from fastapi import APIRouter, Depends, HTTPException

from app.modules.query.application.answer_query import AnswerQueryUseCase
from app.modules.query.domain.exceptions import QueryError
from app.modules.query.domain.entities import QueryAnswer
from app.modules.query.interfaces.http.dependencies import get_answer_query_use_case
from app.modules.query.interfaces.http.schemas import (
    GraphContextResponse,
    EvidenceSnippetResponse,
    QueryRequest,
    QueryResponse,
    RelatedPageResponse,
    RetrievalSummaryResponse,
    TraversalEdgeResponse,
    TraversalPathResponse,
)


router = APIRouter(prefix="/query", tags=["query"])


@router.post("", response_model=QueryResponse)
def answer_query(
    payload: QueryRequest,
    use_case: AnswerQueryUseCase = Depends(get_answer_query_use_case),
) -> QueryResponse:
    try:
        result = use_case.execute(payload.question)
    except QueryError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return _to_response(result)


def _to_response(result: QueryAnswer) -> QueryResponse:
    return QueryResponse(
        answer=result.answer.content,
        related_pages=[
            RelatedPageResponse(
                id=item.page.id,
                page_type=item.page.page_type,
                title=item.page.title,
                slug=item.page.slug,
                relevance_score=item.score,
                role=item.role,
                depth=item.depth,
            )
            for item in result.related_pages
        ],
        evidence_snippets=[
            EvidenceSnippetResponse(
                page_id=snippet.page_id,
                page_type=snippet.page_type,
                page_title=snippet.page_title,
                page_slug=snippet.page_slug,
                page_url=snippet.page_url,
                page_role=snippet.page_role,
                text=snippet.text,
                score=snippet.score,
                rank=snippet.rank,
            )
            for snippet in result.evidence_snippets
        ],
        graph_context=GraphContextResponse(
            nodes=[
                RelatedPageResponse(
                    id=item.page.id,
                    page_type=item.page.page_type,
                    title=item.page.title,
                    slug=item.page.slug,
                    relevance_score=item.score,
                    role=item.role,
                    depth=item.depth,
                )
                for item in result.graph_context.nodes
            ],
            edges=[
                TraversalEdgeResponse(
                    from_page_id=edge.from_page_id,
                    to_page_id=edge.to_page_id,
                    link_type=edge.link_type,
                    role=edge.role,
                    score=edge.score,
                )
                for edge in result.graph_context.edges
            ],
        ),
        traversal_paths=[
            TraversalPathResponse(
                path_id=path.path_id,
                role=path.role,
                used_for_answer=path.used_for_answer,
                score=path.score,
                stop_reason=path.stop_reason,
                nodes=path.nodes,
                edges=[
                    TraversalEdgeResponse(
                        from_page_id=edge.from_page_id,
                        to_page_id=edge.to_page_id,
                        link_type=edge.link_type,
                        role=edge.role,
                        score=edge.score,
                    )
                    for edge in path.edges
                ],
            )
            for path in result.traversal_paths
        ],
        retrieval_summary=RetrievalSummaryResponse(
            source_candidate_count=result.retrieval_summary.source_candidate_count,
            concept_candidate_count=result.retrieval_summary.concept_candidate_count,
            visited_node_count=result.retrieval_summary.visited_node_count,
            returned_node_count=result.retrieval_summary.returned_node_count,
            used_source_count=result.retrieval_summary.used_source_count,
            used_concept_count=result.retrieval_summary.used_concept_count,
            max_depth=result.retrieval_summary.max_depth,
            stop_reason=result.retrieval_summary.stop_reason,
        ),
    )
