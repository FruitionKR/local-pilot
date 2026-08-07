from unittest.mock import Mock, patch

import pytest

from app.core.pipeline_control import PipelineRunCancelledError
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
    assert "d.deleted_at IS NULL" in sql
    assert params == ("run-1",)


def test_touch_pipeline_run_returns_false_for_inactive_target() -> None:
    connection = Mock()
    connection.__enter__ = Mock(return_value=connection)
    connection.__exit__ = Mock(return_value=False)
    connection.execute.return_value.fetchone.return_value = None

    with patch.object(database, "connect", return_value=connection):
        active = database.touch_pipeline_run("run-deleted")

    assert active is False


def test_finish_pipeline_run_rechecks_target_before_wiki_write() -> None:
    connection = Mock()
    connection.__enter__ = Mock(return_value=connection)
    connection.__exit__ = Mock(return_value=False)
    run_cursor = Mock()
    run_cursor.fetchone.return_value = {"document_id": "doc-1"}
    active_document_cursor = Mock()
    active_document_cursor.fetchone.return_value = None
    connection.execute.side_effect = [run_cursor, active_document_cursor]

    with (
        patch.object(database, "connect", return_value=connection),
        patch.object(database, "_persist_wiki_outputs") as persist_outputs,
        pytest.raises(PipelineRunCancelledError),
    ):
        database.finish_pipeline_run("run-1", {"manifest": "value"})

    persist_outputs.assert_not_called()
    active_sql, active_params = connection.execute.call_args_list[1].args
    assert "d.deleted_at IS NULL" in active_sql
    assert "FOR SHARE OF d" in active_sql
    assert active_params == ("doc-1",)
