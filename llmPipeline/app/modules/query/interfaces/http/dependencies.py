from functools import lru_cache

from app.modules.query.application.answer_query import AnswerQueryUseCase
from app.modules.query.infrastructure.bge_m3_embedding_search import BgeM3EmbeddingSearch
from app.modules.query.infrastructure.bm25_searcher import Bm25Searcher
from app.modules.query.infrastructure.minio_wiki_markdown_reader import MinioWikiMarkdownReader
from app.modules.query.infrastructure.postgres_wiki_repository import PostgresWikiRepository
from app.modules.query.infrastructure.query_event_publisher import build_query_event_publisher
from app.modules.query.infrastructure.static_answer_generator import StaticAnswerGenerator


@lru_cache(maxsize=1)
def get_answer_query_use_case() -> AnswerQueryUseCase:
    return AnswerQueryUseCase(
        wiki_repository=PostgresWikiRepository(),
        markdown_reader=MinioWikiMarkdownReader(),
        event_publisher=build_query_event_publisher(),
        embedding_search=BgeM3EmbeddingSearch(),
        text_search=Bm25Searcher(),
        answer_generator=StaticAnswerGenerator(),
    )
