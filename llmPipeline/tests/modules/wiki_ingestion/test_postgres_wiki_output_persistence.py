from app.modules.wiki_ingestion.infrastructure import (
    postgres_wiki_output_persistence as persistence,
)


def test_persist_wiki_outputs_keeps_source_and_followup_write_order(
    monkeypatch,
) -> None:
    calls: list[object] = []
    conn = object()
    manifest = {
        "normalized": {
            "document": {"title": "문서"},
            "semantic_notes": [],
            "concept_ledger": [],
        },
        "source_page": {
            "slug": "doc-1",
            "title": "문서",
            "markdown": "# 문서\n",
        },
        "source_blocks": [],
        "concept_pages": [],
        "links": [],
        "user_id": "user-1",
        "workspace_id": "workspace-1",
    }

    monkeypatch.setattr(
        persistence,
        "_persist_source_blocks",
        lambda *_args: calls.append("source_blocks"),
    )
    monkeypatch.setattr(
        persistence,
        "resolve_or_create_wiki_page_id",
        lambda *_args: calls.append("resolve_source") or "source-page-1",
    )
    monkeypatch.setattr(
        persistence,
        "upload_wiki_markdown",
        lambda *_args: calls.append("upload_source") or "wiki/source.md",
    )
    monkeypatch.setattr(
        persistence,
        "upsert_wiki_page",
        lambda *_args: calls.append("upsert_source"),
    )
    monkeypatch.setattr(
        persistence,
        "upsert_document_wiki_link",
        lambda *_args: calls.append("document_link"),
    )
    monkeypatch.setattr(
        persistence,
        "persist_embedding_units",
        lambda *_args: calls.append("embedding_units"),
    )
    monkeypatch.setattr(
        persistence,
        "load_existing_concept_ids_by_slug",
        lambda *_args: calls.append("load_concept_ids") or {},
    )
    monkeypatch.setattr(
        persistence,
        "refresh_source_related_links",
        lambda *_args: calls.append("refresh_source_links"),
    )
    monkeypatch.setattr(
        persistence,
        "_persist_meaning_cluster_artifacts",
        lambda *_args: calls.append("meaning_clusters"),
    )

    page_ids = persistence.persist_wiki_outputs(conn, "doc-1", manifest)  # type: ignore[arg-type]

    assert page_ids == ["source-page-1"]
    assert calls == [
        "source_blocks",
        "resolve_source",
        "upload_source",
        "upsert_source",
        "document_link",
        "embedding_units",
        "load_concept_ids",
        "refresh_source_links",
        "meaning_clusters",
    ]
