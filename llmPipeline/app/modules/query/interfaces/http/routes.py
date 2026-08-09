from fastapi import APIRouter, Depends, HTTPException

from app.modules.query.application.answer_query import AnswerQueryUseCase
from app.modules.query.domain.exceptions import QueryError
from app.modules.query.domain.entities import ConversationContext, QueryAnswer
from app.modules.query.interfaces.http.dependencies import get_answer_query_use_case
from app.modules.query.infrastructure.query_event_publisher import build_query_event_publisher
from app.modules.query.interfaces.http.schemas import (
    GraphContextResponse,
    EvidenceSnippetResponse,
    QueryRequest,
    QueryResponse,
    RelatedPageResponse,
    SourceReferenceResponse,
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
        result = use_case.execute(
            payload.question,
            workspace_id=payload.workspace_id,
            user_id=payload.user_id,
            event_publisher=event_publisher,
            conversation_context=_conversation_context(payload),
            output_language=payload.output_language,
            response_length=payload.response_length,
            allow_web_search=payload.allow_web_search,
        )
    except QueryError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return _to_response(result)


def _conversation_context(payload: QueryRequest) -> ConversationContext | None:
    if (
        payload.recent_conversation_summary is None
        and not payload.recent_messages
        and payload.reference_context is None
    ):
        return None
    return ConversationContext(
        recent_conversation_summary=payload.recent_conversation_summary,
        recent_messages=tuple(message.to_domain() for message in payload.recent_messages),
        reference_context=payload.reference_context or {},
    )


def _to_response(result: QueryAnswer) -> QueryResponse:
    return QueryResponse(
        answer=result.answer.content,
        updated_conversation_summary=result.updated_conversation_summary,
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
                source_refs=[
                    SourceReferenceResponse(
                        source_document_id=ref.source_document_id,
                        source_block_id=ref.source_block_id,
                    )
                    for ref in snippet.source_refs
                ],
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
