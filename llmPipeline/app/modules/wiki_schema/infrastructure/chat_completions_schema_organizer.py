from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Protocol

from app.modules.wiki_generation.infrastructure.chat_completions_llm import ChatClientConfig, ChatCompletionsJsonClient
from app.modules.wiki_schema.application.ports import SchemaOrganizerPort
from app.modules.wiki_schema.domain.entities import SchemaFragments, SchemaOrganizerCandidate


DEFAULT_SCHEMA_ORGANIZER_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "wiki_schema_organizer.system.md"


class JsonChatClient(Protocol):
    def complete_json(self, system_prompt: str, user_prompt: str) -> dict[str, Any]:
        ...


class ChatCompletionsSchemaOrganizer(SchemaOrganizerPort):
    def __init__(self, client: JsonChatClient, system_prompt: str) -> None:
        self._client = client
        self._system_prompt = system_prompt

    def organize(self, raw_markdown: str) -> SchemaOrganizerCandidate:
        payload = {
            "raw_markdown": raw_markdown,
            "target_sections": [
                "global_markdown",
                "query_markdown",
                "ingest_markdown",
                "edit_markdown",
                "concept_markdown",
                "template_markdown",
            ],
        }
        raw = self._client.complete_json(self._system_prompt, json.dumps(payload, ensure_ascii=False, indent=2))
        return _normalize_candidate(raw)


def build_schema_organizer() -> SchemaOrganizerPort:
    endpoint = _endpoint()
    api_key = _api_key() or ("ollama" if _is_local_ollama_endpoint(endpoint) else None)
    if not api_key:
        raise RuntimeError("Set WIKI_SCHEMA_LLM_API_KEY, QUERY_LLM_API_KEY, UPSTAGE_API_KEY, or LLM_API_KEY.")
    prompt_path = Path(os.environ.get("WIKI_SCHEMA_SYSTEM_PROMPT", str(DEFAULT_SCHEMA_ORGANIZER_PROMPT)))
    return ChatCompletionsSchemaOrganizer(
        ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint=endpoint,
                api_key=api_key,
                model=_model(),
                temperature=_float_env("WIKI_SCHEMA_LLM_TEMPERATURE", 0.0),
                timeout_seconds=_int_env("WIKI_SCHEMA_LLM_TIMEOUT_SECONDS", 180),
                max_tokens=_optional_int_env("WIKI_SCHEMA_LLM_MAX_TOKENS") or 1200,
                json_mode=True,
            )
        ),
        system_prompt=prompt_path.read_text(encoding="utf-8"),
    )


def _normalize_candidate(value: dict[str, Any]) -> SchemaOrganizerCandidate:
    return SchemaOrganizerCandidate(
        fragments=SchemaFragments(
            global_markdown=_optional_text(value.get("global_markdown") or value.get("globalMarkdown")),
            query_markdown=_optional_text(value.get("query_markdown") or value.get("queryMarkdown")),
            ingest_markdown=_optional_text(value.get("ingest_markdown") or value.get("ingestMarkdown")),
            edit_markdown=_optional_text(value.get("edit_markdown") or value.get("editMarkdown")),
            concept_markdown=_optional_text(value.get("concept_markdown") or value.get("conceptMarkdown")),
            template_markdown=_optional_text(value.get("template_markdown") or value.get("templateMarkdown")),
        ),
        blocked_candidates=_string_list(value.get("blocked_candidates") or value.get("blockedCandidates")),
        unclear_items=_string_list(value.get("unclear_items") or value.get("unclearItems")),
    )


def _optional_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def _string_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value.strip()] if value.strip() else []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    return [str(value).strip()] if str(value).strip() else []


def _endpoint() -> str:
    endpoint = os.environ.get("WIKI_SCHEMA_LLM_ENDPOINT") or os.environ.get("QUERY_LLM_ENDPOINT") or os.environ.get("LLM_ENDPOINT")
    if endpoint:
        return endpoint
    base_url = (
        os.environ.get("WIKI_SCHEMA_LLM_BASE_URL")
        or os.environ.get("QUERY_LLM_BASE_URL")
        or os.environ.get("UPSTAGE_BASE_URL")
        or os.environ.get("LLM_BASE_URL")
        or "http://127.0.0.1:11434/v1"
    )
    return base_url.rstrip("/") + "/chat/completions"


def _api_key() -> str | None:
    key_env = os.environ.get("WIKI_SCHEMA_LLM_API_KEY_ENV")
    if key_env and os.environ.get(key_env):
        return os.environ[key_env].strip() or None
    api_key = (
        os.environ.get("WIKI_SCHEMA_LLM_API_KEY")
        or os.environ.get("QUERY_LLM_API_KEY")
        or os.environ.get("UPSTAGE_API_KEY")
        or os.environ.get("LLM_API_KEY")
    )
    return api_key.strip() if api_key and api_key.strip() else None


def _model() -> str:
    return (
        os.environ.get("WIKI_SCHEMA_LLM_MODEL")
        or os.environ.get("QUERY_LLM_MODEL")
        or os.environ.get("UPSTAGE_MODEL")
        or os.environ.get("LLM_MODEL")
        or "qwen2.5:7b"
    )


def _is_local_ollama_endpoint(endpoint: str) -> bool:
    return "127.0.0.1:11434" in endpoint or "localhost:11434" in endpoint


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
