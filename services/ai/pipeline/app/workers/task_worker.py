"""Query·Agent·maintenance Kafka command worker."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import signal
from typing import Any

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from psycopg.types.json import Json

from app.modules.agent.interfaces.http.dependencies import get_handle_agent_turn_use_case
from app.modules.agent.interfaces.http.routes import _to_response as agent_to_response
from app.modules.agent.interfaces.http.schemas import AgentTurnRequestBody
from app.modules.query.interfaces.http.dependencies import get_answer_query_use_case
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


logger = logging.getLogger("task_worker")
BOOTSTRAP_SERVERS = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
COMMAND_TOPIC = os.environ.get("AI_COMMAND_TOPIC", "ai.query.command")
RESULT_TOPIC = os.environ.get("AI_TASK_EVENT_TOPIC", "ai.task.event")
GROUP_ID = os.environ.get("AI_COMMAND_CONSUMER_GROUP", f"{COMMAND_TOPIC}-worker")
MAX_POLL_INTERVAL_MS = int(os.environ.get("AI_TASK_MAX_POLL_INTERVAL_MS", "1800000"))


def _required(command: dict[str, Any], *fields: str) -> None:
    missing = [field for field in fields if command.get(field) in (None, "")]
    if missing:
        raise ValueError(f"AI command requires: {', '.join(missing)}")


def _handle_query(command: dict[str, Any]) -> dict[str, Any]:
    _required(command, "run_id", "workspace_id", "user_id", "session_id", "question")
    result = get_answer_query_use_case().execute(
        str(command["question"]),
        workspace_id=str(command["workspace_id"]),
        user_id=str(command["user_id"]),
    )
    return query_to_response(result).model_dump(mode="json")


def _handle_agent(command: dict[str, Any]) -> dict[str, Any]:
    _required(
        command, "run_id", "workspace_id", "user_id", "document_id",
        "base_version", "apply_operation_id", "message", "editor_snapshot",
    )
    run_id = str(command["run_id"])
    with database.connect_core() as conn:
        run = conn.execute(
            """
            SELECT workspace_id, user_id, document_id, base_version, apply_operation_id,
                   status, result
            FROM agent_runs WHERE id = %s AND action = 'markdown_turn' FOR UPDATE
            """,
            (run_id,),
        ).fetchone()
        if run is None:
            raise ValueError("Agent run is not registered")
        for field in ("workspace_id", "user_id", "document_id", "base_version", "apply_operation_id"):
            if str(run[field]) != str(command[field]):
                raise ValueError(f"Agent command does not match registered {field}")
        if run["status"] == "completed":
            return dict(run["result"] or {})
        if run["status"] == "failed":
            raise ValueError("Agent run is already failed")
        conn.execute(
            "UPDATE agent_runs SET status = 'executing', updated_at = now() WHERE id = %s",
            (run_id,),
        )

    try:
        payload = AgentTurnRequestBody.model_validate({
            "message": command["message"],
            "workspace_id": command["workspace_id"],
            "user_id": command["user_id"],
            "conversation_context": command.get("conversation_context"),
            "active_markdown_context": command["editor_snapshot"],
        })
        result = agent_to_response(
            get_handle_agent_turn_use_case().execute(payload.to_domain())
        ).model_dump(mode="json")
    except Exception:
        with database.connect_core() as conn:
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
    with database.connect_core() as conn:
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


def _handle_lint(command: dict[str, Any]) -> dict[str, Any]:
    _required(command, "run_id", "workspace_id", "user_id")
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
        result = get_wiki_maintenance().lint(WikiLintIn.model_validate(command).to_command())
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


def _event(command: dict[str, Any], status: str, payload: Any = None, error: str | None = None) -> dict[str, Any]:
    run_id = str(command.get("run_id") or "")
    kind = str(command.get("kind") or "unknown")
    return {
        "event_id": f"{kind}:{run_id}:{status}",
        "run_id": run_id,
        "kind": kind,
        "status": status,
        "workspace_id": command.get("workspace_id"),
        "user_id": command.get("user_id"),
        "operation_id": command.get("operation_id"),
        "request": command,
        "payload": payload,
        "error": error,
    }


def _failure_is_durable(command: dict[str, Any]) -> bool:
    kind = str(command.get("kind") or "")
    run_id = str(command.get("run_id") or "")
    if kind == "query" or kind not in {"agent", "lint", "restore_ingest", "restore_lint"}:
        return True
    try:
        if kind == "agent":
            with database.connect_core() as conn:
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
