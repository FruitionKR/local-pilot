from unittest.mock import Mock, patch

import pytest

from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database


def _connection_with_tables(table_names: tuple[str, ...]) -> Mock:
    result = Mock()
    result.fetchall.return_value = [{"table_name": name} for name in table_names]
    connection = Mock()
    connection.execute.return_value = result
    connection.__enter__ = Mock(return_value=connection)
    connection.__exit__ = Mock(return_value=False)
    return connection


def test_verify_schema_accepts_all_flyway_tables() -> None:
    connection = _connection_with_tables(database.REQUIRED_TABLES)

    with patch.object(database, "connect", return_value=connection):
        database.verify_schema()


def test_verify_schema_reports_missing_tables() -> None:
    existing_tables = tuple(
        name for name in database.REQUIRED_TABLES if name != "pipeline_runs"
    )
    connection = _connection_with_tables(existing_tables)

    with (
        patch.object(database, "connect", return_value=connection),
        pytest.raises(RuntimeError, match="missing tables: pipeline_runs"),
    ):
        database.verify_schema()
