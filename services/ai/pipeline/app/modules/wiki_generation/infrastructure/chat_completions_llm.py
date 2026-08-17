from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

from app.core.langsmith_tracing import langsmith_tracing_enabled
from app.core.llm_env import inference_profile, resolve_llm_selection
from app.core.llm_prompt import (
    redact_numeric_personal_data,
    with_llm_security_boundary,
    with_schema_prompt,
)
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
    render_source_accumulation_user_prompt,
)
from app.modules.wiki_generation.infrastructure.json_output_parser import (
    JsonDict,
    JsonParseError,
    SectionPolishParseError,
    parse_json_object,
    parse_section_polish_object,
    strip_json_fence,
)

try:
    from langsmith import traceable
except ImportError:  # pragma: no cover - optional tracing dependency
    traceable = None

@dataclass
class ChatClientConfig:
    endpoint: str
    api_key: str
    model: str
    temperature: float | None = 0.2
    timeout_seconds: int = 180
    max_tokens: int | None = None
    json_mode: bool = False
    provider: str | None = None


class ChatCompletionsJsonClient:
    """Small OpenAI-compatible chat-completions JSON client using stdlib only."""

    def __init__(self, config: ChatClientConfig) -> None:
        self.config = config
        self.provider, self.config.model = resolve_llm_selection(config.provider, config.model)
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
            "response_content": redact_numeric_personal_data(content) if content else content,
            "error": redact_numeric_personal_data(error) if error else error,
        }
        (log_dir / f"request_{self._request_index:04d}.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def complete_text(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        trusted_identifiers: tuple[str, ...] = (),
    ) -> str:
        body: JsonDict = {
            "model": self.config.model,
            "messages": [
                {"role": "system", "content": with_llm_security_boundary(system_prompt)},
                {
                    "role": "user",
                    "content": redact_numeric_personal_data(
                        user_prompt,
                        trusted_identifiers=trusted_identifiers,
                    ),
                },
            ],
        }
        body.update(inference_profile(self.provider, self.config.model))
        if self.config.max_tokens is not None:
            body["max_tokens"] = self.config.max_tokens
        if self.config.json_mode:
            body["response_format"] = {"type": "json_object"}

        return self._complete_text_with_optional_trace(body, trusted_identifiers)

    def _complete_text_with_optional_trace(
        self,
        body: JsonDict,
        trusted_identifiers: tuple[str, ...],
    ) -> str:
        if traceable is None or not langsmith_tracing_enabled():
            return self._send_chat_completion(body, trusted_identifiers)
        traced = traceable(
            name=f"{self.provider}_chat_completions",
            run_type="llm",
            metadata={
                "provider": self.provider,
                "model": self.config.model,
                "json_mode": self.config.json_mode,
            },
        )(self._send_chat_completion)
        return traced(body, trusted_identifiers)

    def _send_chat_completion(
        self,
        body: JsonDict,
        trusted_identifiers: tuple[str, ...],
    ) -> str:
        request_body = (
            self._anthropic_request_body(body)
            if self.provider == "claude"
            else body
        )
        req = urllib.request.Request(
            self.config.endpoint,
            data=json.dumps(request_body).encode("utf-8"),
            headers=self._request_headers(),
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.config.timeout_seconds) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            detail = redact_numeric_personal_data(
                e.read().decode("utf-8", errors="replace")
            )
            self._write_prompt_log(body, error=f"LLM API HTTP {e.code}: {detail}")
            raise RuntimeError(f"LLM API HTTP {e.code}: {detail}") from e
        except (OSError, ValueError) as e:
            self._write_prompt_log(body, error=f"LLM API transport or response error: {e}")
            raise RuntimeError(f"LLM API transport or response error: {e}") from e

        try:
            content = redact_numeric_personal_data(
                self._response_content(payload),
                trusted_identifiers=trusted_identifiers,
            )
        except Exception as e:
            detail = redact_numeric_personal_data(str(payload))
            self._write_prompt_log(body, error=f"Unexpected chat-completions response: {detail}")
            raise RuntimeError(f"Unexpected chat-completions response: {detail}") from e
        self._write_prompt_log(body, content=content)
        return content

    def _request_headers(self) -> dict[str, str]:
        if self.provider == "claude":
            return {
                "Content-Type": "application/json",
                "x-api-key": self.config.api_key,
                "anthropic-version": "2023-06-01",
            }
        return {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.config.api_key}",
        }

    def _anthropic_request_body(self, body: JsonDict) -> JsonDict:
        messages = list(body["messages"])
        system_prompt = "\n\n".join(
            str(message["content"])
            for message in messages
            if message.get("role") == "system"
        )
        if self.config.json_mode:
            system_prompt = (
                f"{system_prompt}\n\n"
                "Return only one valid JSON object without Markdown fences."
            )
        request_body: JsonDict = {
            "model": body["model"],
            "system": system_prompt,
            "messages": [
                message
                for message in messages
                if message.get("role") in {"user", "assistant"}
            ],
            "max_tokens": body.get("max_tokens") or 4096,
        }
        return request_body

    def _response_content(self, payload: JsonDict) -> str:
        if self.provider != "claude":
            return _content_text(payload["choices"][0]["message"]["content"])
        return "".join(
            _content_text(block.get("text"))
            for block in payload["content"]
            if block.get("type") == "text"
        )

    def complete_json(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        trusted_identifiers: tuple[str, ...] = (),
    ) -> JsonDict:
        return parse_json_object(
            self.complete_text(
                system_prompt,
                user_prompt,
                trusted_identifiers=trusted_identifiers,
            )
        )


class GenericChatCompletionsExtractor:
    def __init__(
        self,
        client: ChatCompletionsJsonClient,
        system_prompt: str,
        schema_prompt_provider: Callable[[str], str] | None = None,
        source_context: JsonDict | None = None,
    ) -> None:
        self.client = client
        self.system_prompt = system_prompt
        self.schema_prompt_provider = schema_prompt_provider or (lambda feature: "")
        self.source_context = source_context

    def extract(self, packet: SemanticPacket) -> JsonDict:
        return self.client.complete_json(
            with_schema_prompt(self.system_prompt, self.schema_prompt_provider("ingest")),
            render_semantic_user_prompt(packet, self.source_context),
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
            with_schema_prompt(self.system_prompt, self.schema_prompt_provider("concept")),
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
            with_schema_prompt(self.system_prompt, self.schema_prompt_provider("concept")),
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
            with_schema_prompt(self.system_prompt, self.schema_prompt_provider("edit")),
            render_section_polish_user_prompt(payload, source_blocks),
        )
        return parse_section_polish_object(content)


class GenericChatCompletionsSourceAccumulator:
    def __init__(
        self,
        client: ChatCompletionsJsonClient,
        system_prompt: str,
        schema_prompt_provider: Callable[[str], str] | None = None,
    ) -> None:
        self.client = client
        self.system_prompt = system_prompt
        self.schema_prompt_provider = schema_prompt_provider or (lambda feature: "")

    def evaluate(self, payload: JsonDict, source_blocks: Sequence[SourceBlock]) -> JsonDict:
        return self.client.complete_json(
            with_schema_prompt(self.system_prompt, self.schema_prompt_provider("edit")),
            render_source_accumulation_user_prompt(payload, source_blocks),
        )


# Backwards-compatible aliases.
ApiSemanticExtractor = GenericChatCompletionsExtractor


def _content_text(value: object) -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "".join(_content_text(item) for item in value)
    if isinstance(value, dict):
        return str(value.get("text") or value.get("content") or "")
    return str(value or "")
ApiConceptPageGenerator = GenericChatCompletionsConceptPageGenerator
ApiConceptResolver = GenericChatCompletionsConceptResolver
ApiSectionPolisher = GenericChatCompletionsSectionPolisher
ApiSourceAccumulator = GenericChatCompletionsSourceAccumulator
