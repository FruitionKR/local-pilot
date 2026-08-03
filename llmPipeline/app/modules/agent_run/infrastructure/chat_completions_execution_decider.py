import json
import os
from pathlib import Path
from typing import Any

from app.core.llm_env import (
    api_key_from_env,
    chat_completions_endpoint,
    int_env,
    model_from_env,
    provider_base_url,
    resolve_llm_provider,
)
from app.modules.agent_run.domain.execution import AgentExecutionDecision
from app.modules.agent_run.domain.plan import AgentPlan, AgentPlanOperation
from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ChatClientConfig,
    ChatCompletionsJsonClient,
)


DEFAULT_EXECUTION_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "agent_execution.system.md"
REPLAN_REASON_CODES = {
    "state_changed",
    "insufficient_information",
    "plan_no_longer_safe",
    "goal_not_achievable",
}


class ChatCompletionsExecutionDecider:
    def __init__(self, client: ChatCompletionsJsonClient, system_prompt: str) -> None:
        self._client = client
        self._system_prompt = system_prompt

    def decide(
        self,
        *,
        instruction: str,
        plan: AgentPlan,
        ready_operations: tuple[AgentPlanOperation, ...],
        observations: tuple[dict[str, object], ...],
        allowed_read_tools: tuple[str, ...],
    ) -> AgentExecutionDecision:
        value = self._client.complete_json(
            self._system_prompt,
            json.dumps(
                {
                    "instruction": instruction,
                    "plan": {
                        "id": plan.id,
                        "version": plan.version,
                        "summary": plan.summary,
                        "operation_hash": plan.operation_hash,
                        "operations": [_operation_payload(operation) for operation in plan.operations],
                    },
                    "ready_operations": [_operation_payload(operation) for operation in ready_operations],
                    "observations": list(observations[-10:]),
                    "allowed_read_tools": list(allowed_read_tools),
                },
                ensure_ascii=False,
                indent=2,
            ),
        )
        return normalize_execution_decision(value)


def normalize_execution_decision(value: dict[str, Any]) -> AgentExecutionDecision:
    action = value.get("action")
    if action not in {"read", "execute_operation", "request_replan"}:
        raise ValueError("Agent execution action is invalid.")
    operation_id = _optional_text(value.get("operation_id"))
    tool_name = _optional_text(value.get("tool_name"))
    arguments = value.get("arguments")
    reason = _optional_text(value.get("reason"))
    if action == "read":
        if tool_name is None or not isinstance(arguments, dict):
            raise ValueError("Agent read decision requires a tool and arguments.")
    elif action == "execute_operation":
        if operation_id is None:
            raise ValueError("Agent execution decision requires an operation id.")
        tool_name = None
        arguments = None
    elif action == "request_replan":
        if reason not in REPLAN_REASON_CODES:
            raise ValueError("Agent replan decision requires a supported reason code.")
        operation_id = None
        tool_name = None
        arguments = None
    return AgentExecutionDecision(
        action=action,
        operation_id=operation_id,
        tool_name=tool_name,
        arguments=arguments,
        reason=reason,
    )


def build_execution_decider() -> ChatCompletionsExecutionDecider:
    api_key = api_key_from_env(
        key_env_name="AGENT_PLAN_LLM_API_KEY_ENV",
        key_env_names=("AGENT_PLAN_LLM_API_KEY", "LLM_API_KEY"),
    )
    model = model_from_env(
        ("AGENT_PLAN_LLM_MODEL", "LLM_MODEL"),
        "solar-pro2" if resolve_llm_provider() == "upstage" else "",
    )
    if not api_key or not model:
        raise RuntimeError("Set AGENT_PLAN_LLM_API_KEY or LLM_API_KEY and a model.")
    endpoint = chat_completions_endpoint(
        endpoint_env_names=("AGENT_PLAN_LLM_ENDPOINT", "LLM_ENDPOINT"),
        base_url_env_names=("AGENT_PLAN_LLM_BASE_URL", "LLM_BASE_URL"),
        default_base_url=provider_base_url(),
    )
    prompt_path = Path(os.environ.get("AGENT_EXECUTION_SYSTEM_PROMPT", str(DEFAULT_EXECUTION_PROMPT)))
    return ChatCompletionsExecutionDecider(
        ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint=endpoint,
                api_key=api_key,
                model=model,
                temperature=0.0,
                timeout_seconds=int_env("AGENT_PLAN_LLM_TIMEOUT_SECONDS", 180),
                max_tokens=None,
                json_mode=True,
            )
        ),
        prompt_path.read_text(encoding="utf-8"),
    )


def _operation_payload(operation: AgentPlanOperation) -> dict[str, object]:
    return {
        "id": operation.id,
        "sequence": operation.sequence,
        "tool_name": operation.tool_name,
        "target_type": operation.target_type,
        "target_id": operation.target_id,
        "arguments": operation.arguments,
        "reason": operation.reason,
        "depends_on": list(operation.depends_on),
        "status": operation.status,
        "error_code": operation.error_code,
    }


def _optional_text(value: object) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
