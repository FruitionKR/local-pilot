from app.modules.wiki_ingestion.infrastructure.postgres_wiki_writer import (
    build_source_related_links,
)


def test_build_source_related_links_returns_sorted_source_pairs() -> None:
    rows = [
        {"source_id": "source-b", "concept_id": "concept-1", "concept_title": "공통"},
        {"source_id": "source-a", "concept_id": "concept-1", "concept_title": "공통"},
        {"source_id": "source-c", "concept_id": "concept-1", "concept_title": "공통"},
    ]

    links = build_source_related_links(rows)

    assert [
        (link.from_page_id, link.to_page_id, link.label, link.confidence)
        for link in links
    ] == [
        ("source-a", "source-b", "공유 concept: 공통", 1.0),
        ("source-a", "source-c", "공유 concept: 공통", 1.0),
        ("source-b", "source-c", "공유 concept: 공통", 1.0),
    ]


def test_build_source_related_links_filters_scores_below_threshold() -> None:
    rows = [
        {"source_id": "source-a", "concept_id": "shared", "concept_title": "공통"},
        {"source_id": "source-a", "concept_id": "unique", "concept_title": "고유"},
        {"source_id": "source-b", "concept_id": "shared", "concept_title": "공통"},
    ]

    assert build_source_related_links(rows) == []
