import logging
import os
import time
from uuid import uuid4

from app.modules.agent_run.application.agent_worker import AgentWorker
from app.modules.agent_run.infrastructure.backend_tool_gateway import build_backend_tool_gateway
from app.modules.agent_run.infrastructure.chat_completions_plan_generator import build_plan_generator
from app.modules.agent_run.infrastructure.postgres_agent_job_repository import PostgresAgentJobRepository
from app.modules.agent_run.infrastructure.postgres_agent_run_repository import PostgresAgentRunRepository


logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"))
logger = logging.getLogger(__name__)

_CLEANUP_INTERVAL_SECONDS = 24 * 60 * 60


def _cleanup_expired_runs_if_due(
    repository: PostgresAgentJobRepository,
    next_cleanup_at: float,
    now: float,
) -> float:
    if now < next_cleanup_at:
        return next_cleanup_at
    deleted_count = repository.delete_expired_runs()
    logger.info("Deleted %s expired AgentRun records.", deleted_count)
    return now + _CLEANUP_INTERVAL_SECONDS


def main() -> None:
    repository = PostgresAgentJobRepository()
    worker = AgentWorker(
        repository=repository,
        run_repository=PostgresAgentRunRepository(),
        tool_gateway=build_backend_tool_gateway(),
        plan_generator=build_plan_generator(),
    )
    worker_id = os.environ.get("AGENT_WORKER_ID", f"agent-worker-{uuid4()}")
    poll_seconds = float(os.environ.get("AGENT_WORKER_POLL_SECONDS", "1"))
    next_cleanup_at = 0.0
    while True:
        next_cleanup_at = _cleanup_expired_runs_if_due(repository, next_cleanup_at, time.monotonic())
        job = repository.claim_next(worker_id)
        if job is None:
            time.sleep(poll_seconds)
            continue
        worker.process(job)


if __name__ == "__main__":
    main()
