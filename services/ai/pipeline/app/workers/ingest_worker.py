"""ingest command Kafka worker.

backend가 `ai.ingest.command` topic에 발행한 문서/채팅 Wiki ingest 명령을 소비해
기존 HTTP 실행 경로(`_run_pipeline_request`)를 그대로 재사용한다.

동시성 모델:
- 같은 workspace_id는 같은 partition → 워크스페이스별 순차, 워크스페이스 간 병렬.
- worker 프로세스당 1건씩 처리(max_poll_records=1). 처리량은 worker 수 × partition으로 늘린다.
- LLM ingest는 분 단위 작업이라 max_poll_interval을 30분으로 늘려 리밸런싱을 막는다.
  (heartbeat는 aiokafka 백그라운드 태스크가 유지하고, 실행은 to_thread로 이벤트 루프를 비워 둔다)

전달 보장:
- 수동 커밋: 처리(성공/실패 기록)가 끝난 뒤에만 offset을 커밋한다.
  worker가 중간에 죽으면 재전달되고, 이미 종료된 run은 멱등하게 건너뛴다.
- 실패한 run의 상태·결과 통지는 기존 use case가 pipeline_runs와 result 콜백으로 기록한다.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import signal

from aiokafka import AIOKafkaConsumer

from app.modules.wiki_ingestion.infrastructure import (
    postgres_wiki_ingestion_repository as database,
)
from app.modules.wiki_ingestion.interfaces.http.dependencies import (
    get_pipeline_run_repository,
    get_pipeline_run_use_case,
    get_pipeline_source_reader,
)
from app.modules.wiki_ingestion.interfaces.http.routes import _run_pipeline_request
from app.modules.wiki_ingestion.interfaces.http.schemas import (
    ChatWikiRunIn,
    PipelineRunIn,
)

logger = logging.getLogger("ingest_worker")

TOPIC = os.environ.get("INGEST_COMMAND_TOPIC", "ai.ingest.command")
BOOTSTRAP_SERVERS = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
GROUP_ID = os.environ.get("INGEST_CONSUMER_GROUP", "ingest-worker")
TERMINAL_STATUSES = {"succeeded", "failed", "notify_pending"}
# ingest 1건이 분 단위라 poll 간격 한도를 넉넉히 둔다. 이 시간을 넘기면 리밸런싱된다.
MAX_POLL_INTERVAL_MS = int(os.environ.get("INGEST_MAX_POLL_INTERVAL_MS", "1800000"))


def _build_payload(command: dict) -> PipelineRunIn | ChatWikiRunIn:
    if not command.get("user_id") or not command.get("workspace_id"):
        raise ValueError("ingest command requires user_id and workspace_id")
    common = {
        "document_id": command["document_id"],
        "user_id": command.get("user_id"),
        "workspace_id": command.get("workspace_id"),
        "log_callback_url": command.get("log_callback_url"),
        "operation_id": command.get("operation_id"),
        "result_callback_url": command.get("result_callback_url"),
        "wait": True,
    }
    if command.get("kind") == "chat_wiki":
        return ChatWikiRunIn(
            **common,
            selection_mode=command.get("selection_mode") or "full",
            input_markdown=command.get("input_markdown"),
        )
    return PipelineRunIn(**common)


def _handle(command: dict) -> None:
    if command.get("kind") == "document_deleted":
        database.delete_document_wiki_data(command["workspace_id"], command["document_id"])
        return

    run_id = command["run_id"]
    repository = get_pipeline_run_repository()
    existing = repository.get_run(run_id)
    if existing and any(
        existing.get(field) is not None
        and str(existing[field]) != str(command.get(field))
        for field in ("document_id", "user_id", "workspace_id")
    ):
        error = "ingest command does not match the registered run context"
        if existing.get("status") not in TERMINAL_STATUSES:
            repository.fail(run_id, error)
        raise ValueError(error)
    if existing and existing.get("status") in TERMINAL_STATUSES:
        logger.info(
            "[ingest 재전달 무시] run_id=%s 이미 종료됨 status=%s",
            run_id,
            existing.get("status"),
        )
        return
    payload = _build_payload(command)
    logger.info(
        "[ingest 실행 시작] run_id=%s kind=%s document_id=%s workspace_id=%s",
        run_id, command.get("kind"), command.get("document_id"), command.get("workspace_id"),
    )
    try:
        _run_pipeline_request(
            payload,
            background_tasks=None,
            use_case=get_pipeline_run_use_case(),
            repository=repository,
            source_reader=get_pipeline_source_reader(),
            run_id=run_id,
        )
    except Exception as exc:
        repository.fail(run_id, str(exc))
        raise
    logger.info("[ingest 실행 완료] run_id=%s", run_id)


async def consume() -> None:
    # ai_db 스키마 준비 보장 (api 기동 경로와 동일한 공용 부트스트랩)
    database.ensure_ai_schema()
    consumer = AIOKafkaConsumer(
        TOPIC,
        bootstrap_servers=BOOTSTRAP_SERVERS,
        group_id=GROUP_ID,
        enable_auto_commit=False,
        max_poll_records=1,
        max_poll_interval_ms=MAX_POLL_INTERVAL_MS,
        auto_offset_reset="earliest",
        value_deserializer=lambda raw: json.loads(raw.decode("utf-8")),
    )
    await consumer.start()
    logger.info("[worker 기동] topic=%s group=%s bootstrap=%s", TOPIC, GROUP_ID, BOOTSTRAP_SERVERS)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    try:
        while not stop.is_set():
            batch = await consumer.getmany(timeout_ms=1000, max_records=1)
            for _tp, messages in batch.items():
                for message in messages:
                    try:
                        # 실행은 스레드로 넘겨 이벤트 루프(heartbeat)를 비워 둔다.
                        await asyncio.to_thread(_handle, message.value)
                    except Exception:
                        logger.exception(
                            "[ingest 실행 실패] run_id=%s",
                            message.value.get("run_id") if isinstance(message.value, dict) else "?",
                        )
                        run_id = message.value.get("run_id") if isinstance(message.value, dict) else None
                        try:
                            durable = get_pipeline_run_repository().get_run(run_id) if run_id else None
                        except Exception:
                            durable = None
                        if durable is None or durable.get("status") not in TERMINAL_STATUSES:
                            # terminal run까지 못 남긴 실패는 offset을 전진시키지 않고 컨테이너 재시작으로 재수신한다.
                            raise
                    await consumer.commit()
    finally:
        await consumer.stop()
        logger.info("[worker 종료]")


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    asyncio.run(consume())


if __name__ == "__main__":
    main()
