import json
import os
from pathlib import Path
from typing import Any

from app.core.llm_env import (
    api_key_from_env,
    int_env,
    optional_int_env,
    provider_api_key_env,
    resolve_llm_selection,
)
from app.modules.agent.application.ports import QuerySpecialistPort
from app.modules.agent.domain.entities import (
    AgentTurnRequest,
    QuerySpecialistDecision,
    RetrievalSource,
)
from app.modules.agent.domain.exceptions import AgentTurnRouteContractError
from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ChatClientConfig,
    ChatCompletionsJsonClient,
)
from app.modules.wiki_generation.infrastructure.json_output_parser import JsonParseError

DEFAULT_QUERY_SPECIALIST_PROMPT = (
    Path(__file__).resolve().parents[4] / "prompts" / "query_specialist.system.md"
)
CORE_ACTIONS = {
    "chat_answer",
    "conversation_reply",
    "markdown_edit",
    "markdown_create",
    "clarify",
}
RETRIEVAL_SOURCES = {"none", "workspace", "web"}
JSON_OBJECT_CONTRACT_FAILURE = "model output must be a JSON object"


class ChatCompletionsQuerySpecialist(QuerySpecialistPort):
    def __init__(self, client: ChatCompletionsJsonClient, system_prompt: str) -> None:
        self._client = client
        self._system_prompt = system_prompt

    def decide(
        self,
        request: AgentTurnRequest,
        *,
        retrieval_source: RetrievalSource,
    ) -> QuerySpecialistDecision:
        markdown_context = request.active_markdown_context
        payload: dict[str, object] = {
            "message": request.message,
            "router_retrieval_hint": retrieval_source,
            "allow_web_search": request.allow_web_search,
            "conversation_summary": (
                request.conversation_context.recent_conversation_summary
                if request.conversation_context
                else None
            ),
            "recent_messages": (
                [
                    {
                        "role": message.role,
                        "content": message.content,
                        "action": message.action,
                    }
                    for message in request.conversation_context.recent_messages
                ]
                if request.conversation_context
                else []
            ),
            "reference_context": (
                request.conversation_context.reference_context
                if request.conversation_context
                else {}
            ),
            "active_markdown_context": {
                "has_markdown": bool(markdown_context and markdown_context.markdown.strip()),
                "target": (
                    {
                        "type": markdown_context.target.type,
                        "start_line": markdown_context.target.start_line,
                        "end_line": markdown_context.target.end_line,
                    }
                    if markdown_context and markdown_context.target
                    else None
                ),
            },
        }
        decision, failures = self._complete(payload)
        failures.extend(_decision_failures(decision, request))
        if not failures:
            return decision

        retry_payload = {
            **payload,
            "contract_failures": failures,
            "retry_instruction": "Correct every contract failure and return the required JSON object again.",
        }
        retried, retry_failures = self._complete(retry_payload)
        retry_failures.extend(_decision_failures(retried, request))
        if retry_failures:
            raise AgentTurnRouteContractError(retry_failures)
        return retried

    def _complete(
        self,
        payload: dict[str, object],
    ) -> tuple[QuerySpecialistDecision, list[str]]:
        try:
            raw = self._client.complete_json(
                self._system_prompt,
                json.dumps(payload, ensure_ascii=False, indent=2),
            )
        except JsonParseError:
            return _fallback_decision(), [JSON_OBJECT_CONTRACT_FAILURE]
        return _normalize_decision(raw)


def build_query_specialist(
    *,
    provider: str | None = None,
    model: str | None = None,
) -> QuerySpecialistPort:
    resolved_provider, resolved_model = resolve_llm_selection(provider, model)
    api_key = api_key_from_env(provider=resolved_provider)
    if not api_key:
        raise RuntimeError(f"Set {provider_api_key_env(resolved_provider)}.")
    prompt_path = Path(
        os.environ.get(
            "QUERY_SPECIALIST_SYSTEM_PROMPT",
            str(DEFAULT_QUERY_SPECIALIST_PROMPT),
        )
    )
    return ChatCompletionsQuerySpecialist(
        ChatCompletionsJsonClient(
            ChatClientConfig(
                api_key=api_key,
                model=resolved_model,
                temperature=None,
                timeout_seconds=int_env("QUERY_SPECIALIST_LLM_TIMEOUT_SECONDS", 180),
                max_tokens=optional_int_env("QUERY_SPECIALIST_LLM_MAX_TOKENS"),
                json_mode=True,
                provider=resolved_provider,
            )
        ),
        prompt_path.read_text(encoding="utf-8"),
    )


def _normalize_decision(
    value: dict[str, Any],
) -> tuple[QuerySpecialistDecision, list[str]]:
    failures: list[str] = []
    action = value.get("action")
    if action not in CORE_ACTIONS:
        failures.append("action must be a supported core specialist action")
        action = "clarify"
    retrieval_source = value.get("retrieval_source")
    if retrieval_source not in RETRIEVAL_SOURCES:
        failures.append("retrieval_source must be none, workspace, or web")
        retrieval_source = "none"
    reason = value.get("reason")
    if not isinstance(reason, str) or not reason.strip():
        failures.append("reason must be a non-empty string")
        reason = "Query specialist contract failed"
    message = value.get("message")
    if message is not None and not isinstance(message, str):
        failures.append("message must be a string or null")
        message = None
    return (
        QuerySpecialistDecision(
            action=action,  # type: ignore[arg-type]
            retrieval_source=retrieval_source,  # type: ignore[arg-type]
            reason=reason.strip(),
            message=message.strip() if isinstance(message, str) else None,
        ),
        failures,
    )


def _decision_failures(
    decision: QuerySpecialistDecision,
    request: AgentTurnRequest,
) -> list[str]:
    failures: list[str] = []
    if decision.action == "chat_answer" and decision.retrieval_source == "none":
        failures.append("chat_answer requires workspace or web retrieval")
    if decision.action != "chat_answer" and decision.retrieval_source != "none":
        failures.append("non-query action requires retrieval_source none")
    if decision.retrieval_source == "web" and request.allow_web_search is not True:
        failures.append("web retrieval requires explicit allow_web_search")
    if decision.action == "clarify" and not decision.message:
        failures.append("clarify requires a non-empty message")
    return failures


def _fallback_decision() -> QuerySpecialistDecision:
    return QuerySpecialistDecision(
        action="clarify",
        retrieval_source="none",
        reason="Query specialist output contract failed",
    )
