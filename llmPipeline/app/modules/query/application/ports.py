from typing import Protocol

from app.modules.query.domain.entities import GeneratedAnswer, QueryContext, WikiPage, WikiPageLink


class WikiRepositoryPort(Protocol):
    def list_active_pages(self) -> list[WikiPage]:
        ...

    def list_active_links(self) -> list[WikiPageLink]:
        ...


class EmbeddingSearchPort(Protocol):
    def score(self, query: str, documents: list[str]) -> list[float]:
        ...


class TextSearchPort(Protocol):
    def score(self, query: str, documents: list[str]) -> list[float]:
        ...


class AnswerGeneratorPort(Protocol):
    def generate_answer(self, context: QueryContext) -> GeneratedAnswer:
        ...

