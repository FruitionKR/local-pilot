import json
import os
from pathlib import Path
from typing import Any

from app.core.llm_env import api_key_from_env, chat_completions_endpoint, float_env, int_env, model_from_env, optional_int_env
from app.modules.agent.application.ports import AgentTurnRouterPort
from app.modules.agent.domain.entities import AgentAction, AgentTurnRequest, AgentTurnRoute
from app.modules.wiki_generation.infrastructure.chat_completions_llm import ChatClientConfig, ChatCompletionsJsonClient


DEFAULT_AGENT_TURN_ROUTER_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "agent_turn_router.system.md"
TEMPLATE_DEFERRED_MARKERS = (
    "template",
    "템플릿",
    "양식",
    "서식",
    "전체 문서",
    "문서 전체",
    "구조 그대로",
    "구조를 그대로",
    "원문 구조",
)


class ChatCompletionsTurnRouter(AgentTurnRouterPort):
    def __init__(self, client: ChatCompletionsJsonClient, system_prompt: str) -> None:
        self._client = client
        self._system_prompt = system_prompt

    def route(self, request: AgentTurnRequest) -> AgentTurnRoute:
        guarded = _local_guard(request)
        if guarded is not None:
            return guarded

        payload = {
            "message": request.message,
            "conversation_summary": (
                request.conversation_context.recent_conversation_summary
                if request.conversation_context
                else None
            ),
            "reference_context": (
                request.conversation_context.reference_context
                if request.conversation_context
                else {}
            ),
            "active_markdown_context": {
                "has_markdown": bool(request.active_markdown_context and request.active_markdown_context.markdown.strip()),
                "target": (
                    {
                        "type": request.active_markdown_context.target.type,
                        "start_line": request.active_markdown_context.target.start_line,
                        "end_line": request.active_markdown_context.target.end_line,
                    }
                    if request.active_markdown_context and request.active_markdown_context.target
                    else None
                ),
            },
        }
        raw = self._client.complete_json(self._system_prompt, json.dumps(payload, ensure_ascii=False, indent=2))
        return _normalize_route(raw)


def build_agent_turn_router() -> AgentTurnRouterPort:
    api_key = _api_key()
    if not api_key:
        raise RuntimeError("Set AGENT_ROUTER_LLM_API_KEY, QUERY_LLM_API_KEY, UPSTAGE_API_KEY, or LLM_API_KEY.")
    prompt_path = Path(os.environ.get("AGENT_TURN_ROUTER_SYSTEM_PROMPT", str(DEFAULT_AGENT_TURN_ROUTER_PROMPT)))
    return ChatCompletionsTurnRouter(
        ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint=_endpoint(),
                api_key=api_key,
                model=_model(),
                temperature=_float_env("AGENT_ROUTER_LLM_TEMPERATURE", 0.0),
                timeout_seconds=_int_env("AGENT_ROUTER_LLM_TIMEOUT_SECONDS", 180),
                max_tokens=_optional_int_env("AGENT_ROUTER_LLM_MAX_TOKENS"),
                json_mode=True,
            )
        ),
        system_prompt=prompt_path.read_text(encoding="utf-8"),
    )


def _local_guard(request: AgentTurnRequest) -> AgentTurnRoute | None:
    lowered = request.message.lower()
    if any(marker in lowered for marker in TEMPLATE_DEFERRED_MARKERS):
        return AgentTurnRoute(
            action="clarify",
            confidence=1.0,
            reason="template/full-document transform is deferred",
            edit_goal="template_transform",
        )
    return None


def _normalize_route(value: dict[str, Any]) -> AgentTurnRoute:
    action = str(value.get("action") or value.get("intent") or "chat_answer").strip()
    if action == "chat":
        action = "chat_answer"
    if action == "edit":
        action = "markdown_edit"
    if action == "create":
        action = "markdown_create"
    if action not in {"chat_answer", "markdown_edit", "markdown_create", "clarify", "reject"}:
        action = "chat_answer"
    return AgentTurnRoute(
        action=action,  # type: ignore[arg-type]
        confidence=_bounded_float(value.get("confidence"), 0.0),
        reason=str(value.get("reason") or ""),
        edit_goal=_optional_text(value.get("edit_goal")),
    )


def _endpoint() -> str:
    return chat_completions_endpoint(
        endpoint_env_names=("AGENT_ROUTER_LLM_ENDPOINT", "QUERY_LLM_ENDPOINT", "LLM_ENDPOINT"),
        base_url_env_names=("AGENT_ROUTER_LLM_BASE_URL", "QUERY_LLM_BASE_URL", "UPSTAGE_BASE_URL", "LLM_BASE_URL"),
        default_base_url="https://api.upstage.ai/v1",
    )


def _api_key() -> str | None:
    return api_key_from_env(
        key_env_name="AGENT_ROUTER_LLM_API_KEY_ENV",
        key_env_names=("AGENT_ROUTER_LLM_API_KEY", "QUERY_LLM_API_KEY", "UPSTAGE_API_KEY", "LLM_API_KEY"),
    )


def _model() -> str:
    return model_from_env(("AGENT_ROUTER_LLM_MODEL", "QUERY_LLM_MODEL", "UPSTAGE_MODEL", "LLM_MODEL"), "solar-pro2")


def _bounded_float(value: object, default: float) -> float:
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError):
        return default


def _optional_text(value: object) -> str | None:
    text = str(value or "").strip()
    return text or None


def _float_env(name: str, default: float) -> float:
    return float_env(name, default)


def _int_env(name: str, default: int) -> int:
    return int_env(name, default)


def _optional_int_env(name: str) -> int | None:
    return optional_int_env(name)
