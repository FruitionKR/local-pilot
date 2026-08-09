from typing import Any, Literal

from pydantic import BaseModel, Field

from app.modules.query.domain.entities import ConversationMessage


class ConversationMessageRequest(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(..., min_length=1, max_length=4000)

    def to_domain(self) -> ConversationMessage:
        return ConversationMessage(role=self.role, content=self.content)


class QueryRequest(BaseModel):
    workspace_id: str = Field(..., min_length=1)
    user_id: str | None = Field(default=None, min_length=1)
    question: str = Field(..., min_length=1)
    request_id: str | None = None
    log_callback_url: str | None = None
    recent_conversation_summary: str | None = None
    recent_messages: list[ConversationMessageRequest] = Field(default_factory=list, max_length=6)
    reference_context: dict[str, Any] | None = None
    output_language: Literal["ko", "en", "document"] | None = None
    response_length: Literal["concise", "balanced", "detailed"] | None = None
    allow_web_search: bool | None = None


class RelatedPageResponse(BaseModel):
    id: str
    page_type: str
    title: str
    slug: str
    relevance_score: float
    role: str
    depth: int


class TraversalEdgeResponse(BaseModel):
    from_page_id: str
    to_page_id: str
    link_type: str
    role: str
    score: float


class GraphContextResponse(BaseModel):
    nodes: list[RelatedPageResponse]
    edges: list[TraversalEdgeResponse]


class TraversalPathResponse(BaseModel):
    path_id: str
    role: str
    used_for_answer: bool
    score: float
    stop_reason: str
    nodes: list[str]
    edges: list[TraversalEdgeResponse]


class SourceReferenceResponse(BaseModel):
    source_document_id: str
    source_block_id: str


class EvidenceSnippetResponse(BaseModel):
    rank: int
    source_document_id: str
    source_block_ids: list[str]
    source_refs: list[SourceReferenceResponse]
    text: str


class QueryResponse(BaseModel):
    answer: str
    updated_conversation_summary: str | None = None
    related_pages: list[RelatedPageResponse]
    evidence_snippets: list[EvidenceSnippetResponse]
    graph_context: GraphContextResponse
    traversal_paths: list[TraversalPathResponse]
