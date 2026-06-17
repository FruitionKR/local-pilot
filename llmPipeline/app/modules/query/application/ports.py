from typing import Protocol

from app.modules.query.domain.entities import GeneratedAnswer, QueryContext, QueryRewrite, WebSearchResult, WikiPage, WikiPageLink


class WikiRepositoryPort(Protocol):
    def list_active_pages(self) -> list[WikiPage]:
        ...

    def list_active_links(self) -> list[WikiPageLink]:
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


class QueryEventPublisherPort(Protocol):
    def publish(self, stage: str, message: str, data: dict[str, object] | None = None) -> None:
        ...

