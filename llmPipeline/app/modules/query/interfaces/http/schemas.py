from pydantic import BaseModel, Field


class QueryRequest(BaseModel):
    question: str = Field(..., min_length=1)
    request_id: str | None = None
    log_callback_url: str | None = None


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


class EvidenceSnippetResponse(BaseModel):
    rank: int
    source_document_id: str
    source_block_ids: list[str]
    text: str


class QueryResponse(BaseModel):
    answer: str
    related_pages: list[RelatedPageResponse]
    evidence_snippets: list[EvidenceSnippetResponse]
    graph_context: GraphContextResponse
    traversal_paths: list[TraversalPathResponse]
