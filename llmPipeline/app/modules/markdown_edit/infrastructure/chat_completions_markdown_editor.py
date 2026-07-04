import json
import os
from collections.abc import Callable
from pathlib import Path
from typing import Any

from app.core.llm_env import api_key_from_env, chat_completions_endpoint, float_env, int_env, model_from_env, optional_int_env
from app.modules.markdown_edit.application.ports import MarkdownEditorPort
from app.modules.markdown_edit.domain.entities import (
    GeneratedMarkdownDocument,
    MarkdownCreateRequest,
    MarkdownCreateResult,
    MarkdownEditOperation,
    MarkdownEditRequest,
    MarkdownEditResult,
    MarkdownEditTarget,
)
from app.modules.wiki_generation.infrastructure.chat_completions_llm import ChatClientConfig, ChatCompletionsJsonClient


DEFAULT_MARKDOWN_EDIT_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "markdown_edit.system.md"
DEFAULT_MARKDOWN_CREATE_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "markdown_create.system.md"


class ChatCompletionsMarkdownEditor(MarkdownEditorPort):
    def __init__(
        self,
        client: ChatCompletionsJsonClient,
        system_prompt: str,
        create_system_prompt: str | None = None,
        schema_prompt_provider: Callable[[str], str] | None = None,
    ) -> None:
        self._client = client
        self._system_prompt = system_prompt
        self._create_system_prompt = create_system_prompt or system_prompt
        self._schema_prompt_provider = schema_prompt_provider or (lambda feature: "")

    def generate_edit(self, request: MarkdownEditRequest) -> MarkdownEditResult:
        payload = {
            "instruction": request.instruction,
            "edit_goal": request.edit_goal,
            "conversation_summary": request.conversation_summary,
            "target": {
                "type": request.target.type,
                "start_line": request.target.start_line,
                "end_line": request.target.end_line,
            },
            "markdown": request.markdown,
        }
        raw = self._client.complete_json(
            _with_schema_prompt(self._system_prompt, self._schema_prompt_provider("edit")),
            json.dumps(payload, ensure_ascii=False, indent=2),
        )
        return _normalize_edit_result(raw, request.target)

    def generate_markdown(self, request: MarkdownCreateRequest) -> MarkdownCreateResult:
        payload = {
            "instruction": request.instruction,
            "conversation_summary": request.conversation_summary,
            "reference_context": request.reference_context or {},
        }
        raw = self._client.complete_json(
            _with_schema_prompt(self._create_system_prompt, self._schema_prompt_provider("edit")),
            json.dumps(payload, ensure_ascii=False, indent=2),
        )
        return _normalize_create_result(raw)


def build_markdown_editor() -> MarkdownEditorPort:
    api_key = _api_key()
    if not api_key:
        raise RuntimeError("Set MARKDOWN_EDIT_LLM_API_KEY, QUERY_LLM_API_KEY, UPSTAGE_API_KEY, or LLM_API_KEY.")
    prompt_path = Path(os.environ.get("MARKDOWN_EDIT_SYSTEM_PROMPT", str(DEFAULT_MARKDOWN_EDIT_PROMPT)))
    create_prompt_path = Path(os.environ.get("MARKDOWN_CREATE_SYSTEM_PROMPT", str(DEFAULT_MARKDOWN_CREATE_PROMPT)))
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
    )


def _with_schema_prompt(system_prompt: str, schema_prompt: str) -> str:
    if not schema_prompt.strip():
        return system_prompt
    return f"{system_prompt.rstrip()}\n\n{schema_prompt.strip()}\n"


def _normalize_edit_result(value: dict[str, Any], requested_target: MarkdownEditTarget) -> MarkdownEditResult:
    operation = str(value.get("operation") or "replace").strip()
    if operation != "replace":
        operation = "replace"
    return MarkdownEditResult(
        edit=MarkdownEditOperation(
            operation="replace",
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
