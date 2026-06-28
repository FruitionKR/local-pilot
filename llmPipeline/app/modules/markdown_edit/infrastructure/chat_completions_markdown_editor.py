import json
import os
from pathlib import Path
from typing import Any

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
    ) -> None:
        self._client = client
        self._system_prompt = system_prompt
        self._create_system_prompt = create_system_prompt or system_prompt

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
        raw = self._client.complete_json(self._system_prompt, json.dumps(payload, ensure_ascii=False, indent=2))
        return _normalize_edit_result(raw, request.target)

    def generate_markdown(self, request: MarkdownCreateRequest) -> MarkdownCreateResult:
        payload = {
            "instruction": request.instruction,
            "conversation_summary": request.conversation_summary,
            "reference_context": request.reference_context or {},
        }
        raw = self._client.complete_json(
            self._create_system_prompt,
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
    endpoint = os.environ.get("MARKDOWN_EDIT_LLM_ENDPOINT") or os.environ.get("QUERY_LLM_ENDPOINT") or os.environ.get("LLM_ENDPOINT")
    if endpoint:
        return endpoint
    base_url = (
        os.environ.get("MARKDOWN_EDIT_LLM_BASE_URL")
        or os.environ.get("QUERY_LLM_BASE_URL")
        or os.environ.get("UPSTAGE_BASE_URL")
        or os.environ.get("LLM_BASE_URL")
        or "https://api.upstage.ai/v1"
    )
    return base_url.rstrip("/") + "/chat/completions"


def _api_key() -> str | None:
    key_env = os.environ.get("MARKDOWN_EDIT_LLM_API_KEY_ENV")
    if key_env and os.environ.get(key_env):
        return os.environ[key_env]
    return (
        os.environ.get("MARKDOWN_EDIT_LLM_API_KEY")
        or os.environ.get("QUERY_LLM_API_KEY")
        or os.environ.get("UPSTAGE_API_KEY")
        or os.environ.get("LLM_API_KEY")
    )


def _model() -> str:
    return (
        os.environ.get("MARKDOWN_EDIT_LLM_MODEL")
        or os.environ.get("QUERY_LLM_MODEL")
        or os.environ.get("UPSTAGE_MODEL")
        or os.environ.get("LLM_MODEL")
        or "solar-pro2"
    )


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
