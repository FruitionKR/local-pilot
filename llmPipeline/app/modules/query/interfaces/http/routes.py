from fastapi import APIRouter, Depends, HTTPException

from app.modules.query.application.answer_query import AnswerQueryUseCase
from app.modules.query.domain.exceptions import QueryError
from app.modules.query.domain.entities import QueryAnswer
from app.modules.query.interfaces.http.dependencies import get_answer_query_use_case
from app.modules.query.infrastructure.query_event_publisher import build_query_event_publisher
from app.modules.query.interfaces.http.schemas import (
    GraphContextResponse,
    EvidenceSnippetResponse,
    QueryRequest,
    QueryResponse,
    RelatedPageResponse,
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
        event_publisher = build_query_event_publisher(
            callback_url=payload.log_callback_url,
            request_id=payload.request_id,
        )
        result = use_case.execute(payload.question, event_publisher=event_publisher)
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
                rank=snippet.rank,
                source_document_id=snippet.source_document_id,
                source_block_ids=snippet.source_block_ids,
                text=snippet.text,
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
    )
