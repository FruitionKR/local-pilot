"""Query·Agent·maintenance Kafka command worker."""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import signal
from itertools import count
from typing import Any

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from psycopg.types.json import Json

from app.core.llm_env import resolve_llm_selection
from app.modules.agent.interfaces.http.dependencies import build_handle_agent_turn_use_case
from app.modules.agent.interfaces.http.routes import _to_response as agent_to_response
from app.modules.agent.interfaces.http.schemas import AgentTurnRequestBody
from app.modules.query.application.ports import QueryEventPublisherPort
from app.modules.query.domain.entities import ConversationContext, ConversationMessage
from app.modules.query.interfaces.http.dependencies import build_answer_query_use_case
from app.modules.query.interfaces.http.routes import _to_response as query_to_response
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database
from app.modules.wiki_ingestion.interfaces.http.dependencies import get_wiki_maintenance
from app.modules.wiki_ingestion.interfaces.http.dependencies import get_restore_wiki_pages_use_case
from app.modules.wiki_ingestion.interfaces.http.schemas import (
    IngestOperationRestoreIn,
    LintOperationRestoreIn,
    WikiLintIn,
    WikiLintOut,
)
from app.modules.wiki_ingestion.infrastructure.backend_document_reader import read_contributions
from app.workers.event_request import without_top_level_secrets


logger = logging.getLogger("task_worker")
BOOTSTRAP_SERVERS = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
COMMAND_TOPIC = os.environ.get("AI_COMMAND_TOPIC", "ai.query.command")
RESULT_TOPIC = os.environ.get("AI_TASK_EVENT_TOPIC", "ai.task.event")
GROUP_ID = os.environ.get("AI_COMMAND_CONSUMER_GROUP", f"{COMMAND_TOPIC}-worker")
MAX_POLL_INTERVAL_MS = int(os.environ.get("AI_TASK_MAX_POLL_INTERVAL_MS", "1800000"))


def _required(command: dict[str, Any], *fields: str) -> None:
    missing = [
        field
        for field in fields
        if command.get(field) in (None, "")
        or isinstance(command.get(field), str)
        and not command[field].strip()
    ]
    if missing:
        raise ValueError(f"AI command requires: {', '.join(missing)}")


def _handle_query(
    command: dict[str, Any],
    event_publisher: QueryEventPublisherPort | None = None,
) -> dict[str, Any]:
    _required(
        command,
        "run_id",
        "workspace_id",
        "user_id",
        "session_id",
        "question",
        "provider",
        "model",
        "allow_web_search",
    )
    if not isinstance(command["allow_web_search"], bool):
        raise ValueError("allow_web_search must be a boolean")
    result = build_answer_query_use_case(
        provider=str(command["provider"]).strip(),
        model=str(command["model"]).strip(),
        allow_web_search=command["allow_web_search"],
        event_publisher=event_publisher,
    ).execute(
        str(command["question"]),
        workspace_id=str(command["workspace_id"]),
        user_id=str(command["user_id"]),
        conversation_context=ConversationContext(
            recent_messages=tuple(
                ConversationMessage(role=message["role"], content=message["content"])
                for message in command.get("recent_messages", [])
            )
        ) if command.get("recent_messages") else None,
        allow_web_search=command["allow_web_search"],
    )
    return query_to_response(result).model_dump(mode="json")


def _handle_agent(command: dict[str, Any]) -> dict[str, Any]:
    # 문서를 열지 않은 턴은 편집 대상이 없어 document_id·base_version·apply_operation_id·
    # editor_snapshot이 오지 않는다. 이때 AI는 chat_answer·clarify·reject만 낸다.
    _required(command, "run_id", "workspace_id", "user_id", "message")
    run_id = str(command["run_id"])
    state, replay = _register_agent_command(command)
    if state == "completed":
        return replay or {}

    try:
        payload = AgentTurnRequestBody.model_validate({
            "message": command["message"],
            "provider": command.get("provider"),
            "model": command.get("model"),
            "skill_mode": command.get("skill_mode", "auto"),
            "skill_id": command.get("skill_id"),
            "workspace_id": command["workspace_id"],
            "user_id": command["user_id"],
            "conversation_context": command.get("conversation_context"),
            "active_markdown_context": command.get("editor_snapshot"),
            "skill_draft_sources": command.get("skill_draft_sources", []),
            "skill_draft_user_directives": command.get("skill_draft_user_directives", []),
            "skill_draft_excluded_literals": command.get("skill_draft_excluded_literals", []),
            "skill_scope_type": command.get("skill_scope_type"),
        })
        result = agent_to_response(
            build_handle_agent_turn_use_case(
                provider=payload.provider,
                model=payload.model,
            ).execute(payload.to_domain())
        ).model_dump(mode="json")
    except Exception:
        with database.connect_ai() as conn:
            conn.execute(
                """
                UPDATE agent_runs SET status = 'failed', error_code = 'agent_turn_failed',
                    updated_at = now(), finished_at = now() WHERE id = %s
                """,
                (run_id,),
            )
            conn.execute(
                "UPDATE agent_jobs SET status = 'failed', updated_at = now() WHERE run_id = %s AND job_type = 'markdown_turn'",
                (run_id,),
            )
        raise
    with database.connect_ai() as conn:
        conn.execute(
            """
            UPDATE agent_runs SET status = 'completed', result = %s, error_code = NULL,
                updated_at = now(), finished_at = now() WHERE id = %s
            """,
            (Json(result), run_id),
        )
        conn.execute(
            "UPDATE agent_jobs SET status = 'completed', updated_at = now() WHERE run_id = %s AND job_type = 'markdown_turn'",
            (run_id,),
        )
    return result


def _agent_command_hash(command: dict[str, Any]) -> str:
    canonical = json.dumps(command, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _register_agent_command(command: dict[str, Any]) -> tuple[str, dict[str, Any] | None]:
    run_id = str(command["run_id"])
    provider = model = None
    selection_error: ValueError | None = None
    raw_provider = command.get("provider")
    raw_model = command.get("model")
    if not isinstance(raw_provider, str) or not isinstance(raw_model, str):
        selection_error = ValueError("provider and model are required")
    else:
        try:
            provider, model = resolve_llm_selection(raw_provider, raw_model)
        except ValueError as exc:
            selection_error = exc
    envelope_hash = _agent_command_hash(command)
    deferred_error: ValueError | RuntimeError | None = None
    with database.connect_ai() as conn:
        inserted = conn.execute(
            """
            INSERT INTO agent_runs (
                id, workspace_id, user_id, action, status, request_summary,
                provider, model, document_id, base_version, apply_operation_id,
                command_envelope_hash
            ) VALUES (%s, %s, %s, 'markdown_turn', 'queued', %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (id) DO NOTHING
            RETURNING id
            """,
            (
                run_id,
                str(command["workspace_id"]),
                str(command["user_id"]),
                str(command["message"])[:1000],
                provider,
                model,
                str(command["document_id"]),
                int(command["base_version"]),
                str(command["apply_operation_id"]),
                envelope_hash,
            ),
        ).fetchone()
        if inserted:
            conn.execute(
                """
                INSERT INTO agent_jobs (id, run_id, job_type, status)
                VALUES (%s, %s, 'markdown_turn', 'queued')
                ON CONFLICT (id) DO NOTHING
                """,
                (f"{run_id}:markdown_turn", run_id),
            )
            if selection_error is not None:
                conn.execute(
                    """
                    UPDATE agent_runs
                    SET status = 'failed', error_code = 'invalid_llm_selection',
                        updated_at = now(), finished_at = now()
                    WHERE id = %s
                    """,
                    (run_id,),
                )
                conn.execute(
                    """
                    UPDATE agent_jobs SET status = 'failed', updated_at = now()
                    WHERE run_id = %s AND job_type = 'markdown_turn'
                    """,
                    (run_id,),
                )
        run = conn.execute(
            """
            SELECT status, result, command_envelope_hash
            FROM agent_runs WHERE id = %s AND action = 'markdown_turn' FOR UPDATE
            """,
            (run_id,),
        ).fetchone()
        if run is None or run["command_envelope_hash"] != envelope_hash:
            raise ValueError("Agent command envelope does not match the registered run")
        if selection_error is not None:
            deferred_error = selection_error
        elif run["status"] == "completed":
            return "completed", dict(run["result"] or {})
        elif run["status"] == "failed":
            raise ValueError("Agent run is already failed")
        elif run["status"] == "executing":
            raise RuntimeError("Agent run is already executing")
        else:
            conn.execute(
                "UPDATE agent_runs SET status = 'executing', updated_at = now() WHERE id = %s",
                (run_id,),
            )
            conn.execute(
                "UPDATE agent_jobs SET status = 'executing', updated_at = now() WHERE id = %s",
                (f"{run_id}:markdown_turn",),
            )
    if deferred_error is not None:
        raise deferred_error
    return "execute", None


def _handle_lint(command: dict[str, Any]) -> dict[str, Any]:
    _required(command, "run_id", "workspace_id", "user_id", "provider", "model")
    run_id = str(command["run_id"])
    existing = database.get_pipeline_run(run_id)
    if existing:
        for field in ("workspace_id", "user_id"):
            if str(existing.get(field)) != str(command[field]):
                raise ValueError(f"maintenance command does not match registered {field}")
        if existing.get("status") == "succeeded":
            return dict((existing.get("manifest") or {}).get("task_result") or {})
        if existing.get("status") == "failed":
            raise ValueError(str(existing.get("error") or "maintenance run failed"))
    else:
        database.create_pipeline_run(
            run_id, None, str(command["user_id"]), str(command["workspace_id"]),
            "kafka:lint", f"runs/{run_id}", "lint",
        )
    try:
        lint_payload = {
            field: command.get(field)
            for field in (
                "user_id",
                "workspace_id",
                "operation_id",
                "materialize_promotions",
                "dry_run",
                "provider",
                "model",
            )
            if command.get(field) is not None
        }
        lint_payload["model"] = str(command["model"]).strip()
        lint_payload["provider"] = str(command["provider"]).strip()
        result = get_wiki_maintenance().lint(
            WikiLintIn.model_validate(lint_payload).to_command()
        )
        payload = WikiLintOut.model_validate(result).model_dump(mode="json")
        database.finish_pipeline_run(run_id, {"task_result": payload})
        return payload
    except Exception as exc:
        database.fail_pipeline_run(run_id, str(exc))
        raise


def _validate_restore_contributions(command: dict[str, Any]) -> None:
    expected = command.get("expected_contributions") or {}
    if not expected:
        return
    rows = read_contributions(list(expected), str(command["workspace_id"]))
    current: dict[str, list[str]] = {str(page_id): [] for page_id in expected}
    for row in sorted(rows, key=lambda item: int(item.get("sequence_revision") or 0)):
        page_id = str(row.get("page_id") or "")
        current.setdefault(page_id, []).append(
            f"{row.get('ingest_operation_id')}:{int(row.get('sequence_revision') or 0)}:"
            f"{'1' if row.get('active') else '0'}"
        )
    if current != {str(key): list(value) for key, value in expected.items()}:
        raise ValueError("restore contribution manifest is stale")


def _handle_restore(command: dict[str, Any]) -> dict[str, Any]:
    _required(command, "run_id", "workspace_id", "user_id", "operation_id")
    run_id = str(command["run_id"])
    existing = database.get_pipeline_run(run_id)
    if existing:
        for field in ("workspace_id", "user_id"):
            if str(existing.get(field)) != str(command[field]):
                raise ValueError(f"restore command does not match registered {field}")
        if existing.get("status") == "succeeded":
            return dict((existing.get("manifest") or {}).get("task_result") or {})
        if existing.get("status") == "failed":
            raise ValueError(str(existing.get("error") or "restore run failed"))
    else:
        database.create_pipeline_run(
            run_id, None, str(command["user_id"]), str(command["workspace_id"]),
            f"kafka:{command['kind']}", f"runs/{run_id}", str(command["kind"]),
        )
    try:
        _validate_restore_contributions(command)
        use_case = get_restore_wiki_pages_use_case()
        if command["kind"] == "restore_ingest":
            result = use_case.execute_ingest(
                IngestOperationRestoreIn.model_validate(command).to_command()
            )
        else:
            result = use_case.execute_lint(
                LintOperationRestoreIn.model_validate(command).to_command()
            )
        database.finish_pipeline_run(run_id, {"task_result": result})
        return result
    except Exception as exc:
        database.fail_pipeline_run(run_id, str(exc))
        raise


def _handle(command: dict[str, Any]) -> dict[str, Any]:
    kind = str(command.get("kind") or "")
    if kind == "query":
        return _handle_query(command)
    if kind == "agent":
        return _handle_agent(command)
    if kind == "lint":
        return _handle_lint(command)
    if kind in {"restore_ingest", "restore_lint"}:
        return _handle_restore(command)
    raise ValueError(f"unsupported AI command kind: {kind}")


def _event(
    command: dict[str, Any],
    status: str,
    payload: Any = None,
    error: str | None = None,
    *,
    with_request: bool = True,
) -> dict[str, Any]:
    """`with_request=False`는 소비 측이 원본 command를 보지 않는 이벤트에 쓴다."""
    run_id = str(command.get("run_id") or "")
    kind = str(command.get("kind") or "unknown")
    event: dict[str, Any] = {
        "event_id": f"{kind}:{run_id}:{status}",
        "run_id": run_id,
        "kind": kind,
        "status": status,
        "workspace_id": command.get("workspace_id"),
        "user_id": command.get("user_id"),
        "operation_id": command.get("operation_id"),
    }
    if with_request:
        event["request"] = without_top_level_secrets(command)
    event["payload"] = payload
    event["error"] = error
    return event


def _progress_event(
    command: dict[str, Any],
    *,
    sequence: int,
    stage: str,
    message: str,
    data: dict[str, object] | None,
) -> dict[str, Any]:
    # 소비 측은 진행 이벤트에서 run_id·event_id·status·payload만 읽는다.
    # command 전체(질문·대화 이력)를 단계마다 다시 실어 보내지 않는다.
    event = _event(
        command,
        "progress",
        payload={"stage": stage, "message": message, "data": data or {}},
        with_request=False,
    )
    event["event_id"] = f"query:{event['run_id']}:progress:{sequence}:{stage}"
    return event


class KafkaQueryEventPublisher(QueryEventPublisherPort):
    """동기 query pipeline의 진행 이벤트를 worker event loop의 Kafka producer로 전달한다."""

    # 진행 이벤트는 유실을 허용한다. Kafka가 응답하지 않을 때 답변 생성 스레드를
    # 무기한 붙잡지 않도록 제한을 둔다. 호출자(publish_query_event)가 예외를 삼킨다.
    SEND_TIMEOUT_SECONDS = 5

    def __init__(
        self,
        producer: AIOKafkaProducer,
        loop: asyncio.AbstractEventLoop,
        command: dict[str, Any],
    ) -> None:
        self._producer = producer
        self._loop = loop
        self._command = command
        self._sequences = count(1)

    def publish(
        self,
        stage: str,
        message: str,
        data: dict[str, object] | None = None,
    ) -> None:
        event = _progress_event(
            self._command,
            sequence=next(self._sequences),
            stage=stage,
            message=message,
            data=data,
        )
        future = asyncio.run_coroutine_threadsafe(
            self._producer.send_and_wait(
                RESULT_TOPIC,
                event,
                key=event["run_id"].encode("utf-8"),
            ),
            self._loop,
        )
        try:
            future.result(self.SEND_TIMEOUT_SECONDS)
        except TimeoutError:
            future.cancel()
            raise


def _failure_is_durable(command: dict[str, Any]) -> bool:
    kind = str(command.get("kind") or "")
    run_id = str(command.get("run_id") or "")
    if kind == "query" or kind not in {"agent", "lint", "restore_ingest", "restore_lint"}:
        return True
    try:
        if kind == "agent":
            with database.connect_ai() as conn:
                row = conn.execute(
                    "SELECT status FROM agent_runs WHERE id = %s",
                    (run_id,),
                ).fetchone()
            return row is not None and row["status"] in {"completed", "failed"}
        run = database.get_pipeline_run(run_id)
        return run is not None and run.get("status") in {"succeeded", "failed"}
    except Exception:
        return False


async def consume() -> None:
    database.ensure_ai_schema()
    if COMMAND_TOPIC.endswith("agent.command"):
        database.verify_agent_schema()
    consumer = AIOKafkaConsumer(
        COMMAND_TOPIC,
        bootstrap_servers=BOOTSTRAP_SERVERS,
        group_id=GROUP_ID,
        enable_auto_commit=False,
        max_poll_records=1,
        max_poll_interval_ms=MAX_POLL_INTERVAL_MS,
        auto_offset_reset="earliest",
        value_deserializer=lambda raw: json.loads(raw.decode("utf-8")),
    )
    producer = AIOKafkaProducer(
        bootstrap_servers=BOOTSTRAP_SERVERS,
        enable_idempotence=True,
        value_serializer=lambda value: json.dumps(value, ensure_ascii=False).encode("utf-8"),
    )
    await consumer.start()
    await producer.start()
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    logger.info("[worker 기동] topic=%s group=%s", COMMAND_TOPIC, GROUP_ID)
    try:
        while not stop.is_set():
            batch = await consumer.getmany(timeout_ms=1000, max_records=1)
            for _tp, messages in batch.items():
                for message in messages:
                    command = message.value
                    try:
                        if command.get("kind") == "query":
                            event_publisher = KafkaQueryEventPublisher(producer, loop, command)
                            result = await asyncio.to_thread(
                                _handle_query,
                                command,
                                event_publisher,
                            )
                        else:
                            result = await asyncio.to_thread(_handle, command)
                        event = _event(command, "succeeded", payload=result)
                    except Exception as exc:
                        logger.exception("[AI command 실패] kind=%s run_id=%s", command.get("kind"), command.get("run_id"))
                        if not _failure_is_durable(command):
                            raise
                        event = _event(command, "failed", error=str(exc)[:1000])
                    await producer.send_and_wait(RESULT_TOPIC, event, key=event["run_id"].encode("utf-8"))
                    await consumer.commit()
    finally:
        await producer.stop()
        await consumer.stop()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    asyncio.run(consume())


if __name__ == "__main__":
    main()
