import json
from pathlib import Path

from app.modules.wiki_generation.domain.entities import SourceBlock, SourceDocument
from app.modules.wiki_generation.infrastructure.assemble import SourcePageAssembler
from app.modules.wiki_generation.infrastructure.normalize import SemanticNormalizer


def test_source_extraction_artifact_uses_embedding_friendly_terms(tmp_path: Path) -> None:
    document = SourceDocument(
        document_id="doc_test",
        title="테스트 문서",
        source_path="examples/test.md",
        content_sha1="abc123",
    )
    blocks = [
        SourceBlock("doc_test", "B0001", "ref_test_md_b0001", "핵심 개념은 검색 인덱스이다.", 1, 1),
        SourceBlock("doc_test", "B0002", "ref_test_md_b0002", "토큰화는 검색 인덱스를 설명하는 하위 절이다.", 2, 2),
        SourceBlock("doc_test", "B0003", "ref_test_md_b0003", "BM25는 예시로 언급된다.", 3, 3),
    ]
    notes = [
        {
            "chunk_id": "chunk_0001",
            "semantic_summary": "검색 인덱스를 설명하는 문서이다.",
            "key_points": [{"text": "검색 인덱스가 핵심이다.", "anchor_block_ids": ["B0001"]}],
            "observations": [
                {
                    "type": "qa_episode",
                    "title": "검색 인덱스의 역할 질문",
                    "query_text": "검색 인덱스가 왜 필요해?",
                    "summary": "검색 인덱스는 문서를 검색 가능한 구조로 바꿔 후속 질의의 후보가 된다.",
                    "claims": ["검색 인덱스는 후속 질의 후보를 찾는 데 쓰인다."],
                    "related_concept_hints": ["search-index"],
                    "anchor_block_ids": ["B0001", "B0002"],
                }
            ],
            "categories": [{"name": "technology"}],
            "core_concepts": [
                {
                    "title": "검색 인덱스",
                    "slug_hint": "search-index",
                    "aliases": ["search index"],
                    "definition": "검색 가능한 구조화 표현이다.",
                    "why_page_worthy": "문서 이해의 중심 개념이다.",
                    "evidence_block_ids": ["B0001"],
                }
            ],
            "section_candidates": [
                {
                    "title": "토큰화",
                    "slug_hint": "tokenization",
                    "context": "검색 인덱스를 설명하는 하위 항목이다.",
                    "evidence_block_ids": ["B0002"],
                }
            ],
            "mentions": [
                {
                    "name": "BM25",
                    "slug_hint": "bm25",
                    "context": "검색 예시로 언급된다.",
                    "evidence_block_ids": ["B0003"],
                }
            ],
            "evidence_claims": [],
            "needs_neighbor_context": False,
            "context_problem": None,
        }
    ]

    normalized = SemanticNormalizer(document, blocks).normalize_notes(notes)
    source_page = SourcePageAssembler().assemble(normalized, tmp_path)
    artifact_path = Path(source_page).with_suffix(".json")
    artifact = json.loads(artifact_path.read_text(encoding="utf-8"))

    assert artifact["schema_version"] == "source-extraction.v1"
    assert artifact["categories"] == ["technology"]
    assert artifact["observations"][0]["observation_id"] == "O001"
    assert artifact["observations"][0]["type"] == "qa_episode"
    assert artifact["observations"][0]["anchor_reference_ids"] == ["B0001", "B0002"]
    assert artifact["core_concepts"][0]["term"] == "검색 인덱스"
    assert artifact["core_concepts"][0]["slug"] == "search-index"
    assert artifact["core_concepts"][0]["context"] == "검색 가능한 구조화 표현이다."
    assert artifact["section_candidates"][0]["context"] == "검색 인덱스를 설명하는 하위 항목이다."
    assert artifact["mentions"][0]["term"] == "BM25"
    assert "bucket" not in artifact["core_concepts"][0]
    assert "source_reference_ids" not in artifact["core_concepts"][0]
    assert "terms" not in artifact
    assert "## Observations" in Path(source_page).read_text(encoding="utf-8")
    assert "O001 (qa_episode)" in Path(source_page).read_text(encoding="utf-8")


def test_concept_aliases_preserve_llm_aliases_without_backend_expansion() -> None:
    document = SourceDocument(
        document_id="doc_alias",
        title="Alias 문서",
        source_path="examples/alias.md",
        content_sha1="def456",
    )
    blocks = [
        SourceBlock("doc_alias", "B0001", "ref_alias_md_b0001", "3계층 아키텍처를 설명한다.", 1, 1),
    ]
    notes = [
        {
            "chunk_id": "chunk_0001",
            "semantic_summary": "alias 테스트 문서이다.",
            "key_points": [],
            "observations": [],
            "categories": [],
            "core_concepts": [
                {
                    "title": "Three-Layer Architecture",
                    "slug_hint": "three-layer-architecture",
                    "aliases": ["3계층 아키텍처", "3-layer architecture"],
                    "definition": "원시 문서, 위키, 스키마로 구성된 구조이다.",
                    "why_page_worthy": "시스템 구조를 설명하는 핵심 개념이다.",
                    "evidence_block_ids": ["B0001"],
                }
            ],
            "section_candidates": [],
            "mentions": [],
            "evidence_claims": [],
            "needs_neighbor_context": False,
            "context_problem": None,
        }
    ]

    normalized = SemanticNormalizer(document, blocks).normalize_notes(notes)
    aliases = normalized["concept_ledger"][0]["aliases"]

    assert "Three-Layer Architecture" in aliases
    assert "3-layer architecture" in aliases
    assert "3계층 아키텍처" in aliases
    assert "three-layer architecture" not in aliases
    assert "three-layer-architecture" not in aliases
