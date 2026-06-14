from dataclasses import dataclass, field


@dataclass(frozen=True)
class WikiPage:
    id: str
    page_type: str
    title: str
    slug: str
    summary: str
    markdown_uri: str | None = None
    markdown: str | None = None

    @property
    def is_source(self) -> bool:
        return self.page_type == "source"

    @property
    def is_concept(self) -> bool:
        return self.page_type == "concept"


@dataclass(frozen=True)
class WikiPageLink:
    from_page_id: str
    to_page_id: str
    link_type: str
    label: str | None = None
    confidence: float = 1.0


@dataclass(frozen=True)
class RetrievedPage:
    page: WikiPage
    score: float
    role: str
    depth: int = 0


@dataclass(frozen=True)
class TraversalEdge:
    from_page_id: str
    to_page_id: str
    link_type: str
    role: str
    score: float


@dataclass(frozen=True)
class TraversalPath:
    path_id: str
    role: str
    nodes: list[str]
    edges: list[TraversalEdge]
    score: float
    used_for_answer: bool = True
    stop_reason: str = "answer_context_selected"


@dataclass(frozen=True)
class GraphContext:
    nodes: list[RetrievedPage] = field(default_factory=list)
    edges: list[TraversalEdge] = field(default_factory=list)


@dataclass(frozen=True)
class RetrievalSummary:
    source_candidate_count: int
    concept_candidate_count: int
    visited_node_count: int
    returned_node_count: int
    used_source_count: int
    used_concept_count: int
    max_depth: int
    stop_reason: str


@dataclass(frozen=True)
class EvidenceSnippet:
    page_id: str
    page_type: str
    page_title: str
    page_slug: str
    page_url: str
    page_role: str
    text: str
    score: float
    rank: int
    paragraph_index: int | None = None
    sentence_index: int | None = None


@dataclass(frozen=True)
class QueryContext:
    question: str
    graph_context: GraphContext
    traversal_paths: list[TraversalPath]
    related_pages: list[RetrievedPage]
    evidence_snippets: list[EvidenceSnippet]
    answer_context: str


@dataclass(frozen=True)
class GeneratedAnswer:
    content: str


@dataclass(frozen=True)
class QueryAnswer:
    answer: GeneratedAnswer
    related_pages: list[RetrievedPage]
    evidence_snippets: list[EvidenceSnippet]
    graph_context: GraphContext
    traversal_paths: list[TraversalPath]
    retrieval_summary: RetrievalSummary

