from app.modules.wiki_generation.infrastructure.source_context_merge import (
    apply_same_source_core_context,
    source_context_blocks,
    source_page_context_normalized,
)


def test_apply_same_source_core_context_adds_previous_candidate_evidence_to_new_core() -> None:
    normalized = {
        "document": {"document_id": "chat_doc"},
        "concept_ledger": [
            {
                "slug": "langsmith-traces",
                "title": "LangSmith Traces",
                "display_reference_ids": ["B0003"],
                "anchor_reference_ids": ["B0003"],
                "source_document_ids": ["chat_doc"],
                "evidence_claim_ids": [],
            }
        ],
        "section_candidates": [
            {
                "title": "LangSmith Traces - 디버깅 핵심 단위",
                "slug": "langsmith-traces-debugging",
                "context": "core와 의미상 겹침",
                "anchor_reference_ids": ["B0003"],
            }
        ],
        "mentions": [
            {
                "name": "run",
                "slug": "run",
                "context": "별도 mention",
                "anchor_reference_ids": ["B0003"],
            }
        ],
        "evidence_units": [],
    }
    source_artifact = {
        "document_id": "chat_doc",
        "section_candidates": [
            {
                "term": "LangSmith Traces",
                "slug": "langsmith-traces",
                "context": "traces 화면에서 run을 확인한다",
                "evidence_block_ids": ["B0001", "B0002"],
            }
        ],
        "mentions": [],
        "evidence_claims": [],
    }

    result, context_blocks = apply_same_source_core_context(normalized, source_artifact)
    concept = result["concept_ledger"][0]

    assert concept["display_reference_ids"] == ["B0003", "B0001", "B0002"]
    assert concept["anchor_reference_ids"] == ["B0003", "B0001", "B0002"]
    assert concept["evidence_claim_ids"] == ["same_source_context_langsmith-traces_001"]
    assert result["evidence_units"][0]["origin"] == "same_source_context"
    assert result["same_source_context_merges"][0]["slug"] == "langsmith-traces"
    assert result["section_candidates"] == []
    assert [item["slug"] for item in result["mentions"]] == ["run"]
    assert [block.block_id for block in context_blocks] == ["B0001", "B0002"]


def test_apply_same_source_core_context_matches_slug_subset_inside_same_source_page() -> None:
    normalized = {
        "document": {"document_id": "chat_doc"},
        "concept_ledger": [
            {
                "slug": "traces",
                "title": "Traces",
                "display_reference_ids": ["chat_session:pair_003"],
                "anchor_reference_ids": ["chat_session:pair_003"],
                "source_document_ids": ["chat_doc"],
                "evidence_claim_ids": [],
            }
        ],
        "evidence_units": [],
    }
    source_artifact = {
        "document_id": "chat_doc",
        "section_candidates": [
            {
                "term": "LangSmith Traces",
                "slug": "langsmith-traces",
                "context": "LangSmith traces 화면에서 run을 확인한다",
                "evidence_block_ids": ["chat_session:pair_001"],
            }
        ],
        "mentions": [],
    }

    result, _ = apply_same_source_core_context(normalized, source_artifact)

    concept = result["concept_ledger"][0]
    assert concept["anchor_reference_ids"] == ["chat_session:pair_003", "chat_session:pair_001"]
    assert result["evidence_units"][0]["related_concept_slugs"] == ["traces"]


def test_source_page_context_normalized_preserves_existing_source_metadata_without_demoting_new_core() -> None:
    normalized = {
        "semantic_notes": [
            {"chunk_id": "new", "semantic_summary": "새 요약", "key_points": [{"text": "새 핵심", "anchor_reference_ids": ["B0005"]}]}
        ],
        "concept_ledger": [
            {"slug": "langsmith-traces", "title": "LangSmith Traces"}
        ],
        "categories": [{"name": "new-category"}],
        "observations": [{"summary": "새 observation", "anchor_reference_ids": ["B0005"]}],
        "evidence_units": [],
        "section_candidates": [
            {"title": "LangSmith Traces - 디버깅 핵심 단위", "slug": "langsmith-traces-debugging", "context": "새 core와 의미상 겹침", "anchor_reference_ids": ["B0005"]}
        ],
        "mentions": [
            {"name": "LangSmith Traces 디버깅", "slug": "langsmith-traces-debugging", "context": "새 core와 의미상 겹침", "anchor_reference_ids": ["B0005"]}
        ],
    }
    source_artifact = {
        "document_id": "chat_doc",
        "core_concepts": [
            {"term": "기존 Core", "slug": "existing-core", "evidence_block_ids": ["B0001"]}
        ],
        "key_points": [{"text": "기존 핵심", "evidence_block_ids": ["B0001"]}],
        "categories": ["existing-category"],
        "observations": [{"summary": "기존 observation", "anchor_reference_ids": ["B0001"]}],
        "evidence_claims": [{"claim": "기존 claim", "anchor_reference_ids": ["B0001"]}],
        "section_candidates": [
            {"term": "LangSmith Traces", "slug": "langsmith-traces", "context": "승격 대상", "evidence_block_ids": ["B0002"]},
            {"term": "남는 Section", "slug": "remaining-section", "context": "아직 후보", "evidence_block_ids": ["B0003"]},
        ],
        "mentions": [
            {"term": "남는 Mention", "slug": "remaining-mention", "context": "단순 언급", "evidence_block_ids": ["B0004"]}
        ],
    }

    result = source_page_context_normalized(normalized, source_artifact)

    assert result["semantic_notes"][0]["chunk_id"] == "existing_source_page"
    assert result["semantic_notes"][0]["key_points"][0]["text"] == "기존 핵심"
    assert result["semantic_notes"][1]["chunk_id"] == "new"
    assert [item["name"] for item in result["categories"]] == ["existing-category", "new-category"]
    assert [item["summary"] for item in result["observations"]] == ["기존 observation", "새 observation"]
    assert [item["claim"] for item in result["evidence_units"]] == ["기존 claim"]
    assert [item["slug"] for item in result["concept_ledger"]] == ["existing-core", "langsmith-traces"]
    assert [item["slug"] for item in result["section_candidates"]] == ["remaining-section"]
    assert [item["slug"] for item in result["mentions"]] == ["remaining-mention"]


def test_source_page_context_normalized_keeps_new_core_not_in_existing_source_artifact() -> None:
    normalized = {
        "concept_ledger": [
            {"slug": "new-core", "title": "New Core"}
        ],
        "section_candidates": [],
        "mentions": [],
    }
    source_artifact = {
        "document_id": "chat_doc",
        "core_concepts": [],
        "section_candidates": [],
        "mentions": [],
    }

    result = source_page_context_normalized(normalized, source_artifact)

    assert [item["slug"] for item in result["concept_ledger"]] == ["new-core"]


def test_source_context_blocks_exposes_existing_artifact_refs_for_source_polish() -> None:
    source_artifact = {
        "document_id": "chat_doc",
        "key_points": [{"text": "기존 핵심", "evidence_block_ids": ["chat:pair_001"]}],
        "observations": [{"summary": "기존 observation", "anchor_reference_ids": ["chat:pair_002"]}],
        "core_concepts": [],
        "section_candidates": [],
        "mentions": [],
        "evidence_claims": [],
    }

    blocks = source_context_blocks(source_artifact)

    assert [block.block_id for block in blocks] == ["chat:pair_001", "chat:pair_002"]
    assert blocks[0].text == "기존 핵심"
