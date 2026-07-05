from app.modules.query.domain.entities import RetrievedPage, RetrievalSummary


def build_retrieval_summary(
    *,
    related_pages: list[RetrievedPage],
    source_candidate_count: int,
    concept_candidate_count: int,
    stop_reason: str,
    max_depth: int = 0,
) -> RetrievalSummary:
    return RetrievalSummary(
        source_candidate_count=source_candidate_count,
        concept_candidate_count=concept_candidate_count,
        visited_node_count=len(related_pages),
        returned_node_count=len(related_pages),
        used_source_count=len([item for item in related_pages if item.page.is_source]),
        used_concept_count=len([item for item in related_pages if item.page.is_concept]),
        max_depth=max_depth,
        stop_reason=stop_reason,
    )
