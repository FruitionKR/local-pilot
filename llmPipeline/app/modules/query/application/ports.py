from typing import Protocol

from app.modules.query.domain.entities import EvidenceSnippet, GeneratedAnswer, QueryContext, QueryEvaluation, QueryRewrite, WebSearchResult, WikiEmbeddingUnit, WikiPage, WikiPageLink


class WikiRepositoryPort(Protocol):
    def list_candidate_pages(
        self,
        workspace_id: str,
        query: str,
        source_limit: int,
        concept_limit: int,
    ) -> list[WikiPage]:
        ...

    def list_links_for_page_ids(
        self,
        workspace_id: str,
        page_ids: list[str],
        limit: int,
    ) -> list[WikiPageLink]:
        ...

    def list_embedding_units_by_page_ids(self, page_ids: list[str]) -> dict[str, list[WikiEmbeddingUnit]]:
        ...


class WikiMarkdownReaderPort(Protocol):
    def read_markdown(self, markdown_uri: str) -> str:
        ...


class EmbeddingSearchPort(Protocol):
    def score(self, query: str, documents: list[str]) -> list[float]:
        ...


class TextSearchPort(Protocol):
    def score(self, query: str, documents: list[str]) -> list[float]:
        ...


class QueryRewritePort(Protocol):
    def rewrite(self, question: str) -> QueryRewrite:
        ...


class WebSearchPort(Protocol):
    def search(self, query: str) -> list[WebSearchResult]:
        ...


class AnswerGeneratorPort(Protocol):
    def generate_answer(self, context: QueryContext) -> GeneratedAnswer:
        ...


class QueryEvaluatorPort(Protocol):
    def evaluate(
        self,
        question: str,
        context: QueryContext,
        answer: GeneratedAnswer,
        stop_reason: str,
        web_search_available: bool = False,
    ) -> QueryEvaluation:
        ...


class QueryEvaluatorGraphPort(Protocol):
    def run(
        self,
        *,
        question: str,
        query_context: QueryContext,
        stop_reason: str,
        event_publisher: "QueryEventPublisherPort | None",
    ) -> tuple[GeneratedAnswer, list[EvidenceSnippet], QueryContext, QueryEvaluation | None]:
        ...


class QueryEventPublisherPort(Protocol):
    def publish(self, stage: str, message: str, data: dict[str, object] | None = None) -> None:
        ...
