from app.modules.wiki_ingestion.infrastructure.active_cluster_markdown import (
    merge_active_cluster_markdown,
    parse_active_cluster_lint,
    reconcile_active_cluster_invalidations,
)
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as repository
from app.modules.wiki_ingestion.infrastructure.concept_evidence import append_concept_evidence
from app.modules.wiki_ingestion.infrastructure.embedding_units import extract_embedding_units
from app.modules.wiki_ingestion.infrastructure.postgres_wiki_ingestion_repository import (
    _concept_index_from_markdown,
    _materialize_active_relation_candidates,
    _merge_promotion_into_existing_concept,
    _delete_source_related_links,
    _resolve_or_create_wiki_page_id,
)


def test_lint_merge_does_not_overwrite_previous_operation_snapshot(
    monkeypatch,
) -> None:
    writes = []
    upserts = []

    class Result:
        def fetchone(self):
            return {
                "id": "page-shared",
                "title": "Shared",
                "summary": "공유 개념",
                "markdown_uri": "wiki/ws/pages/page-shared/ops/ingest-A.md",
            }

    class Connection:
        def execute(self, _query, _params):
            return Result()

    monkeypatch.setattr(
        repository,
        "_read_optional_text_object",
        lambda _key: "# Shared\n\n## Evidence\n- 기존 근거\n",
    )
    monkeypatch.setattr(
        repository,
        "write_text_object",
        lambda key, text: writes.append((key, text)) or key,
    )
    monkeypatch.setattr(
        repository,
        "_upsert_wiki_page",
        lambda *args: upserts.append(args),
    )
    monkeypatch.setattr(
        repository,
        "_persist_embedding_units",
        lambda *_args: None,
    )

    change = _merge_promotion_into_existing_concept(
        Connection(),
        "user-1",
        "ws",
        "shared",
        [
            {
                "id": "claim-1",
                "claim": "lint 근거",
                "refs": ["doc-A:B0001"],
            }
        ],
    )

    assert writes[0][0] == "wiki/user-1/ws/concepts/shared.md"
    assert all("ops/ingest-A.md" not in key for key, _text in writes)
    assert upserts[0][6] == "wiki/user-1/ws/concepts/shared.md"
    assert change is not None
    assert "lint 근거" in change["markdown"]


def test_concept_index_uses_markdown_definition_and_evidence() -> None:
    markdown = """---
type: concept
---

# Back EMF

## Definition
Back EMF는 회전 전동기에서 유기되는 역기전력이다. [doc_a:B0001]

## Why It Matters
전동기 성능 평가에 필요하다.

## Aliases
back electromotive force, BEMF

## Evidence
- Back EMF는 토크 리플 및 코깅 토크와 함께 최적화 대상이다. [doc_a:B0002]
- 제조 공차는 Back EMF 응답에 영향을 준다. [doc_b:B0003]

## Reference Summary
- display refs: doc_a:B0001
"""

    concept = _concept_index_from_markdown("back-emf", "Back EMF", "s3://bucket/wiki/concepts/back-emf.md", markdown)

    assert "summary" not in concept
    assert concept["definition"] == "Back EMF는 회전 전동기에서 유기되는 역기전력이다. [doc_a:B0001]"
    assert concept["why_page_worthy"] == "전동기 성능 평가에 필요하다."
    assert concept["aliases"] == ["back electromotive force, BEMF"]
    assert concept["evidence"] == [
        "Back EMF는 토크 리플 및 코깅 토크와 함께 최적화 대상이다. [doc_a:B0002]",
        "제조 공차는 Back EMF 응답에 영향을 준다. [doc_b:B0003]",
    ]


def test_append_concept_evidence_replaces_placeholder_and_deduplicates() -> None:
    markdown = """# Back EMF

## Definition
Back EMF 정의

## Evidence
- 아직 연결된 evidence claim 없음

## Related Concepts
- 관련 개념 없음
"""

    updated_once = append_concept_evidence(
        markdown,
        [
            {
                "claim_id": "claim_001",
                "claim": "Back EMF는 제조 공차의 영향을 받는다.",
                "refs": ["doc_a:B0001"],
            }
        ],
    )
    updated_twice = append_concept_evidence(
        updated_once,
        [
            {
                "claim_id": "claim_001",
                "claim": "Back EMF는 제조 공차의 영향을 받는다.",
                "refs": ["doc_a:B0001"],
            }
        ],
    )

    assert "- 아직 연결된 evidence claim 없음" not in updated_once
    assert updated_once.count("claim_001: Back EMF는 제조 공차의 영향을 받는다. [doc_a:B0001]") == 1
    assert updated_twice.count("claim_001: Back EMF는 제조 공차의 영향을 받는다. [doc_a:B0001]") == 1
    assert "## Related Concepts" in updated_once


def test_embedding_units_extract_global_source_refs() -> None:
    markdown = """# Source

## Key Points
- Back EMF는 제조 공차의 영향을 받는다. [doc_a:B0001, doc_b:B0002]
"""

    units = extract_embedding_units(markdown)

    assert units[0]["block_refs"] == ["doc_a:B0001", "doc_b:B0002"]
    assert units[0]["text"] == "Back EMF는 제조 공차의 영향을 받는다."


def test_resolve_or_create_wiki_page_id_reuses_existing_uuid() -> None:
    class FakeConn:
        def __init__(self) -> None:
            self.rows = []

        def execute(self, _query: str, params: tuple[str, str, str, str]):
            self.rows.append(params)
            return self

        def fetchone(self):
            if len(self.rows) == 1:
                return None
            return {"id": "wiki_page_existing"}

    conn = FakeConn()

    first_id = _resolve_or_create_wiki_page_id(conn, "user_1", "workspace_1", "concept", "back-emf")
    second_id = _resolve_or_create_wiki_page_id(conn, "user_1", "workspace_1", "concept", "back-emf")

    assert first_id.startswith("wiki_page_")
    assert "back-emf" not in first_id
    assert second_id == "wiki_page_existing"


def test_parse_active_cluster_lint_reads_promotion_relations_and_refs() -> None:
    markdown = """# Active Meaning Clusters

## cluster: back-emf

### Evidence Claims
- claim_001: Back EMF는 제조 공차의 영향을 받는다. [doc_a:B0001, doc_b:B0002]

### Core Relation Candidates
- target: concept:tolerance-analysis
  relation: supports_or_enables
  evidence: [doc_a:B0001]
  reason: Back EMF 변화가 공차 분석 근거로 쓰임

### Promotion
status: candidate
source_refs: [doc_a, doc_b]
reason: definition/evidence/relation이 충분함
"""

    clusters = parse_active_cluster_lint(markdown)

    assert clusters == [
        {
            "id": "back-emf",
            "refs": ["doc_a:B0001", "doc_b:B0002"],
            "claims": [
                    {
                        "id": "claim_001",
                        "text": "Back EMF는 제조 공차의 영향을 받는다. [doc_a:B0001, doc_b:B0002]",
                        "claim": "Back EMF는 제조 공차의 영향을 받는다.",
                        "refs": ["doc_a:B0001", "doc_b:B0002"],
                        "decision": "",
                    }
                ],
                "relations": [
                    {
                        "target": "concept:tolerance-analysis",
                        "relation": "supports_or_enables",
                        "evidence": ["doc_a:B0001"],
                        "reason": "Back EMF 변화가 공차 분석 근거로 쓰임",
                    }
                ],
                "invalid_relations": [],
                "promotion_status": "candidate",
                "promotion_source_refs": ["doc_a", "doc_b"],
        }
    ]


def test_merge_active_cluster_markdown_deduplicates_claims_and_relations() -> None:
    existing = """# Active Meaning Clusters

## cluster: back-emf

### Evidence Claims
- claim_001: Back EMF는 제조 공차의 영향을 받는다. [doc_a:B0001]

### Core Relation Candidates
- target: concept:tolerance-analysis
  relation: supports_or_enables
  evidence: [doc_a:B0001]
  reason: 기존 근거
"""
    incoming = """# Active Meaning Clusters

## cluster: back-emf

### Evidence Claims
- claim_001: Back EMF는 제조 공차의 영향을 받는다. [doc_a:B0001]
- claim_002: Back EMF는 토크 리플 분석에도 쓰인다. [doc_b:B0002]

### Core Relation Candidates
- target: concept:tolerance-analysis
  relation: supports_or_enables
  evidence: [doc_a:B0001]
  reason: 중복 근거
- target: concept:torque-ripple
  relation: related_evidence
  evidence: [doc_b:B0002]
  reason: 신규 관계 후보
"""

    merged = merge_active_cluster_markdown(existing, incoming)

    assert merged.count("claim_001") == 1
    assert "claim_002: Back EMF는 토크 리플 분석에도 쓰인다. [doc_b:B0002]" in merged
    assert merged.count("target: concept:tolerance-analysis") == 1
    assert "target: concept:torque-ripple" in merged


def test_materialize_active_relation_candidates_links_existing_concepts_only() -> None:
    class FakeConn:
        def __init__(self) -> None:
            self.link_params = []

        def execute(self, _query: str, params: tuple[str, str, str, str, float]):
            self.link_params.append(params)
            return self

    clusters = [
        {
            "id": "torque-ripple-optimization",
            "claims": [
                {
                    "id": "claim_001",
                    "refs": ["doc_a:B0001"],
                }
            ],
            "relations": [
                {
                    "target": "concept:afpm-motor-optimization",
                    "relation": "part_of",
                    "evidence": ["claim_001"],
                    "reason": "토크 리플 최적화는 AFPM 모터 최적화의 일부",
                },
                {
                    "target": "concept:cogging-torque",
                    "relation": "related_evidence",
                    "evidence": ["claim_001"],
                    "reason": "약한 근거 연결",
                },
                {
                    "target": "concept:missing-concept",
                    "relation": "uses_or_depends_on",
                    "evidence": ["claim_001"],
                    "reason": "대상 concept 없음",
                },
            ],
        }
    ]

    materialized = _materialize_active_relation_candidates(
        FakeConn(),
        clusters,
        {
            "torque-ripple-optimization": "page_source",
            "afpm-motor-optimization": "page_target",
            "cogging-torque": "page_related",
        },
        "workspace-1",
    )

    assert materialized == [
        {
            "from": "torque-ripple-optimization",
            "to": "afpm-motor-optimization",
            "relation": "part_of",
            "evidence": ["claim_001"],
            "source_refs": ["doc_a:B0001"],
        }
    ]


def test_lint_wiki_workspace_dry_run_does_not_write_log(monkeypatch) -> None:
    writes = []
    monkeypatch.setattr(repository, "_read_optional_text_object", lambda _path: "")
    monkeypatch.setattr(repository, "write_text_object", lambda path, text: writes.append((path, text)))
    monkeypatch.setattr(repository, "_list_reconciliation_candidates", lambda *_args: [])

    class FakeConnection:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

    monkeypatch.setattr(repository, "connect", FakeConnection)

    result = repository.lint_wiki_workspace("user_1", "workspace_1", write_log=False)

    assert result["active_path"] == "wiki/user_1/workspace_1/clusters/active.md"
    assert writes == []


def test_lint_blocks_promotion_when_reingest_invalidated_its_source_ref(
    monkeypatch,
) -> None:
    active_markdown = """# Active Meaning Clusters

## cluster: stale-cluster

### Evidence Claims
- claim_001: 수정 전 주장이다. [doc_1:B0002]

### Promotion
status: candidate
source_refs: [doc_1]
"""
    monkeypatch.setattr(
        repository,
        "_read_optional_text_object",
        lambda _path: active_markdown,
    )
    monkeypatch.setattr(repository, "_orphan_source_refs", lambda _refs: [])
    monkeypatch.setattr(
        repository,
        "_list_reconciliation_candidates",
        lambda *_args: [
            {
                "pipeline_run_id": "run-2",
                "document_id": "doc_1",
                "invalidated_source_refs": ["doc_1:B0002"],
                "stale_concept_slugs": [],
                "stale_relations": [],
                "structural_reconciled": False,
                "_current_claim_signatures": [],
                "_current_relation_signatures": [],
            }
        ],
    )

    class FakeConnection:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

    monkeypatch.setattr(repository, "connect", FakeConnection)

    result = repository.lint_wiki_workspace(
        "user_1",
        "workspace_1",
        write_log=False,
    )

    assert result["promotion_candidates"] == []
    assert result["needs_review"] == ["stale-cluster"]


def test_lint_does_not_block_claim_regenerated_by_latest_reingest(
    monkeypatch,
) -> None:
    active_markdown = """# Active Meaning Clusters

## cluster: current-cluster

### Evidence Claims
- claim_002: 수정 후 최신 주장이다. [doc_1:B0002]

### Promotion
status: candidate
source_refs: [doc_1]
"""
    monkeypatch.setattr(
        repository,
        "_read_optional_text_object",
        lambda _path: active_markdown,
    )
    monkeypatch.setattr(repository, "_orphan_source_refs", lambda _refs: [])
    monkeypatch.setattr(
        repository,
        "_list_reconciliation_candidates",
        lambda *_args: [
            {
                "pipeline_run_id": "run-2",
                "document_id": "doc_1",
                "invalidated_source_refs": ["doc_1:B0002"],
                "stale_concept_slugs": [],
                "stale_relations": [],
                "structural_reconciled": False,
                "_current_claim_signatures": [
                    [
                        "current-cluster",
                        "claim_002",
                        "수정 후 최신 주장이다. [doc_1:B0002]",
                    ]
                ],
                "_current_relation_signatures": [],
            }
        ],
    )

    class FakeConnection:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

    monkeypatch.setattr(repository, "connect", FakeConnection)

    result = repository.lint_wiki_workspace(
        "user_1",
        "workspace_1",
        write_log=False,
    )

    assert result["promotion_candidates"] == ["current-cluster"]
    assert result["needs_review"] == []


def test_lint_does_not_remove_claim_for_legacy_reingest_without_signatures(
    monkeypatch,
) -> None:
    active_markdown = """# Active Meaning Clusters

## cluster: legacy-cluster

### Evidence Claims
- claim_001: 최신 여부를 판별할 수 없는 주장이다. [doc_1:B0002]
"""
    reconciled_refs: list[set[str]] = []
    monkeypatch.setattr(
        repository,
        "_read_optional_text_object",
        lambda _path: active_markdown,
    )
    monkeypatch.setattr(repository, "_orphan_source_refs", lambda _refs: [])
    monkeypatch.setattr(
        repository,
        "_list_reconciliation_candidates",
        lambda *_args: [
            {
                "pipeline_run_id": "run-2",
                "document_id": "doc_1",
                "invalidated_source_refs": ["doc_1:B0002"],
                "stale_concept_slugs": [],
                "stale_relations": [],
                "structural_reconciled": False,
                "_cluster_reconciliation_ready": False,
                "_current_claim_signatures": [],
                "_current_relation_signatures": [],
            }
        ],
    )
    monkeypatch.setattr(
        repository,
        "_reconcile_active_cluster_invalidations",
        lambda markdown, refs, *_args: (
            reconciled_refs.append(refs) or markdown,
            [],
            [],
        ),
    )
    monkeypatch.setattr(repository, "_active_relation_keys", lambda *_args: set())
    monkeypatch.setattr(
        repository,
        "_apply_structural_reconciliation",
        lambda *_args: [],
    )

    class FakeConnection:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

    monkeypatch.setattr(repository, "connect", FakeConnection)

    result = repository.lint_wiki_workspace(
        "user_1",
        "workspace_1",
        apply_reconciliation=True,
        write_log=False,
    )

    assert reconciled_refs == [set()]
    assert result["needs_review"] == ["legacy-cluster"]


def test_reconcile_active_clusters_removes_only_stale_reingest_claims() -> None:
    markdown = """# Active Meaning Clusters

## cluster: motor

### Evidence Claims
- claim_old: 수정 전 주장이다. [doc_1:B0002]
- claim_new: 수정 후 주장이다. [doc_1:B0002]
- claim_other: 다른 문서 주장이다. [doc_2:B0001]

### Core Relation Candidates
- target: concept:old-target
  relation: supports_or_enables
  evidence: [claim_old]
  reason: 이전 주장 관계
- target: concept:new-target
  relation: supports_or_enables
  evidence: [claim_old, claim_new]
  reason: 최신 주장 관계
"""

    reconciled, removed_claims, removed_relations = (
        reconcile_active_cluster_invalidations(
            markdown,
            {"doc_1:B0002"},
            {
                (
                    "motor",
                    "claim_new",
                    "수정 후 주장이다. [doc_1:B0002]",
                )
            },
            {
                (
                    "motor",
                    "concept:new-target",
                    "supports_or_enables",
                    ("claim_old", "claim_new"),
                )
            },
        )
    )

    assert "claim_old" not in reconciled
    assert "concept:old-target" not in reconciled
    assert "claim_new" in reconciled
    assert "concept:new-target" in reconciled
    assert "evidence: [claim_new]" in reconciled
    assert "claim_other" in reconciled
    assert removed_claims[0]["claim_id"] == "claim_old"
    assert removed_relations[0]["target"] == "concept:old-target"


def test_reconcile_active_clusters_keeps_partial_relation_evidence_change() -> None:
    markdown = """# Active Meaning Clusters

## cluster: motor

### Core Relation Candidates
- target: concept:target
  relation: supports_or_enables
  evidence: [doc_1:B0002, claim_keep]
  reason: 일부 근거만 무효화
"""

    reconciled, removed_claims, removed_relations = (
        reconcile_active_cluster_invalidations(
            markdown,
            {"doc_1:B0002"},
            set(),
            set(),
        )
    )

    assert "evidence: [claim_keep]" in reconciled
    assert "doc_1:B0002" not in reconciled
    assert removed_claims == []
    assert removed_relations == []


def test_delete_source_related_links_is_scoped_to_workspace() -> None:
    class EmptyRows:
        def fetchall(self):
            return []

    class FakeConn:
        def __init__(self) -> None:
            self.calls = []

        def execute(self, query: str, params=None):
            self.calls.append((" ".join(query.split()), params))
            return EmptyRows()

    conn = FakeConn()

    _delete_source_related_links(conn, "user_1", "workspace_1")

    assert len(conn.calls) == 1
    assert conn.calls[0][1] == ("user_1", "workspace_1", "user_1", "workspace_1")
    assert "DELETE FROM wiki_page_links" in conn.calls[0][0]
    assert "from_page.workspace_id = %s" in conn.calls[0][0]
