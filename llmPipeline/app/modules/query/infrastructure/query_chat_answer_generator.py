import os
from collections.abc import Callable

from app.modules.query.application.ports import AnswerGeneratorPort
from app.modules.query.domain.entities import GeneratedAnswer, QueryContext
from app.modules.wiki_generation.infrastructure.chat_completions_llm import ChatClientConfig, ChatCompletionsJsonClient
from app.modules.wiki_schema.infrastructure.active_schema_prompt import get_active_schema_prompt


QUERY_ANSWER_SYSTEM_PROMPT = """You are a document-grounded question-answering assistant.

Use only the provided context as evidence.
Answer in Korean.
Write only the conversational answer body that should be shown to the user.
Mark the evidence used for each supported sentence with citation markers like [1] or [2].
Use only the evidence rank numbers provided in the context as citation markers.
Every sentence that contains factual content from evidence must end with at least one citation marker.
Do not write uncited factual sentences.
Do not expose evidence lists, scores, path ids, page ids, page URLs, or internal metadata in the answer body.
Do not expose internal link type names or implementation labels unless the user explicitly asks for technical details.
If the context includes a mode-specific answer policy, follow that policy over generic unsupported-answer guidance.
If the evidence directly answers the question, answer naturally from that evidence.
If the evidence does not contain a direct definition or explanation, say that the exact answer is not sufficiently supported.
For unsupported questions, do not explain the answer from general knowledge; mention only that the provided evidence does not support it and, if useful, name the closest related evidence topic.
Do not create examples, analogies, or fictional cases that are not present in the context.
If an example is needed, use only entities or cases that appear in the evidence.
Do not add information from outside the context.
"""


class QueryChatAnswerGenerator(AnswerGeneratorPort):
    def __init__(
        self,
        client: ChatCompletionsJsonClient,
        system_prompt: str = QUERY_ANSWER_SYSTEM_PROMPT,
        schema_prompt_provider: Callable[[str], str] | None = None,
    ) -> None:
        self._client = client
        self._system_prompt = system_prompt
        self._schema_prompt_provider = schema_prompt_provider or (lambda feature: "")

    def generate_answer(self, context: QueryContext) -> GeneratedAnswer:
        content = self._client.complete_text(
            _with_schema_prompt(self._system_prompt, self._schema_prompt_provider("query")),
            context.answer_context,
        ).strip()
        return GeneratedAnswer(content=content)


def build_query_chat_answer_generator() -> QueryChatAnswerGenerator:
    return QueryChatAnswerGenerator(
        ChatCompletionsJsonClient(_config_from_env()),
        schema_prompt_provider=lambda feature: get_active_schema_prompt(feature),  # type: ignore[arg-type]
    )


def _with_schema_prompt(system_prompt: str, schema_prompt: str) -> str:
    if not schema_prompt.strip():
        return system_prompt
    return f"{system_prompt.rstrip()}\n\n{schema_prompt.strip()}\n"


def _config_from_env() -> ChatClientConfig:
    api_key = _api_key()
    if not api_key:
        raise RuntimeError("Set QUERY_LLM_API_KEY, UPSTAGE_API_KEY, or LLM_API_KEY before enabling query answer generation.")
    return ChatClientConfig(
        endpoint=_endpoint(),
        api_key=api_key,
        model=_model(),
        temperature=_float_env("QUERY_LLM_TEMPERATURE", 0.2),
        timeout_seconds=_int_env("QUERY_LLM_TIMEOUT_SECONDS", 180),
        max_tokens=_optional_int_env("QUERY_LLM_MAX_TOKENS"),
        json_mode=False,
    )


def _endpoint() -> str:
    endpoint = os.environ.get("QUERY_LLM_ENDPOINT") or os.environ.get("LLM_ENDPOINT")
    if endpoint:
        return endpoint
    base_url = (
        os.environ.get("QUERY_LLM_BASE_URL")
        or os.environ.get("UPSTAGE_BASE_URL")
        or os.environ.get("LLM_BASE_URL")
        or "https://api.upstage.ai/v1"
    )
    return base_url.rstrip("/") + "/chat/completions"


def _api_key() -> str | None:
    key_env = os.environ.get("QUERY_LLM_API_KEY_ENV")
    if key_env and os.environ.get(key_env):
        return os.environ[key_env]
    return os.environ.get("QUERY_LLM_API_KEY") or os.environ.get("UPSTAGE_API_KEY") or os.environ.get("LLM_API_KEY")


def _model() -> str:
    return os.environ.get("QUERY_LLM_MODEL") or os.environ.get("UPSTAGE_MODEL") or os.environ.get("LLM_MODEL") or "solar-pro2"


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
