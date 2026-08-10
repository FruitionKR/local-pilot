from unittest.mock import MagicMock, patch

import pytest

from app.modules.wiki_ingestion.infrastructure import (
    postgres_wiki_ingestion_repository as database,
)
from app.workers import ingest_worker


def test_create_pipeline_run_ignores_duplicate_run_id() -> None:
    connection = MagicMock()

    with patch.object(database, "connect", return_value=connection):
        database.create_pipeline_run(
            "run-1",
            "document-1",
            "storage:document-1.md",
            "runs/run-1",
            "api",
        )

    query = connection.__enter__.return_value.execute.call_args.args[0]
    assert "ON CONFLICT (id) DO NOTHING" in query


@pytest.mark.parametrize("status", ["succeeded", "failed", "notify_pending"])
def test_handle_skips_terminal_redelivered_run(status: str) -> None:
    repository = MagicMock()
    repository.get_run.return_value = {"status": status}
    command = {
        "run_id": "run-1",
        "document_id": "document-1",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
    }

    with (
        patch.object(ingest_worker, "get_pipeline_run_repository", return_value=repository),
        patch.object(ingest_worker, "_run_pipeline_request") as run_request,
    ):
        ingest_worker._handle(command)

    run_request.assert_not_called()


def test_handle_retries_running_redelivered_run() -> None:
    repository = MagicMock()
    repository.get_run.return_value = {"status": "running"}
    command = {
        "run_id": "run-1",
        "document_id": "document-1",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
    }

    with (
        patch.object(ingest_worker, "get_pipeline_run_repository", return_value=repository),
        patch.object(ingest_worker, "get_pipeline_run_use_case") as get_use_case,
        patch.object(ingest_worker, "get_pipeline_source_reader") as get_source_reader,
        patch.object(ingest_worker, "_run_pipeline_request") as run_request,
    ):
        ingest_worker._handle(command)

    run_request.assert_called_once_with(
        ingest_worker._build_payload(command),
        background_tasks=None,
        use_case=get_use_case.return_value,
        repository=repository,
        source_reader=get_source_reader.return_value,
        run_id="run-1",
    )


def test_handle_records_failure_after_run_registration() -> None:
    repository = MagicMock()
    repository.get_run.return_value = None
    command = {
        "run_id": "run-1",
        "document_id": "document-1",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
    }

    with (
        patch.object(ingest_worker, "get_pipeline_run_repository", return_value=repository),
        patch.object(
            ingest_worker,
            "_run_pipeline_request",
            side_effect=RuntimeError("document lookup failed"),
        ),
    ):
        with pytest.raises(RuntimeError, match="document lookup failed"):
            ingest_worker._handle(command)

    repository.fail.assert_called_once_with("run-1", "document lookup failed")


def test_build_payload_requires_actor_context() -> None:
    with pytest.raises(ValueError, match="user_id and workspace_id"):
        ingest_worker._build_payload({"run_id": "run-1", "document_id": "document-1"})
