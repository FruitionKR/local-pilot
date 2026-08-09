from unittest.mock import patch

import pytest

from app.workers import task_worker


def test_event_uses_common_command_envelope() -> None:
    command = {
        "run_id": "run-1",
        "kind": "query",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "operation_id": None,
    }

    event = task_worker._event(command, "succeeded", {"answer": "ok"})

    assert event["event_id"] == "query:run-1:succeeded"
    assert event["request"] == command
    assert event["payload"] == {"answer": "ok"}


def test_restore_rejects_changed_contribution_manifest() -> None:
    command = {
        "workspace_id": "workspace-1",
        "expected_contributions": {"page-1": ["op-1:1:1"]},
    }
    rows = [{"page_id": "page-1", "ingest_operation_id": "op-2", "sequence_revision": 2, "active": True}]

    with (
        patch.object(task_worker, "read_contributions", return_value=rows),
        pytest.raises(ValueError, match="manifest is stale"),
    ):
        task_worker._validate_restore_contributions(command)


def test_unknown_command_is_rejected() -> None:
    with pytest.raises(ValueError, match="unsupported AI command kind"):
        task_worker._handle({"run_id": "run-1", "kind": "unknown"})


def test_maintenance_failure_requires_terminal_run() -> None:
    command = {"run_id": "run-1", "kind": "lint"}

    with patch.object(task_worker.database, "get_pipeline_run", return_value=None):
        assert task_worker._failure_is_durable(command) is False
    with patch.object(
        task_worker.database,
        "get_pipeline_run",
        return_value={"status": "failed"},
    ):
        assert task_worker._failure_is_durable(command) is True
