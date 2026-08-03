import json
import os
from pathlib import Path
from typing import Any

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
from app.modules.agent.application.ports import AgentTurnRouterPort
from app.modules.agent.domain.entities import AgentAction, AgentTurnRequest, AgentTurnRoute
from app.modules.agent.domain.exceptions import AgentTurnRouteContractError
from app.modules.wiki_generation.infrastructure.chat_completions_llm import ChatClientConfig, ChatCompletionsJsonClient
from app.modules.wiki_generation.infrastructure.json_output_parser import JsonParseError


DEFAULT_AGENT_TURN_ROUTER_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "agent_turn_router.system.md"
TEMPLATE_DEFERRED_MARKERS = (
    "template",
    "템플릿",
)
INSERT_AFTER_POSITION_MARKERS = ("아래에", "아래로", "뒤에", "뒤로", "after", "below")
INSERT_AFTER_ACTION_MARKERS = ("추가", "삽입", "붙여", "insert", "append", "add")
ALLOWED_ACTIONS = {"chat_answer", "markdown_edit", "markdown_create", "folder_organize", "clarify", "reject"}
JSON_OBJECT_CONTRACT_FAILURE = "model output must be a JSON object"


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
            "skill_mode": request.skill_mode,
            "available_skills": [
                {
                    "id": skill.id,
                    "version_id": skill.version_id,
                    "name": skill.name,
                    "description": skill.description,
                    "capabilities": list(skill.capabilities),
                }
                for skill in request.available_skills
            ],
        }
        route, failures = self._complete_route(payload)
        if not failures:
            return route

        retry_payload = {
            **payload,
            "contract_failures": failures,
            "retry_instruction": "Correct every contract failure and return the required route JSON object again.",
        }
        retried_route, retry_failures = self._complete_route(retry_payload)
        if retry_failures:
            raise AgentTurnRouteContractError(retry_failures)
        return retried_route

    def _complete_route(self, payload: dict[str, object]) -> tuple[AgentTurnRoute, list[str]]:
        try:
            raw = self._client.complete_json(
                self._system_prompt,
                json.dumps(payload, ensure_ascii=False, indent=2),
            )
        except JsonParseError:
            return _fallback_route(), [JSON_OBJECT_CONTRACT_FAILURE]
        return _normalize_route(raw)


def build_agent_turn_router() -> AgentTurnRouterPort:
    api_key = _api_key()
    if not api_key:
        raise RuntimeError("Set AGENT_ROUTER_LLM_API_KEY, QUERY_LLM_API_KEY, or LLM_API_KEY.")
    model = _model()
    if not model:
        raise RuntimeError("Set AGENT_ROUTER_LLM_MODEL, QUERY_LLM_MODEL, or LLM_MODEL.")
    prompt_path = Path(os.environ.get("AGENT_TURN_ROUTER_SYSTEM_PROMPT", str(DEFAULT_AGENT_TURN_ROUTER_PROMPT)))
    return ChatCompletionsTurnRouter(
        ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint=_endpoint(),
                api_key=api_key,
                model=model,
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
    has_template_skill = any("template" in skill.capabilities for skill in request.available_skills)
    if not has_template_skill and any(marker in lowered for marker in TEMPLATE_DEFERRED_MARKERS):
        return AgentTurnRoute(
            action="clarify",
            confidence=1.0,
            reason="template/full-document transform is deferred",
            edit_goal="template_transform",
        )
    requests_insert_after = any(marker in lowered for marker in INSERT_AFTER_POSITION_MARKERS) and any(
        marker in lowered for marker in INSERT_AFTER_ACTION_MARKERS
    )
    if requests_insert_after:
        target = request.active_markdown_context.target if request.active_markdown_context else None
        return AgentTurnRoute(
            action="markdown_edit" if target and target.type == "current_section" else "clarify",
            confidence=1.0,
            reason="insert_after request requires a current section target",
            edit_goal="insert_after",
        )
    return None


def _normalize_route(value: dict[str, Any]) -> tuple[AgentTurnRoute, list[str]]:
    failures: list[str] = []
    raw_action = value.get("action")
    if not isinstance(raw_action, str) or raw_action not in ALLOWED_ACTIONS:
        failures.append("action must be a supported value")
        action: AgentAction = "chat_answer"
    else:
        action = raw_action

    raw_confidence = value.get("confidence")
    if (
        not isinstance(raw_confidence, (int, float))
        or isinstance(raw_confidence, bool)
        or not 0.0 <= float(raw_confidence) <= 1.0
    ):
        failures.append("confidence must be a number between 0 and 1")
        confidence = 0.0
    else:
        confidence = float(raw_confidence)

    raw_reason = value.get("reason")
    if not isinstance(raw_reason, str) or not raw_reason.strip():
        failures.append("reason must be a non-empty string")
        reason = ""
    else:
        reason = raw_reason.strip()

    if "edit_goal" not in value:
        failures.append("edit_goal is required")
    raw_edit_goal = value.get("edit_goal")
    if raw_edit_goal is not None and not isinstance(raw_edit_goal, str):
        failures.append("edit_goal must be a string or null")
        edit_goal = None
    else:
        edit_goal = _optional_text(raw_edit_goal)

    raw_selected_skill_id = value.get("selected_skill_id")
    if raw_selected_skill_id is not None and not isinstance(raw_selected_skill_id, str):
        failures.append("selected_skill_id must be a string or null")
        selected_skill_id = None
    else:
        selected_skill_id = _optional_text(raw_selected_skill_id)

    raw_skill_candidates = value.get("skill_candidates", [])
    if not isinstance(raw_skill_candidates, list) or not all(
        isinstance(candidate, str) and candidate.strip() for candidate in raw_skill_candidates
    ):
        failures.append("skill_candidates must be an array of non-empty strings")
        skill_candidates: tuple[str, ...] = ()
    else:
        skill_candidates = tuple(candidate.strip() for candidate in raw_skill_candidates)

    return AgentTurnRoute(
        action=action,
        confidence=confidence,
        reason=reason,
        edit_goal=edit_goal,
        selected_skill_id=selected_skill_id,
        skill_candidates=skill_candidates,
    ), failures


def _fallback_route() -> AgentTurnRoute:
    return AgentTurnRoute(
        action="chat_answer",
        confidence=0.0,
        reason="",
    )


def _endpoint() -> str:
    return chat_completions_endpoint(
        endpoint_env_names=("AGENT_ROUTER_LLM_ENDPOINT", "QUERY_LLM_ENDPOINT", "LLM_ENDPOINT"),
        base_url_env_names=("AGENT_ROUTER_LLM_BASE_URL", "QUERY_LLM_BASE_URL", "LLM_BASE_URL"),
        default_base_url=provider_base_url(),
    )


def _api_key() -> str | None:
    return api_key_from_env(
        key_env_name="AGENT_ROUTER_LLM_API_KEY_ENV",
        key_env_names=("AGENT_ROUTER_LLM_API_KEY", "QUERY_LLM_API_KEY", "LLM_API_KEY"),
    )


def _model() -> str:
    default = "solar-pro2" if resolve_llm_provider() == "upstage" else ""
    return model_from_env(
        ("AGENT_ROUTER_LLM_MODEL", "QUERY_LLM_MODEL", "LLM_MODEL"),
        default,
    )


def _optional_text(value: object) -> str | None:
    text = str(value or "").strip()
    return text or None


def _float_env(name: str, default: float) -> float:
    return float_env(name, default)


def _int_env(name: str, default: int) -> int:
    return int_env(name, default)


def _optional_int_env(name: str) -> int | None:
    return optional_int_env(name)
