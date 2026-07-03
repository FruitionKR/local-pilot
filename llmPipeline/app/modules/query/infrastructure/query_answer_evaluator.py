import json
import os
from pathlib import Path
from typing import Any

from app.modules.query.application.ports import QueryEvaluatorPort
from app.modules.query.domain.entities import GeneratedAnswer, QueryContext, QueryEvaluation
from app.modules.wiki_generation.infrastructure.chat_completions_llm import ChatClientConfig, ChatCompletionsJsonClient


DEFAULT_QUERY_EVALUATOR_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "query_answer_evaluator.system.md"
ALLOWED_ROUTES = {"internal_supported", "web_fallback", "internal_web_augmented", "unsupported"}


class QueryAnswerEvaluator(QueryEvaluatorPort):
    def __init__(self, client: ChatCompletionsJsonClient, system_prompt: str) -> None:
        self._client = client
        self._system_prompt = system_prompt

    def evaluate(
        self,
        question: str,
        context: QueryContext,
        answer: GeneratedAnswer,
        stop_reason: str,
        web_search_available: bool = False,
    ) -> QueryEvaluation:
        payload = {
            "question": question,
            "resolved_retrieval_question": context.question,
            "answer": answer.content,
            "stop_reason": stop_reason,
            "web_search_available": web_search_available,
            "related_pages": [
                {
                    "id": item.page.id,
                    "page_type": item.page.page_type,
                    "title": item.page.title,
                    "role": item.role,
                    "score": item.score,
                    "summary": item.page.summary,
                }
                for item in context.related_pages[:8]
            ],
            "evidence_snippets": [
                {
                    "rank": snippet.rank,
                    "source_document_id": snippet.source_document_id,
                    "source_block_ids": snippet.source_block_ids,
                    "source_refs": [
                        {
                            "source_document_id": ref.source_document_id,
                            "source_block_id": ref.source_block_id,
                        }
                        for ref in snippet.source_refs
                    ],
                    "text": snippet.text,
                }
                for snippet in context.evidence_snippets[:8]
            ],
        }
        raw = self._client.complete_json(self._system_prompt, json.dumps(payload, ensure_ascii=False, indent=2))
        return _normalize_evaluation(raw)


def build_query_answer_evaluator() -> QueryEvaluatorPort | None:
    mode = os.environ.get("QUERY_EVALUATOR_MODE", "disabled").strip().lower()
    if mode in {"", "disabled", "off", "none"}:
        return None
    if mode != "llm":
        return None
    api_key = _api_key()
    if not api_key:
        return None
    prompt_path = Path(os.environ.get("QUERY_EVALUATOR_PROMPT", str(DEFAULT_QUERY_EVALUATOR_PROMPT)))
    system_prompt = prompt_path.read_text(encoding="utf-8")
    return QueryAnswerEvaluator(
        ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint=_endpoint(),
                api_key=api_key,
                model=_model(),
                temperature=_float_env("QUERY_EVALUATOR_TEMPERATURE", 0.0),
                timeout_seconds=_int_env("QUERY_EVALUATOR_TIMEOUT_SECONDS", 180),
                max_tokens=_optional_int_env("QUERY_EVALUATOR_MAX_TOKENS"),
                json_mode=True,
            )
        ),
        system_prompt=system_prompt,
    )


def _normalize_evaluation(value: dict[str, Any]) -> QueryEvaluation:
    route = str(value.get("route") or "internal_supported").strip()
    if route not in ALLOWED_ROUTES:
        route = "internal_supported"
    return QueryEvaluation(
        route=route,
        evidence_relevance=_bounded_float(value.get("evidence_relevance"), 0.0),
        citation_evidence_alignment=_optional_bounded_float(value.get("citation_evidence_alignment")),
        unsupported_refusal_accuracy=_optional_bounded_float(value.get("unsupported_refusal_accuracy")),
        reason=str(value.get("reason") or ""),
        feedback=str(value.get("feedback") or ""),
        web_query=_optional_text(value.get("web_query")),
    )


def _endpoint() -> str:
    endpoint = os.environ.get("QUERY_EVALUATOR_ENDPOINT") or os.environ.get("QUERY_LLM_ENDPOINT") or os.environ.get("LLM_ENDPOINT")
    if endpoint:
        return endpoint
    base_url = (
        os.environ.get("QUERY_EVALUATOR_BASE_URL")
        or os.environ.get("QUERY_LLM_BASE_URL")
        or os.environ.get("UPSTAGE_BASE_URL")
        or os.environ.get("LLM_BASE_URL")
        or "https://api.upstage.ai/v1"
    )
    return base_url.rstrip("/") + "/chat/completions"


def _api_key() -> str | None:
    key_env = os.environ.get("QUERY_EVALUATOR_API_KEY_ENV")
    if key_env and os.environ.get(key_env):
        return os.environ[key_env]
    return (
        os.environ.get("QUERY_EVALUATOR_API_KEY")
        or os.environ.get("QUERY_LLM_API_KEY")
        or os.environ.get("UPSTAGE_API_KEY")
        or os.environ.get("LLM_API_KEY")
    )


def _model() -> str:
    return (
        os.environ.get("QUERY_EVALUATOR_MODEL")
        or os.environ.get("QUERY_LLM_MODEL")
        or os.environ.get("UPSTAGE_MODEL")
        or os.environ.get("LLM_MODEL")
        or "solar-pro2"
    )


def _bounded_float(value: object, default: float) -> float:
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError):
        return default


def _optional_bounded_float(value: object) -> float | None:
    if value is None:
        return None
    return _bounded_float(value, 0.0)


def _optional_text(value: object) -> str | None:
    text = str(value or "").strip()
    return text or None


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


def _optional_int_env(name: str) -> int | None:
    raw = os.environ.get(name)
    if not raw:
        return None
    try:
        return int(raw)
    except ValueError:
        return None
