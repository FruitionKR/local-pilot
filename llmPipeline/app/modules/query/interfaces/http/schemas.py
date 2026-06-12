from pydantic import BaseModel, Field


class QueryRequest(BaseModel):
    question: str = Field(..., min_length=1)


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


class RetrievalSummaryResponse(BaseModel):
    source_candidate_count: int
    concept_candidate_count: int
    visited_node_count: int
    returned_node_count: int
    used_source_count: int
    used_concept_count: int
    max_depth: int
    stop_reason: str


class QueryResponse(BaseModel):
    answer: str
    related_pages: list[RelatedPageResponse]
    graph_context: GraphContextResponse
    traversal_paths: list[TraversalPathResponse]
    retrieval_summary: RetrievalSummaryResponse

