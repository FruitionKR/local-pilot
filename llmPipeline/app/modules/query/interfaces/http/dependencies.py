import os
from functools import lru_cache

from app.modules.query.application.answer_query import AnswerQueryUseCase
from app.modules.query.infrastructure.bm25_searcher import Bm25Searcher
from app.modules.query.infrastructure.minio_wiki_markdown_reader import MinioWikiMarkdownReader
from app.modules.query.infrastructure.postgres_wiki_repository import PostgresWikiRepository
from app.modules.query.infrastructure.query_chat_answer_generator import build_query_chat_answer_generator
from app.modules.query.infrastructure.query_event_publisher import build_query_event_publisher
from app.modules.query.infrastructure.stored_wiki_page_embedding_search import StoredWikiPageEmbeddingSearch


@lru_cache(maxsize=1)
def get_answer_query_use_case() -> AnswerQueryUseCase:
    text_search = Bm25Searcher()
    return AnswerQueryUseCase(
        wiki_repository=PostgresWikiRepository(),
        markdown_reader=MinioWikiMarkdownReader(),
        event_publisher=build_query_event_publisher(),
        embedding_search=_build_embedding_search(text_search),
        text_search=text_search,
        answer_generator=build_query_chat_answer_generator(),
    )


def _build_embedding_search(text_search: Bm25Searcher):
    mode = os.environ.get("QUERY_EMBEDDING_MODE", "bge-m3").strip().lower()
    if mode in {"text-only", "bm25", "lexical"}:
        return text_search
    return StoredWikiPageEmbeddingSearch()
