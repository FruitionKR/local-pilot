from __future__ import annotations

import json
import os
import re
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Sequence

from app.modules.wiki_generation.application.ports import (
    ConceptPageGenerator,
    ConceptResolver,
    SectionPolisher,
    SemanticExtractor,
)
from app.modules.wiki_generation.domain.entities import SemanticPacket, SourceBlock
from app.modules.wiki_generation.infrastructure.prompt_io import (
    render_concept_page_user_prompt,
    render_concept_resolution_user_prompt,
    render_section_polish_user_prompt,
    render_semantic_user_prompt,
)

JsonDict = Dict[str, Any]


class JsonParseError(RuntimeError):
    pass


class SectionPolishParseError(JsonParseError):
    def __init__(self, message: str, raw_content: str) -> None:
        super().__init__(message)
        self.raw_content = raw_content


def strip_json_fence(content: str) -> str:
    content = content.strip()
    if content.startswith("```"):
        content = re.sub(r"^```(?:json)?\s*", "", content)
        content = re.sub(r"\s*```$", "", content)
    return content.strip()


def parse_json_object(content: str) -> JsonDict:
    cleaned = strip_json_fence(content)
    candidates = [cleaned]
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start != -1 and end != -1 and end > start:
        candidates.append(cleaned[start : end + 1])

    last_error: Exception | None = None
    for candidate in candidates:
        for repaired in _json_repair_candidates(candidate):
            try:
                value = json.loads(repaired)
            except json.JSONDecodeError as exc:
                last_error = exc
                continue
            if not isinstance(value, dict):
                raise JsonParseError("Model output must be a JSON object")
            return value
    raise JsonParseError(f"Model output is not repairable JSON: {last_error}")


def parse_section_polish_object(content: str) -> JsonDict:
    cleaned = strip_json_fence(content)
    candidates = [cleaned]
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start != -1 and end != -1 and end > start:
        candidates.append(cleaned[start : end + 1])

    last_error: Exception | None = None
    for candidate in candidates:
        for repaired in _section_polish_repair_candidates(candidate):
            try:
                value = json.loads(repaired)
            except json.JSONDecodeError as exc:
                last_error = exc
                continue
            if not isinstance(value, dict):
                last_error = JsonParseError("SectionPolish output must be a JSON object")
                continue
            return _normalize_section_polish_schema(value)
    raise SectionPolishParseError(f"SectionPolish output is not repairable JSON: {last_error}", content)


def _section_polish_repair_candidates(text: str) -> list[str]:
    out = []
    current = text.strip()
    out.append(current)
    current = re.sub(r",\s*([}\]])", r"\1", current)
    out.append(current)
    current = current.replace("“", '"').replace("”", '"').replace("‘", "'").replace("’", "'")
    out.append(current)
    out.append(_escape_invalid_json_backslashes(current))
    return out


def _json_repair_candidates(text: str) -> list[str]:
    current = text.strip()
    candidates = [current]
    current = re.sub(r",\s*([}\]])", r"\1", current)
    candidates.append(current)
    current = current.replace("“", '"').replace("”", '"').replace("‘", "'").replace("’", "'")
    candidates.append(current)
    candidates.append(_escape_invalid_json_backslashes(current))
    return list(dict.fromkeys(candidates))


def _escape_invalid_json_backslashes(text: str) -> str:
    return re.sub(r'\\(?!["\\/bfnrtu])', r"\\\\", text)


def _normalize_section_polish_schema(value: JsonDict) -> JsonDict:
    items = value.get("items", [])
    if isinstance(items, dict):
        items = [items]
    if not isinstance(items, list):
        items = []

    normalized_items = []
    for item in items:
        if not isinstance(item, dict):
            continue
        normalized_items.append(
            {
                "text": str(item.get("text", "")),
                "anchor_block_ids": _as_string_list(item.get("anchor_block_ids", [])),
            }
        )

    return {
        "section": str(value.get("section", "")),
        "title": str(value.get("title", "")),
        "text": str(value.get("text", "")),
        "anchor_block_ids": _as_string_list(value.get("anchor_block_ids", [])),
        "items": normalized_items,
        "related_concept_hints": _as_string_list(value.get("related_concept_hints", [])),
        "confidence": _as_float(value.get("confidence", 0.0)),
    }


def _as_string_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return [str(item) for item in value if item is not None]
    return [str(value)]


def _as_float(value: Any) -> float:
    try:
        return float(value)
    except Exception:
        return 0.0


@dataclass
class ChatClientConfig:
    endpoint: str
    api_key: str
    model: str
    temperature: float = 0.2
    timeout_seconds: int = 180
    max_tokens: int | None = None
    json_mode: bool = False


class ChatCompletionsJsonClient:
    """Small OpenAI-compatible chat-completions JSON client using stdlib only."""

    def __init__(self, config: ChatClientConfig) -> None:
        self.config = config
        self.prompt_log_dir = os.environ.get("LLM_PROMPT_LOG_DIR", "").strip()
        self._request_index = 0

    def _write_prompt_log(self, body: JsonDict, content: str | None = None, error: str | None = None) -> None:
        if not self.prompt_log_dir:
            return
        self._request_index += 1
        log_dir = Path(self.prompt_log_dir)
        log_dir.mkdir(parents=True, exist_ok=True)
        payload = {
            "request": body,
            "response_content": content,
            "error": error,
        }
        (log_dir / f"request_{self._request_index:04d}.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def complete_text(self, system_prompt: str, user_prompt: str) -> str:
        body: JsonDict = {
            "model": self.config.model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": self.config.temperature,
        }
        if self.config.max_tokens is not None:
            body["max_tokens"] = self.config.max_tokens
        if self.config.json_mode:
            body["response_format"] = {"type": "json_object"}

        req = urllib.request.Request(
            self.config.endpoint,
            data=json.dumps(body).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.config.api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.config.timeout_seconds) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", errors="replace")
            self._write_prompt_log(body, error=f"LLM API HTTP {e.code}: {detail}")
            raise RuntimeError(f"LLM API HTTP {e.code}: {detail}") from e
        except urllib.error.URLError as e:
            self._write_prompt_log(body, error=f"LLM API connection error: {e}")
            raise RuntimeError(f"LLM API connection error: {e}") from e

        try:
            content = payload["choices"][0]["message"]["content"]
        except Exception as e:
            self._write_prompt_log(body, error=f"Unexpected chat-completions response: {payload}")
            raise RuntimeError(f"Unexpected chat-completions response: {payload}") from e
        self._write_prompt_log(body, content=content)
        return content

    def complete_json(self, system_prompt: str, user_prompt: str) -> JsonDict:
        return parse_json_object(self.complete_text(system_prompt, user_prompt))


class GenericChatCompletionsExtractor:
    def __init__(
        self,
        client: ChatCompletionsJsonClient,
        system_prompt: str,
        schema_prompt_provider: Callable[[str], str] | None = None,
    ) -> None:
        self.client = client
        self.system_prompt = system_prompt
        self.schema_prompt_provider = schema_prompt_provider or (lambda feature: "")

    def extract(self, packet: SemanticPacket) -> JsonDict:
        return self.client.complete_json(
            _with_schema_prompt(self.system_prompt, self.schema_prompt_provider("ingest")),
            render_semantic_user_prompt(packet),
        )


class GenericChatCompletionsConceptPageGenerator:
    def __init__(
        self,
        client: ChatCompletionsJsonClient,
        system_prompt: str,
        schema_prompt_provider: Callable[[str], str] | None = None,
    ) -> None:
        self.client = client
        self.system_prompt = system_prompt
        self.schema_prompt_provider = schema_prompt_provider or (lambda feature: "")

    def generate(self, concept: JsonDict, evidence_units: list[JsonDict], source_blocks: Sequence[SourceBlock]) -> JsonDict:
        return self.client.complete_json(
            _with_schema_prompt(self.system_prompt, self.schema_prompt_provider("concept")),
            render_concept_page_user_prompt(concept, evidence_units, source_blocks),
        )


class GenericChatCompletionsConceptResolver:
    def __init__(
        self,
        client: ChatCompletionsJsonClient,
        system_prompt: str,
        schema_prompt_provider: Callable[[str], str] | None = None,
    ) -> None:
        self.client = client
        self.system_prompt = system_prompt
        self.schema_prompt_provider = schema_prompt_provider or (lambda feature: "")

    def resolve(
        self,
        incoming_concepts: list[JsonDict],
        existing_concepts: list[JsonDict],
        missing_related_hints: list[JsonDict] | None = None,
    ) -> JsonDict:
        return self.client.complete_json(
            _with_schema_prompt(self.system_prompt, self.schema_prompt_provider("concept")),
            render_concept_resolution_user_prompt(incoming_concepts, existing_concepts, missing_related_hints),
        )


class GenericChatCompletionsSectionPolisher:
    def __init__(
        self,
        client: ChatCompletionsJsonClient,
        system_prompt: str,
        schema_prompt_provider: Callable[[str], str] | None = None,
    ) -> None:
        self.client = client
        self.system_prompt = system_prompt
        self.schema_prompt_provider = schema_prompt_provider or (lambda feature: "")

    def polish(self, payload: JsonDict, source_blocks: Sequence[SourceBlock]) -> JsonDict:
        content = self.client.complete_text(
            _with_schema_prompt(self.system_prompt, self.schema_prompt_provider("edit")),
            render_section_polish_user_prompt(payload, source_blocks),
        )
        return parse_section_polish_object(content)


def _with_schema_prompt(system_prompt: str, schema_prompt: str) -> str:
    if not schema_prompt.strip():
        return system_prompt
    return f"{system_prompt.rstrip()}\n\n{schema_prompt.strip()}\n"


# Backwards-compatible aliases.
ApiSemanticExtractor = GenericChatCompletionsExtractor
ApiConceptPageGenerator = GenericChatCompletionsConceptPageGenerator
ApiConceptResolver = GenericChatCompletionsConceptResolver
ApiSectionPolisher = GenericChatCompletionsSectionPolisher
