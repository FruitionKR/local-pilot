import logging
import os
import time
from uuid import uuid4

import psycopg
from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from psycopg.rows import dict_row

from app.modules.agent_run.infrastructure.agent_worker import AgentWorker
from app.modules.agent_run.infrastructure.backend_tool_gateway import build_backend_tool_gateway
from app.modules.agent_run.infrastructure.chat_completions_execution_decider import build_execution_decider
from app.modules.agent_run.infrastructure.chat_completions_plan_generator import build_plan_generator
from app.modules.agent_run.infrastructure.postgres_agent_job_repository import PostgresAgentJobRepository
from app.modules.agent_run.infrastructure.postgres_agent_run_repository import PostgresAgentRunRepository
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database


logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"))
logger = logging.getLogger(__name__)

_CLEANUP_INTERVAL_SECONDS = 24 * 60 * 60


def _cleanup_expired_runs_if_due(
    repository: PostgresAgentJobRepository,
    checkpointer: PostgresSaver,
    next_cleanup_at: float,
    now: float,
) -> float:
    if now < next_cleanup_at:
        return next_cleanup_at
    run_ids = repository.list_expired_run_ids()
    for run_id in run_ids:
        checkpointer.delete_thread(run_id)
    deleted_count = repository.delete_expired_runs(run_ids)
    logger.info("Deleted %s expired AgentRun records.", deleted_count)
    return now + _CLEANUP_INTERVAL_SECONDS


def main() -> None:
    repository = PostgresAgentJobRepository()
    with psycopg.connect(
        database.database_url(),
        autocommit=True,
        row_factory=dict_row,
    ) as connection:
        checkpointer = PostgresSaver(
            connection,
            serde=JsonPlusSerializer(allowed_msgpack_modules=()),
        )
        worker = AgentWorker(
            repository=repository,
            run_repository=PostgresAgentRunRepository(),
            tool_gateway=build_backend_tool_gateway(),
            plan_generator=build_plan_generator(),
            execution_decider=build_execution_decider(),
            checkpointer=checkpointer,
        )
        worker_id = os.environ.get("AGENT_WORKER_ID", f"agent-worker-{uuid4()}")
        poll_seconds = float(os.environ.get("AGENT_WORKER_POLL_SECONDS", "1"))
        next_cleanup_at = 0.0
        while True:
            next_cleanup_at = _cleanup_expired_runs_if_due(
                repository,
                checkpointer,
                next_cleanup_at,
                time.monotonic(),
            )
            job = repository.claim_next(worker_id)
            if job is None:
                time.sleep(poll_seconds)
                continue
            worker.process(job)


if __name__ == "__main__":
    main()
