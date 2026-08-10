from argparse import Namespace
from unittest.mock import Mock, call, patch

import pytest

import wiki_db_cutover as cutover


def test_copy_requires_all_wiki_writes_to_be_stopped() -> None:
    with pytest.raises(RuntimeError, match="Stop ingest workers"):
        cutover.copy_data(Namespace(writes_stopped=False))


def test_finalize_requires_all_smoke_tests() -> None:
    with pytest.raises(RuntimeError, match="ingest/query/lint/restore"):
        cutover.finalize_core_permissions(Namespace(smoke_tested=["ingest", "query"]))


def test_cutover_ready_requires_drained_runs_and_revoked_runtime_writes(monkeypatch) -> None:
    monkeypatch.setenv("CORE_DB_RUNTIME_USER", "core_runtime")
    monkeypatch.setenv("AI_DB_RUNTIME_USER", "ai_runtime")
    source = Mock()

    with (
        patch.object(cutover, "_assert_no_active_runs") as no_active_runs,
        patch.object(cutover, "_assert_table_write") as table_write,
    ):
        cutover._assert_cutover_ready(source)

    no_active_runs.assert_called_once_with(source)
    assert table_write.call_args_list == [
        call(source, "core_runtime", cutover.WIKI_TABLES, False),
        call(source, "ai_runtime", cutover.WIKI_TABLES, False),
    ]


def test_permission_check_rejects_remaining_write_access() -> None:
    result = Mock()
    result.fetchone.return_value = (True,)
    connection = Mock()
    connection.execute.return_value = result

    with pytest.raises(RuntimeError, match="wiki_pages"):
        cutover._assert_table_write(connection, "ai_runtime", ("wiki_pages",), False)


def test_cutover_copies_every_ai_owned_current_state_table() -> None:
    assert cutover.WIKI_TABLES == (
        "pipeline_runs",
        "wiki_pages",
        "document_wiki_links",
        "wiki_page_links",
        "source_blocks",
        "wiki_page_embeddings",
        "wiki_embedding_vectors",
        "wiki_embedding_units",
    )


def test_ai_schema_keeps_pipeline_workspace_status_index() -> None:
    schema = cutover.SCHEMA_PATH.read_text(encoding="utf-8")
    assert "idx_pipeline_runs_workspace_status" in schema
    assert "(workspace_id, status, created_at DESC)" in schema
