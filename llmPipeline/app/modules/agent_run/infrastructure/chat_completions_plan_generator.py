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
from app.modules.agent_run.domain.plan import AgentPlan, AgentPlanOperation, build_agent_plan
from app.modules.wiki_generation.infrastructure.chat_completions_llm import ChatClientConfig, ChatCompletionsJsonClient


DEFAULT_PLAN_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "agent_folder_plan.system.md"
ALLOWED_PLAN_TOOLS = {"create_folder", "rename_folder", "move_folder", "move_document", "rename_document"}


class ChatCompletionsPlanGenerator:
    def __init__(self, client: ChatCompletionsJsonClient, system_prompt: str) -> None:
        self._client = client
        self._system_prompt = system_prompt

    def generate(
        self,
        *,
        run_id: str,
        plan_id: str,
        version: int,
        instruction: str,
        hierarchy: list[dict[str, object]],
        skill_instructions: str | None,
    ) -> AgentPlan:
        value = self._client.complete_json(
            self._system_prompt,
            json.dumps(
                {
                    "plan_id": plan_id,
                    "instruction": instruction,
                    "hierarchy": hierarchy,
                    "skill_instructions": skill_instructions,
                },
                ensure_ascii=False,
                indent=2,
            ),
        )
        plan = normalize_plan_candidate(run_id, plan_id, version, value)
        _validate_plan_against_hierarchy(plan, hierarchy)
        return plan


def normalize_plan_candidate(
    run_id: str,
    plan_id: str,
    version: int,
    value: dict[str, Any],
) -> AgentPlan:
    summary = value.get("summary")
    raw_operations = value.get("operations")
    if not isinstance(summary, str) or not summary.strip():
        raise ValueError("Agent plan summary is required.")
    if not isinstance(raw_operations, list) or not 1 <= len(raw_operations) <= 20:
        raise ValueError("Agent plan must contain between 1 and 20 operations.")
    operations: list[AgentPlanOperation] = []
    for index, raw in enumerate(raw_operations, start=1):
        if not isinstance(raw, dict):
            raise ValueError("Agent plan operation must be an object.")
        tool_name = _required_text(raw, "tool_name")
        if tool_name not in ALLOWED_PLAN_TOOLS:
            raise ValueError("Agent plan contains an unsupported tool.")
        target_type = _required_text(raw, "target_type")
        if target_type not in {"folder", "document"}:
            raise ValueError("Agent plan target_type is invalid.")
        arguments = raw.get("arguments")
        if not isinstance(arguments, dict):
            raise ValueError("Agent plan arguments must be an object.")
        raw_dependencies = raw.get("depends_on", [])
        if not isinstance(raw_dependencies, list) or not all(isinstance(item, int) for item in raw_dependencies):
            raise ValueError("Agent plan dependencies must be operation sequence numbers.")
        operations.append(
            AgentPlanOperation(
                id=f"{plan_id}-op-{index}",
                sequence=index,
                tool_name=tool_name,  # type: ignore[arg-type]
                target_type=target_type,  # type: ignore[arg-type]
                target_id=_optional_text(raw.get("target_id")),
                base_version=_optional_int(raw.get("base_version")),
                source_parent_id=_optional_text(raw.get("source_parent_id")),
                destination_parent_id=_optional_text(raw.get("destination_parent_id")),
                arguments=arguments,
                reason=_required_text(raw, "reason"),
                depends_on=tuple(f"{plan_id}-op-{dependency}" for dependency in raw_dependencies),
            )
        )
    return build_agent_plan(plan_id, run_id, version, summary, tuple(operations))


def build_plan_generator() -> ChatCompletionsPlanGenerator:
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
    prompt_path = Path(os.environ.get("AGENT_PLAN_SYSTEM_PROMPT", str(DEFAULT_PLAN_PROMPT)))
    return ChatCompletionsPlanGenerator(
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


def _required_text(value: dict[str, Any], key: str) -> str:
    text = _optional_text(value.get(key))
    if text is None:
        raise ValueError(f"Agent plan {key} is required.")
    return text


def _optional_text(value: object) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _optional_int(value: object) -> int | None:
    if value is None:
        return None
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValueError("Agent plan base_version must be an integer or null.")
    return value


def _validate_plan_against_hierarchy(
    plan: AgentPlan,
    hierarchy: list[dict[str, object]],
) -> None:
    items = {str(item.get("id")): item for item in hierarchy if item.get("id") is not None}
    operation_ids = {operation.id for operation in plan.operations}
    required_arguments = {
        "create_folder": {"name", "parent_folder_id"},
        "rename_folder": {"folder_id", "name", "base_version"},
        "move_folder": {"folder_id", "parent_folder_id", "position", "base_version"},
        "move_document": {"document_id", "folder_id", "position", "base_version"},
        "rename_document": {"document_id", "display_name", "base_version"},
    }
    for operation in plan.operations:
        if set(operation.arguments) != required_arguments[operation.tool_name]:
            raise ValueError("Agent plan arguments do not match the tool contract.")
        if operation.tool_name == "create_folder":
            if operation.target_id is not None or operation.base_version is not None:
                raise ValueError("create_folder cannot have an existing target or base_version.")
        else:
            item = items.get(operation.target_id or "")
            if item is None or item.get("type") != operation.target_type:
                raise ValueError("Agent plan target must exist in the hierarchy snapshot.")
            if item.get("current_version") != operation.base_version:
                raise ValueError("Agent plan base_version must match the hierarchy snapshot.")
            id_key = "folder_id" if operation.target_type == "folder" else "document_id"
            if operation.arguments.get(id_key) != operation.target_id:
                raise ValueError("Agent plan target id must match the tool arguments.")
            if operation.arguments.get("base_version") != operation.base_version:
                raise ValueError("Agent plan base_version must match the tool arguments.")
        destination_key = {
            "create_folder": "parent_folder_id",
            "move_folder": "parent_folder_id",
            "move_document": "folder_id",
        }.get(operation.tool_name)
        if destination_key:
            destination = operation.arguments.get(destination_key)
            if not isinstance(destination, dict) and destination != operation.destination_parent_id:
                raise ValueError("Agent plan destination must match the tool arguments.")
            if isinstance(destination, str):
                target_folder = items.get(destination)
                if target_folder is None or target_folder.get("type") != "folder":
                    raise ValueError("Agent plan destination folder must exist.")
        references = _operation_references(operation.arguments)
        if not references.issubset(set(operation.depends_on)) or not references.issubset(operation_ids):
            raise ValueError("Agent plan result references must be declared dependencies.")


def _operation_references(value: object) -> set[str]:
    if isinstance(value, dict):
        if set(value) == {"$operation_result", "field"} and isinstance(value["$operation_result"], str):
            return {value["$operation_result"]}
        return set().union(*(_operation_references(item) for item in value.values()), set())
    if isinstance(value, list):
        return set().union(*(_operation_references(item) for item in value), set())
    return set()
