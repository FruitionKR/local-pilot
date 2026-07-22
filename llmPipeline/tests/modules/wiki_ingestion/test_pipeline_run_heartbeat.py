from unittest.mock import Mock, patch

from app.modules.wiki_ingestion.infrastructure import (
    postgres_wiki_ingestion_repository as database,
)


def test_touch_pipeline_run_updates_only_running_run() -> None:
    connection = Mock()
    connection.__enter__ = Mock(return_value=connection)
    connection.__exit__ = Mock(return_value=False)

    with patch.object(database, "connect", return_value=connection):
        database.touch_pipeline_run("run-1")

    sql, params = connection.execute.call_args.args
    assert "SET updated_at = now()" in sql
    assert "status = 'running'" in sql
    assert params == ("run-1",)
