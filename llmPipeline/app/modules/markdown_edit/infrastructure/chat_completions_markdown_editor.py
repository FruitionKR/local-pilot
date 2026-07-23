import json
import os
from collections.abc import Callable
from dataclasses import replace
from pathlib import Path
from typing import Any

from app.core.llm_env import api_key_from_env, chat_completions_endpoint, float_env, int_env, model_from_env, optional_int_env
from app.core.llm_prompt import with_schema_prompt
from app.modules.markdown_edit.application.ports import MarkdownEditorPort
from app.modules.markdown_edit.domain.entities import (
    EditOperationType,
    GeneratedMarkdownDocument,
    MarkdownCreateRequest,
    MarkdownCreateResult,
    MarkdownEditOperation,
    MarkdownEditRequest,
    MarkdownEditResult,
    MarkdownEditTarget,
    operation_for_edit_goal,
)
from app.modules.markdown_edit.domain.markdown_output_contract import (
    MarkdownCreateOutputContractError,
    MarkdownOutputContractError,
    ProtectedMarkdown,
    protect_markdown,
    repair_markdown_output,
    validate_markdown_create_output,
    validate_markdown_output,
)
from app.modules.markdown_edit.domain.markdown_target_scope import MarkdownTargetScope, build_markdown_target_scope
from app.modules.markdown_edit.infrastructure.markdown_source_range import (
    MarkdownSourceRangePlan,
    apply_source_range_response,
    build_source_range_plan,
    source_range_payload,
    validate_markdown_target_boundary,
)
from app.modules.markdown_edit.infrastructure.markdown_syntax_validation import validate_markdown_syntax
from app.modules.wiki_generation.infrastructure.chat_completions_llm import ChatClientConfig, ChatCompletionsJsonClient


DEFAULT_MARKDOWN_EDIT_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "markdown_edit.system.md"
DEFAULT_MARKDOWN_CREATE_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "markdown_create.system.md"
DEFAULT_MARKDOWN_SOURCE_EDIT_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "markdown_source_edit.system.md"


class ChatCompletionsMarkdownEditor(MarkdownEditorPort):
    def __init__(
        self,
        client: ChatCompletionsJsonClient,
        system_prompt: str,
        create_system_prompt: str | None = None,
        source_edit_system_prompt: str | None = None,
        context_lines: int = 20,
        schema_prompt_provider: Callable[[str], str] | None = None,
    ) -> None:
        self._client = client
        self._system_prompt = system_prompt
        self._create_system_prompt = create_system_prompt or system_prompt
        self._source_edit_system_prompt = source_edit_system_prompt or DEFAULT_MARKDOWN_SOURCE_EDIT_PROMPT.read_text(
            encoding="utf-8"
        )
        self._context_lines = context_lines
        self._schema_prompt_provider = schema_prompt_provider or (lambda feature: "")

    def generate_edit(self, request: MarkdownEditRequest) -> MarkdownEditResult:
        validate_markdown_target_boundary(request.markdown, request.target)
        scope = build_markdown_target_scope(request.markdown, request.target, self._context_lines)
        scoped_request = replace(request, markdown=scope.markdown)
        requested_operation = operation_for_edit_goal(request.edit_goal)
        source_range_plan = build_source_range_plan(scoped_request)
        if source_range_plan is not None:
            return self._generate_source_range_edit(scoped_request, source_range_plan, scope)

        protected = protect_markdown(scoped_request)
        payload = {
            "instruction": scoped_request.instruction,
            "edit_goal": scoped_request.edit_goal,
            "requested_operation": requested_operation,
            "conversation_summary": scoped_request.conversation_summary,
            "target": {
                "type": scoped_request.target.type,
                "start_line": scoped_request.target.start_line,
                "end_line": scoped_request.target.end_line,
            },
            "markdown": protected.markdown,
            **_read_only_context_payload(scope),
        }
        system_prompt = with_schema_prompt(self._system_prompt, self._schema_prompt_provider("edit"))
        result = self._complete_edit(system_prompt, payload, scoped_request, protected)
        if not result[1]:
            return result[0]

        retry_payload = {
            **payload,
            "previous_replacement_markdown": result[2],
            "contract_failures": result[1],
            "retry_instruction": "Correct every contract failure and return the required JSON object again.",
        }
        retried = self._complete_edit(system_prompt, retry_payload, scoped_request, protected)
        if retried[1]:
            raise MarkdownOutputContractError(retried[1], retried[0].edit.replacement_markdown)
        return retried[0]

    def _generate_source_range_edit(
        self,
        request: MarkdownEditRequest,
        plan: MarkdownSourceRangePlan,
        scope: MarkdownTargetScope,
    ) -> MarkdownEditResult:
        payload = {
            "instruction": request.instruction,
            "edit_goal": request.edit_goal,
            "conversation_summary": request.conversation_summary,
            "target": {
                "type": request.target.type,
                "start_line": request.target.start_line,
                "end_line": request.target.end_line,
            },
            **source_range_payload(plan),
            **_read_only_context_payload(scope),
        }
        system_prompt = with_schema_prompt(self._source_edit_system_prompt, self._schema_prompt_provider("edit"))
        result, failures, raw = self._complete_source_range_edit(system_prompt, payload, request, plan)
        if not failures:
            return result

        retry_payload = {
            **payload,
            "previous_source_range_response": raw,
            "contract_failures": failures,
            "retry_instruction": "Correct every contract failure and return the source range JSON object again.",
        }
        retried, retry_failures, _ = self._complete_source_range_edit(system_prompt, retry_payload, request, plan)
        if retry_failures:
            raise MarkdownOutputContractError(retry_failures, retried.edit.replacement_markdown)
        return retried

    def _complete_source_range_edit(
        self,
        system_prompt: str,
        payload: dict[str, object],
        request: MarkdownEditRequest,
        plan: MarkdownSourceRangePlan,
    ) -> tuple[MarkdownEditResult, list[str], dict[str, Any]]:
        raw = self._client.complete_json(
            system_prompt,
            json.dumps(payload, ensure_ascii=False, indent=2),
        )
        replacement, failures = apply_source_range_response(plan, raw.get("edits"))
        failures.extend(validate_markdown_output(request, replacement))
        result = MarkdownEditResult(
            edit=MarkdownEditOperation(
                operation="replace",
                target=request.target,
                summary=str(raw.get("summary") or "").strip(),
                replacement_markdown=replacement,
            )
        )
        return result, failures, raw

    def _complete_edit(
        self,
        system_prompt: str,
        payload: dict[str, object],
        request: MarkdownEditRequest,
        protected: ProtectedMarkdown,
    ) -> tuple[MarkdownEditResult, list[str], str]:
        raw = self._client.complete_json(
            system_prompt,
            json.dumps(payload, ensure_ascii=False, indent=2),
        )
        result = _normalize_edit_result(raw, request.target, operation_for_edit_goal(request.edit_goal))
        protected_replacement = result.edit.replacement_markdown
        restored, failures = protected.restore(protected_replacement)
        restored = repair_markdown_output(request, restored)
        failures.extend(validate_markdown_output(request, restored))
        failures.extend(validate_markdown_syntax(restored))
        restored_result = MarkdownEditResult(
            edit=MarkdownEditOperation(
                operation=result.edit.operation,
                target=result.edit.target,
                summary=result.edit.summary,
                replacement_markdown=restored,
            )
        )
        return restored_result, failures, protected_replacement

    def generate_markdown(self, request: MarkdownCreateRequest) -> MarkdownCreateResult:
        payload = {
            "instruction": request.instruction,
            "conversation_summary": request.conversation_summary,
            "reference_context": request.reference_context or {},
        }
        system_prompt = with_schema_prompt(self._create_system_prompt, self._schema_prompt_provider("edit"))
        result, failures, raw = self._complete_markdown_create(system_prompt, payload)
        if not failures:
            return result

        retry_payload = {
            **payload,
            "previous_generated_markdown": raw,
            "contract_failures": failures,
            "retry_instruction": "Correct every contract failure and return the required JSON object again.",
        }
        retried, retry_failures, retried_raw = self._complete_markdown_create(system_prompt, retry_payload)
        if retry_failures:
            raise MarkdownCreateOutputContractError(retry_failures, retried_raw)
        return retried

    def _complete_markdown_create(
        self,
        system_prompt: str,
        payload: dict[str, object],
    ) -> tuple[MarkdownCreateResult, list[str], dict[str, Any]]:
        raw = self._client.complete_json(system_prompt, json.dumps(payload, ensure_ascii=False, indent=2))
        result = _normalize_create_result(raw)
        failures = validate_markdown_create_output(result.document)
        failures.extend(validate_markdown_syntax(result.document.markdown))
        return result, failures, raw


def build_markdown_editor() -> MarkdownEditorPort:
    api_key = _api_key()
    if not api_key:
        raise RuntimeError("Set MARKDOWN_EDIT_LLM_API_KEY, QUERY_LLM_API_KEY, UPSTAGE_API_KEY, or LLM_API_KEY.")
    prompt_path = Path(os.environ.get("MARKDOWN_EDIT_SYSTEM_PROMPT", str(DEFAULT_MARKDOWN_EDIT_PROMPT)))
    create_prompt_path = Path(os.environ.get("MARKDOWN_CREATE_SYSTEM_PROMPT", str(DEFAULT_MARKDOWN_CREATE_PROMPT)))
    source_edit_prompt_path = Path(
        os.environ.get("MARKDOWN_SOURCE_EDIT_SYSTEM_PROMPT", str(DEFAULT_MARKDOWN_SOURCE_EDIT_PROMPT))
    )
    return ChatCompletionsMarkdownEditor(
        ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint=_endpoint(),
                api_key=api_key,
                model=_model(),
                temperature=_float_env("MARKDOWN_EDIT_LLM_TEMPERATURE", 0.2),
                timeout_seconds=_int_env("MARKDOWN_EDIT_LLM_TIMEOUT_SECONDS", 180),
                max_tokens=_optional_int_env("MARKDOWN_EDIT_LLM_MAX_TOKENS"),
                json_mode=True,
            )
        ),
        system_prompt=prompt_path.read_text(encoding="utf-8"),
        create_system_prompt=create_prompt_path.read_text(encoding="utf-8"),
        source_edit_system_prompt=source_edit_prompt_path.read_text(encoding="utf-8"),
        context_lines=_int_env("MARKDOWN_EDIT_CONTEXT_LINES", 20),
    )


def _read_only_context_payload(scope: MarkdownTargetScope) -> dict[str, object]:
    if not scope.context_before and not scope.context_after:
        return {}
    return {
        "read_only_context": {
            "before": scope.context_before,
            "after": scope.context_after,
        }
    }


def _normalize_edit_result(
    value: dict[str, Any],
    requested_target: MarkdownEditTarget,
    requested_operation: EditOperationType,
) -> MarkdownEditResult:
    return MarkdownEditResult(
        edit=MarkdownEditOperation(
            operation=requested_operation,
            target=requested_target,
            summary=str(value.get("summary") or "").strip(),
            replacement_markdown=str(value.get("replacement_markdown") or value.get("replacementMarkdown") or "").strip(),
        )
    )


def _normalize_create_result(value: dict[str, Any]) -> MarkdownCreateResult:
    return MarkdownCreateResult(
        document=GeneratedMarkdownDocument(
            title=str(value.get("title") or "").strip(),
            summary=str(value.get("summary") or "").strip(),
            markdown=str(value.get("markdown") or value.get("content") or "").strip(),
        )
    )


def _endpoint() -> str:
    return chat_completions_endpoint(
        endpoint_env_names=("MARKDOWN_EDIT_LLM_ENDPOINT", "QUERY_LLM_ENDPOINT", "LLM_ENDPOINT"),
        base_url_env_names=("MARKDOWN_EDIT_LLM_BASE_URL", "QUERY_LLM_BASE_URL", "UPSTAGE_BASE_URL", "LLM_BASE_URL"),
        default_base_url="https://api.upstage.ai/v1",
    )


def _api_key() -> str | None:
    return api_key_from_env(
        key_env_name="MARKDOWN_EDIT_LLM_API_KEY_ENV",
        key_env_names=("MARKDOWN_EDIT_LLM_API_KEY", "QUERY_LLM_API_KEY", "UPSTAGE_API_KEY", "LLM_API_KEY"),
    )


def _model() -> str:
    return model_from_env(
        ("MARKDOWN_EDIT_LLM_MODEL", "QUERY_LLM_MODEL", "UPSTAGE_MODEL", "LLM_MODEL"),
        "solar-pro2",
    )


def _float_env(name: str, default: float) -> float:
    return float_env(name, default)


def _int_env(name: str, default: int) -> int:
    return int_env(name, default)


def _optional_int_env(name: str) -> int | None:
    return optional_int_env(name)
