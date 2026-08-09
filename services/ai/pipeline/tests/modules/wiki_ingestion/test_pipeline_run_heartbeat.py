from contextlib import nullcontext
from unittest.mock import Mock, patch
from app.modules.wiki_ingestion.infrastructure import (
    postgres_wiki_ingestion_repository as database,
)


def test_touch_pipeline_run_updates_only_running_run() -> None:
    connection = Mock()
    connection.__enter__ = Mock(return_value=connection)
    connection.__exit__ = Mock(return_value=False)
    connection.execute.return_value.fetchone.return_value = {"id": "run-1"}

    with patch.object(database, "connect", return_value=connection):
        active = database.touch_pipeline_run("run-1")

    sql, params = connection.execute.call_args.args
    assert active is True
    assert "SET updated_at = now()" in sql
    assert "pr.status = 'running'" in sql
    assert "documents" not in sql
    assert params == ("run-1",)


def test_touch_pipeline_run_returns_false_for_inactive_target() -> None:
    connection = Mock()
    connection.__enter__ = Mock(return_value=connection)
    connection.__exit__ = Mock(return_value=False)
    connection.execute.return_value.fetchone.return_value = None

    with patch.object(database, "connect", return_value=connection):
        active = database.touch_pipeline_run("run-deleted")

    assert active is False


def test_finish_pipeline_run_writes_only_ai_owned_tables() -> None:
    connection = Mock()
    connection.__enter__ = Mock(return_value=connection)
    connection.__exit__ = Mock(return_value=False)
    run_cursor = Mock()
    run_cursor.fetchone.return_value = {"document_id": "doc-1", "workspace_id": "ws-1"}
    connection.execute.side_effect = [run_cursor, Mock()]

    with (
        patch.object(database, "connect", return_value=connection),
        patch.object(database, "concept_write_lock", return_value=nullcontext()),
        patch.object(database, "_persist_wiki_outputs") as persist_outputs,
        patch.object(database, "_mark_derived_state_ingested"),
    ):
        database.finish_pipeline_run("run-1", {"manifest": "value"})

    persist_outputs.assert_called_once()
    sql = " ".join(call.args[0] for call in connection.execute.call_args_list)
    assert "documents" not in sql
