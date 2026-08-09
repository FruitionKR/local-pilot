from unittest.mock import Mock

from app.modules.wiki_ingestion.infrastructure import (
    postgres_wiki_output_persistence as persistence,
)


def _stub_followup_writes(monkeypatch) -> None:
    monkeypatch.setattr(persistence, "_persist_source_blocks", lambda *_args: None)
    monkeypatch.setattr(
        persistence,
        "delete_source_related_links",
        lambda *_args: None,
    )
    monkeypatch.setattr(
        persistence,
        "_persist_meaning_cluster_artifacts",
        lambda *_args: [],
    )


def test_persist_source_blocks_clears_existing_rows_for_empty_blocks() -> None:
    conn = Mock()

    persistence._persist_source_blocks(
        conn,
        "doc-1",
        {"source_blocks": []},
    )

    conn.execute.assert_called_once()
    query, params = conn.execute.call_args.args
    assert "DELETE FROM source_blocks WHERE document_id = %s" in query
    assert params == ("doc-1",)


def test_persist_wiki_outputs_keeps_source_and_followup_write_order(
    monkeypatch,
) -> None:
    calls: list[object] = []
    conn = Mock()
    conn.execute.return_value.fetchone.return_value = None
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
        "delete_source_related_links",
        lambda *_args: calls.append("delete_source_links"),
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
        "delete_source_links",
        "meaning_clusters",
    ]


def test_persist_wiki_outputs_persists_concept_and_page_link(monkeypatch) -> None:
    manifest = {
        "normalized": {
            "document": {"title": "문서"},
            "semantic_notes": [],
            "concept_ledger": [
                {
                    "slug": "concept-a",
                    "title": "개념 A",
                    "definition": "정의",
                    "importance_score": 0.8,
                }
            ],
        },
        "source_page": {"title": "문서", "markdown": "# 문서\n"},
        "source_blocks": [],
        "concept_pages": [
            {"slug": "concept-a", "markdown": "# 개념 A\n"}
        ],
        "links": [
            {
                "source": "source:doc-1",
                "target": "concept:concept-a",
                "relation": "source_mentions_concept",
                "label": "언급",
                "confidence": 0.9,
            }
        ],
        "user_id": "user-1",
        "workspace_id": "workspace-1",
    }
    conn = Mock()
    conn.execute.return_value.fetchone.return_value = None
    page_links: list[tuple[object, ...]] = []
    _stub_followup_writes(monkeypatch)
    monkeypatch.setattr(
        persistence,
        "resolve_or_create_wiki_page_id",
        lambda _conn, _user, _workspace, page_type, _slug: (
            "source-page-1" if page_type == "source" else "concept-page-1"
        ),
    )
    monkeypatch.setattr(
        persistence,
        "upload_wiki_markdown",
        lambda _markdown, object_name: object_name,
    )
    monkeypatch.setattr(persistence, "upsert_wiki_page", lambda *_args: None)
    monkeypatch.setattr(
        persistence,
        "upsert_document_wiki_link",
        lambda *_args: None,
    )
    monkeypatch.setattr(
        persistence,
        "persist_embedding_units",
        lambda *_args: None,
    )
    monkeypatch.setattr(
        persistence,
        "load_existing_concept_ids_by_slug",
        lambda *_args: {},
    )
    monkeypatch.setattr(
        persistence,
        "upsert_wiki_page_link",
        lambda *args: page_links.append(args),
    )

    page_ids = persistence.persist_wiki_outputs(conn, "doc-1", manifest)  # type: ignore[arg-type]

    assert page_ids == ["source-page-1", "concept-page-1"]
    assert page_links == [
        (
            conn,
            "source-page-1",
            "concept-page-1",
            "source_mentions_concept",
            "언급",
            0.9,
            "workspace-1",
        )
    ]


def test_persist_wiki_outputs_connects_operation_artifacts(monkeypatch) -> None:
    manifest = {
        "operation_id": "op-1",
        "normalized": {
            "document": {"title": "문서"},
            "semantic_notes": [],
            "concept_ledger": [
                {
                    "slug": "concept-a",
                    "title": "개념 A",
                    "definition": "정의",
                },
                {
                    "slug": "concept-b",
                    "title": "개념 B",
                    "definition": "기존 정의",
                },
            ],
        },
        "source_page": {"title": "문서", "markdown": "# 문서\n"},
        "source_blocks": [],
        "concept_pages": [
            {
                "slug": "concept-a",
                "title": "개념 A",
                "markdown": "# 개념 A\n",
            },
            {
                "slug": "concept-b",
                "title": "개념 B",
                "markdown": "# 개념 B\n",
            },
        ],
        "concept_contributions": {
            "concept-a": {
                "schema_version": 1,
                "operation_id": "op-1",
                "concept": {"slug": "concept-a"},
            }
        },
        "links": [],
        "user_id": "user-1",
        "workspace_id": "workspace-1",
    }
    captured: dict = {}
    events: list[str] = []
    markdown_uploads: list[str] = []
    page_upserts: list[tuple[object, ...]] = []
    _stub_followup_writes(monkeypatch)
    monkeypatch.setattr(
        persistence,
        "resolve_or_create_wiki_page_id",
        lambda _conn, _user, _workspace, page_type, slug: (
            "source-page-1"
            if page_type == "source"
            else {
                "concept-a": "concept-page-1",
                "concept-b": "concept-page-2",
            }[slug]
        ),
    )
    monkeypatch.setattr(
        persistence,
        "upload_wiki_markdown",
        lambda _markdown, object_name: (
            events.append("canonical")
            or markdown_uploads.append(object_name)
            or object_name
        ),
    )
    monkeypatch.setattr(
        persistence,
        "upsert_wiki_page",
        lambda *args: page_upserts.append(args),
    )
    monkeypatch.setattr(
        persistence,
        "upsert_document_wiki_link",
        lambda *_args: None,
    )
    monkeypatch.setattr(
        persistence,
        "persist_embedding_units",
        lambda *_args: None,
    )
    monkeypatch.setattr(
        persistence,
        "load_existing_concept_ids_by_slug",
        lambda *_args: {},
    )
    monkeypatch.setattr(persistence, "upsert_wiki_page_link", lambda *_args: None)
    monkeypatch.setattr(
        persistence,
        "persist_operation_artifacts",
        lambda **kwargs: (
            events.append("artifact")
            or captured.update(kwargs)
            or [{"page_id": "source-page-1"}]
        ),
    )

    conn = Mock()
    conn.execute.return_value.fetchone.return_value = None
    persistence.persist_wiki_outputs(conn, "doc-1", manifest)

    assert captured["operation_id"] == "op-1"
    assert captured["workspace_id"] == "workspace-1"
    assert captured["source_page_id"] == "source-page-1"
    # operation artifact 저장은 DB 반영(canonical markdown 업로드)이 모두 끝난 뒤 마지막에 일어나야
    # 트랜잭션 롤백 시 orphan object가 남지 않는다.
    assert events[-1] == "artifact"
    assert markdown_uploads == [
        "wiki/user-1/workspace-1/sources/doc-1.md",
        "wiki/user-1/workspace-1/concepts/concept-a.md",
        "wiki/user-1/workspace-1/concepts/concept-b.md",
    ]
    assert len(page_upserts) == 3
    assert captured["concept_pages"] == [
        {
            "page_id": "concept-page-1",
            "slug": "concept-a",
            "markdown": "# 개념 A\n",
        }
    ]
    assert manifest["operation_artifacts"] == [{"page_id": "source-page-1"}]


def test_persist_wiki_outputs_skips_operation_artifacts_when_later_step_fails(
    monkeypatch,
) -> None:
    """DB 반영 후속 단계가 실패하면 operation artifact를 아예 쓰지 않아야 한다.

    operation artifact를 먼저 쓰고 뒤에서 실패하면 트랜잭션은 롤백되지만
    이미 쓴 object storage 파일은 orphan으로 남는다. write_text_object가
    한 번도 호출되지 않는지로 orphan이 생기지 않았음을 검증한다.
    """
    manifest = {
        "operation_id": "op-1",
        "normalized": {
            "document": {"title": "문서"},
            "semantic_notes": [],
            "concept_ledger": [],
        },
        "source_page": {"title": "문서", "markdown": "# 문서\n"},
        "source_blocks": [],
        "concept_pages": [],
        "concept_contributions": {},
        "links": [],
        "user_id": "user-1",
        "workspace_id": "workspace-1",
    }
    written_keys: list[str] = []
    monkeypatch.setattr(persistence, "_persist_source_blocks", lambda *_args: None)
    monkeypatch.setattr(
        persistence,
        "resolve_or_create_wiki_page_id",
        lambda *_args: "source-page-1",
    )
    monkeypatch.setattr(
        persistence,
        "upload_wiki_markdown",
        lambda _markdown, key: key,
    )
    monkeypatch.setattr(persistence, "upsert_wiki_page", lambda *_args: None)
    monkeypatch.setattr(
        persistence, "upsert_document_wiki_link", lambda *_args: None
    )
    monkeypatch.setattr(persistence, "persist_embedding_units", lambda *_args: None)
    monkeypatch.setattr(
        persistence, "load_existing_concept_ids_by_slug", lambda *_args: {}
    )
    monkeypatch.setattr(
        persistence, "delete_source_related_links", lambda *_args: None
    )

    def boom(*_args):
        raise RuntimeError("meaning cluster persistence failed")

    monkeypatch.setattr(persistence, "_persist_meaning_cluster_artifacts", boom)
    monkeypatch.setattr(
        persistence,
        "write_text_object",
        lambda key, text, content_type="text/markdown; charset=utf-8": (
            written_keys.append(key) or f"s3://bucket/{key}"
        ),
    )

    try:
        persistence.persist_wiki_outputs(object(), "doc-1", manifest)  # type: ignore[arg-type]
    except RuntimeError:
        pass
    else:
        raise AssertionError("meaning cluster 실패가 전파돼야 한다")

    assert written_keys == []


def test_operation_artifacts_include_existing_concept_evidence_updates(
    monkeypatch,
) -> None:
    manifest = {
        "operation_id": "op-1",
        "normalized": {
            "document": {"title": "문서"},
            "semantic_notes": [],
            "concept_ledger": [],
        },
        "source_page": {"title": "문서", "markdown": "# 문서\n"},
        "source_blocks": [],
        "concept_pages": [],
        "concept_contributions": {
            "existing": {
                "operation_id": "op-1",
                "concept": {"slug": "existing"},
            }
        },
        "links": [],
        "meaning_clusters": {},
        "user_id": "user-1",
        "workspace_id": "workspace-1",
    }
    captured = {}
    _stub_followup_writes(monkeypatch)
    monkeypatch.setattr(
        persistence,
        "resolve_or_create_wiki_page_id",
        lambda *_args: "source-page-1",
    )
    monkeypatch.setattr(
        persistence,
        "upload_wiki_markdown",
        lambda _markdown, key: key,
    )
    monkeypatch.setattr(persistence, "upsert_wiki_page", lambda *_args: None)
    monkeypatch.setattr(
        persistence,
        "upsert_document_wiki_link",
        lambda *_args: None,
    )
    monkeypatch.setattr(
        persistence,
        "persist_embedding_units",
        lambda *_args: None,
    )
    monkeypatch.setattr(
        persistence,
        "load_existing_concept_ids_by_slug",
        lambda *_args: {"existing": "existing-page-1"},
    )
    monkeypatch.setattr(
        persistence,
        "_prepare_concept_update_decisions",
        lambda *_args: [
            {
                "page_id": "existing-page-1",
                "slug": "existing",
                "markdown": "# Existing\n\n새 근거\n",
            }
        ],
    )
    monkeypatch.setattr(
        persistence,
        "_persist_meaning_cluster_artifacts",
        lambda *_args: [],
    )
    monkeypatch.setattr(
        persistence,
        "persist_operation_artifacts",
        lambda **kwargs: captured.update(kwargs) or [],
    )

    persistence.persist_wiki_outputs(object(), "doc-1", manifest)  # type: ignore[arg-type]

    assert captured["concept_pages"] == [
        {
            "page_id": "existing-page-1",
            "slug": "existing",
            "markdown": "# Existing\n\n새 근거\n",
        }
    ]


def test_existing_concept_update_writes_canonical_current_markdown(
    monkeypatch,
) -> None:
    writes = []

    class Result:
        def fetchone(self):
            return {
                "id": "existing-page-1",
                "markdown_uri": "wiki/ws/pages/existing-page-1/ops/op-old.md",
            }

    class Connection:
        def execute(self, _query, _params):
            return Result()

    monkeypatch.setattr(
        persistence,
        "read_optional_text_object",
        lambda _key: "# Existing\n\n## Evidence\n- 기존 근거\n",
    )
    monkeypatch.setattr(
        persistence,
        "write_text_object",
        lambda key, text: writes.append((key, text)) or f"s3://bucket/{key}",
    )
    monkeypatch.setattr(
        persistence,
        "persist_embedding_units",
        lambda *_args: None,
    )

    changes = persistence._apply_concept_update_decisions(
        Connection(),
        "doc-1",
        "user-1",
        "ws",
        [
            {
                "decision": "same_concept",
                "concept_slug": "existing",
                "claim_id": "claim-1",
                "claim": "새 근거",
                "refs": ["doc-1:B0001"],
            }
        ],
    )

    assert writes[0][0] == "wiki/user-1/ws/concepts/existing.md"
    assert all("ops/op-old.md" not in key for key, _text in writes)
    assert changes[0]["page_id"] == "existing-page-1"
    assert "새 근거" in changes[0]["markdown"]


def test_persist_wiki_outputs_reads_normalized_and_links_artifacts(
    monkeypatch,
    tmp_path,
) -> None:
    normalized_path = tmp_path / "normalized.json"
    normalized_path.write_text(
        '{"document": {"title": "파일 제목"}, "concept_ledger": []}',
        encoding="utf-8",
    )
    links_path = tmp_path / "links.json"
    links_path.write_text(
        '[{"source": "source:doc-1", "target": "concept:existing"}]',
        encoding="utf-8",
    )
    manifest = {
        "out": str(tmp_path),
        "source_page": {"markdown": "본문"},
        "source_blocks": [],
        "concept_pages": [],
        "links": str(links_path),
        "user_id": "user-1",
        "workspace_id": "workspace-1",
    }
    page_titles: list[str] = []
    page_links: list[tuple[object, ...]] = []
    _stub_followup_writes(monkeypatch)
    monkeypatch.setattr(
        persistence,
        "resolve_or_create_wiki_page_id",
        lambda *_args: "source-page-1",
    )
    monkeypatch.setattr(
        persistence,
        "upload_wiki_markdown",
        lambda _markdown, object_name: object_name,
    )
    monkeypatch.setattr(
        persistence,
        "upsert_wiki_page",
        lambda _conn, _page_id, _page_type, title, *_args: page_titles.append(title),
    )
    monkeypatch.setattr(
        persistence,
        "upsert_document_wiki_link",
        lambda *_args: None,
    )
    monkeypatch.setattr(
        persistence,
        "persist_embedding_units",
        lambda *_args: None,
    )
    monkeypatch.setattr(
        persistence,
        "load_existing_concept_ids_by_slug",
        lambda *_args: {"existing": "concept-page-1"},
    )
    monkeypatch.setattr(
        persistence,
        "upsert_wiki_page_link",
        lambda *args: page_links.append(args),
    )

    page_ids = persistence.persist_wiki_outputs(object(), "doc-1", manifest)  # type: ignore[arg-type]

    assert page_ids == ["source-page-1"]
    assert page_titles == ["파일 제목"]
    assert page_links[0][1:4] == (
        "source-page-1",
        "concept-page-1",
        "related_to",
    )
