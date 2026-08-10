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
    run_cursor.fetchone.return_value = {
        "document_id": "doc-1",
        "user_id": "user-1",
        "workspace_id": "ws-1",
    }
    connection.execute.side_effect = [run_cursor, Mock()]

    with (
        patch.object(database, "connect", return_value=connection),
        patch.object(database, "concept_write_lock", return_value=nullcontext()),
        patch.object(database, "_persist_wiki_outputs") as persist_outputs,
        patch.object(database, "_mark_derived_state_ingested") as mark_derived,
    ):
        database.finish_pipeline_run("run-1", {"manifest": "value"}, "sha256:expected")

    persist_outputs.assert_called_once()
    mark_derived.assert_called_once_with("doc-1", "sha256:expected")
    sql = " ".join(call.args[0] for call in connection.execute.call_args_list)
    assert "documents" not in sql


def test_derived_state_marks_only_the_expected_hash() -> None:
    connection = Mock()
    connection.__enter__ = Mock(return_value=connection)
    connection.__exit__ = Mock(return_value=False)

    with patch.object(database, "connect_ai", return_value=connection):
        database._mark_derived_state_ingested("doc-1", "sha256:expected")

    sql, params = connection.execute.call_args.args
    assert "ingested_hash = %s" in sql
    assert "last_edit_hash = %s" in sql
    assert params == ("sha256:expected", "doc-1", "sha256:expected")


def test_restore_updates_current_markdown_and_links(monkeypatch) -> None:
    monkeypatch.setenv("S3_BUCKET", "wiki-bucket")
    connection = Mock()
    connection.__enter__ = Mock(return_value=connection)
    connection.__exit__ = Mock(return_value=False)

    def execute(query, params=None):
        cursor = Mock()
        normalized = " ".join(str(query).split())
        if normalized.startswith("SELECT id, page_type, slug, user_id"):
            cursor.fetchall.return_value = [
                {
                    "id": "page-c3",
                    "page_type": "concept",
                    "slug": "c3",
                    "user_id": "user-1",
                }
            ]
        elif normalized.startswith("SELECT id FROM wiki_pages"):
            cursor.fetchone.return_value = {"id": "page-target"}
        return cursor

    connection.execute.side_effect = execute
    changed = [{
        "page_id": "page-c3",
        "markdown_key": "wiki/ws-1/pages/page-c3/ops/restore-1.md",
    }]
    links = {"removed_links": [], "restored_links": [{
        "source": "concept:c3",
        "target": "concept:target",
        "relation": "related_to",
    }]}

    with (
        patch.object(database, "connect", return_value=connection),
        patch.object(database, "invalidate_concept_index") as invalidate,
    ):
        database.apply_restored_wiki_state("ws-1", changed, links, True)

    calls = connection.execute.call_args_list
    markdown_call = next(call for call in calls if "SET markdown_uri" in str(call.args[0]))
    assert markdown_call.args[1] == (
        "s3://wiki-bucket/wiki/ws-1/pages/page-c3/ops/restore-1.md",
        "page-c3",
        "ws-1",
    )
    assert any("DELETE FROM wiki_page_links WHERE from_page_id" in str(call.args[0]) for call in calls)
    assert any("INSERT INTO wiki_page_links" in str(call.args[0]) for call in calls)
    invalidate.assert_called_once_with("user-1", "ws-1")
