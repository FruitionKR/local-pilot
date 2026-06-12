from functools import lru_cache

from app.modules.query.application.answer_query import AnswerQueryUseCase
from app.modules.query.infrastructure.postgres_wiki_repository import PostgresWikiRepository
from app.modules.query.infrastructure.simple_text_searcher import SimpleTextSearcher
from app.modules.query.infrastructure.static_answer_generator import StaticAnswerGenerator
from app.modules.query.infrastructure.static_score_embedding_search import StaticScoreEmbeddingSearch


@lru_cache(maxsize=1)
def get_answer_query_use_case() -> AnswerQueryUseCase:
    return AnswerQueryUseCase(
        wiki_repository=PostgresWikiRepository(),
        embedding_search=StaticScoreEmbeddingSearch(),
        text_search=SimpleTextSearcher(),
        answer_generator=StaticAnswerGenerator(),
    )
