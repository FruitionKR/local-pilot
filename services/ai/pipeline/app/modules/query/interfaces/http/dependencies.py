import os
from functools import lru_cache

from app.modules.query.application.answer_query import AnswerQueryUseCase
from app.modules.query.application.query_answer_assembler import QueryAnswerAssembler
from app.modules.query.infrastructure.bm25_searcher import Bm25Searcher
from app.modules.query.infrastructure.minio_wiki_markdown_reader import MinioWikiMarkdownReader
from app.modules.query.infrastructure.postgres_wiki_repository import PostgresWikiRepository
from app.modules.query.infrastructure.query_chat_answer_generator import (
    build_query_chat_answer_generator,
    build_query_conversation_summarizer,
)
from app.modules.query.infrastructure.query_answer_evaluator import build_query_answer_evaluator
from app.modules.query.infrastructure.query_evaluator_graph import LangGraphQueryEvaluatorGraph
from app.modules.query.infrastructure.query_event_publisher import NoOpQueryEventPublisher
from app.modules.query.infrastructure.rule_based_query_rewriter import RuleBasedQueryRewriter
from app.modules.query.infrastructure.stored_wiki_page_embedding_search import StoredWikiPageEmbeddingSearch
from app.modules.query.infrastructure.web_search import build_web_search
from app.modules.query.interfaces.http.schemas import QueryRequest


@lru_cache(maxsize=1)
def get_conversation_summarizer():
    return build_query_conversation_summarizer()


def build_answer_query_use_case(
    *,
    model: str | None = None,
    allow_web_search: bool = False,
) -> AnswerQueryUseCase:
    text_search = Bm25Searcher()
    answer_generator = build_query_chat_answer_generator(model=model)
    query_answer_assembler = QueryAnswerAssembler(answer_generator)
    query_evaluator = build_query_answer_evaluator(model=model)
    conversation_summarizer = build_query_conversation_summarizer(model=model)
    web_search = build_web_search(allow_web_search)
    query_evaluator_max_attempts = _int_env("QUERY_EVALUATOR_MAX_ATTEMPTS", 2)
    return AnswerQueryUseCase(
        wiki_repository=PostgresWikiRepository(),
        markdown_reader=MinioWikiMarkdownReader(),
        event_publisher=NoOpQueryEventPublisher(),
        embedding_search=_build_embedding_search(text_search),
        text_search=text_search,
        answer_generator=answer_generator,
        query_rewriter=RuleBasedQueryRewriter(),
        query_evaluator=query_evaluator,
        web_search=web_search,
        query_answer_assembler=query_answer_assembler,
        query_evaluator_graph=LangGraphQueryEvaluatorGraph(
            query_answer_assembler=query_answer_assembler,
            query_evaluator=query_evaluator,
            web_search_available=web_search is not None,
            max_attempts=query_evaluator_max_attempts,
        ),
        min_internal_relevance_score=_float_env("QUERY_MIN_INTERNAL_RELEVANCE_SCORE", 0.0),
        query_evaluator_max_attempts=query_evaluator_max_attempts,
        conversation_summarizer=conversation_summarizer,
    )


def get_query_answer_use_case(payload: QueryRequest) -> AnswerQueryUseCase:
    return build_answer_query_use_case(
        model=payload.model,
        allow_web_search=payload.allow_web_search,
    )


@lru_cache(maxsize=1)
def get_answer_query_use_case() -> AnswerQueryUseCase:
    return build_answer_query_use_case()


def _build_embedding_search(text_search: Bm25Searcher):
    mode = os.environ.get("QUERY_EMBEDDING_MODE", "bge-m3").strip().lower()
    if mode in {"text-only", "bm25", "lexical"}:
        return text_search
    return StoredWikiPageEmbeddingSearch()


def _float_env(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default
