from collections.abc import Callable

from app.core.llm_env import (
    api_key_from_env,
    chat_completions_endpoint,
    float_env,
    int_env,
    model_from_env,
    optional_int_env,
    provider_base_url,
    resolve_llm_provider,
)
from app.core.llm_prompt import with_schema_prompt
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
        schema_prompt_provider: Callable[
            [str, str | None, str | None],
            str,
        ]
        | None = None,
    ) -> None:
        self._client = client
        self._system_prompt = system_prompt
        self._schema_prompt_provider = schema_prompt_provider or (
            lambda feature, workspace_id, user_id: ""
        )

    def generate_answer(self, context: QueryContext) -> GeneratedAnswer:
        content = self._client.complete_text(
            with_schema_prompt(
                self._system_prompt,
                self._schema_prompt_provider(
                    "query",
                    context.workspace_id,
                    context.user_id,
                ),
            ),
            context.answer_context,
        ).strip()
        return GeneratedAnswer(content=content)


def build_query_chat_answer_generator(
    *,
    model: str | None = None,
) -> QueryChatAnswerGenerator:
    return QueryChatAnswerGenerator(
        ChatCompletionsJsonClient(_config_from_env(model=model)),
        schema_prompt_provider=get_active_schema_prompt,
    )


def _config_from_env(
    *,
    model: str | None = None,
) -> ChatClientConfig:
    api_key = _api_key()
    if not api_key:
        raise RuntimeError("Set QUERY_LLM_API_KEY or LLM_API_KEY before enabling query answer generation.")
    resolved_model = model or _model()
    if not resolved_model:
        raise RuntimeError("Set QUERY_LLM_MODEL or LLM_MODEL before enabling query answer generation.")
    return ChatClientConfig(
        endpoint=_endpoint(),
        api_key=api_key,
        model=resolved_model,
        temperature=_float_env("QUERY_LLM_TEMPERATURE", 0.2),
        timeout_seconds=_int_env("QUERY_LLM_TIMEOUT_SECONDS", 180),
        max_tokens=_optional_int_env("QUERY_LLM_MAX_TOKENS"),
        json_mode=False,
    )


def _endpoint() -> str:
    return chat_completions_endpoint(
        endpoint_env_names=("QUERY_LLM_ENDPOINT", "LLM_ENDPOINT"),
        base_url_env_names=("QUERY_LLM_BASE_URL", "LLM_BASE_URL"),
        default_base_url=provider_base_url(),
    )


def _api_key() -> str | None:
    return api_key_from_env(
        key_env_name="QUERY_LLM_API_KEY_ENV",
        key_env_names=("QUERY_LLM_API_KEY", "LLM_API_KEY"),
    )


def _model() -> str:
    default = "solar-pro2" if resolve_llm_provider() == "upstage" else ""
    return model_from_env(("QUERY_LLM_MODEL", "LLM_MODEL"), default)


def _float_env(name: str, default: float) -> float:
    return float_env(name, default)


def _int_env(name: str, default: int) -> int:
    return int_env(name, default)


def _optional_int_env(name: str) -> int | None:
    return optional_int_env(name)
