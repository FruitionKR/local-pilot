from __future__ import annotations

import json
import re
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Dict, Protocol, Sequence

from .models import SemanticPacket, SourceBlock
from .prompt_io import render_concept_page_user_prompt, render_semantic_user_prompt

JsonDict = Dict[str, Any]


class SemanticExtractor(Protocol):
    def extract(self, packet: SemanticPacket) -> JsonDict:
        ...


class ConceptPageGenerator(Protocol):
    def generate(self, concept: JsonDict, evidence_units: list[JsonDict], source_blocks: Sequence[SourceBlock]) -> JsonDict:
        ...


class JsonParseError(RuntimeError):
    pass


def strip_json_fence(content: str) -> str:
    content = content.strip()
    if content.startswith("```"):
        content = re.sub(r"^```(?:json)?\s*", "", content)
        content = re.sub(r"\s*```$", "", content)
    return content.strip()


def parse_json_object(content: str) -> JsonDict:
    cleaned = strip_json_fence(content)
    try:
        value = json.loads(cleaned)
    except json.JSONDecodeError:
        start = cleaned.find("{")
        end = cleaned.rfind("}")
        if start == -1 or end == -1 or end <= start:
            raise JsonParseError(f"Model output is not JSON: {content[:500]}")
        value = json.loads(cleaned[start : end + 1])
    if not isinstance(value, dict):
        raise JsonParseError("Model output must be a JSON object")
    return value


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

    def complete_json(self, system_prompt: str, user_prompt: str) -> JsonDict:
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
            raise RuntimeError(f"LLM API HTTP {e.code}: {detail}") from e
        except urllib.error.URLError as e:
            raise RuntimeError(f"LLM API connection error: {e}") from e

        try:
            content = payload["choices"][0]["message"]["content"]
        except Exception as e:
            raise RuntimeError(f"Unexpected chat-completions response: {payload}") from e
        return parse_json_object(content)


class GenericChatCompletionsExtractor:
    def __init__(self, client: ChatCompletionsJsonClient, system_prompt: str) -> None:
        self.client = client
        self.system_prompt = system_prompt

    def extract(self, packet: SemanticPacket) -> JsonDict:
        return self.client.complete_json(self.system_prompt, render_semantic_user_prompt(packet))


class GenericChatCompletionsConceptPageGenerator:
    def __init__(self, client: ChatCompletionsJsonClient, system_prompt: str) -> None:
        self.client = client
        self.system_prompt = system_prompt

    def generate(self, concept: JsonDict, evidence_units: list[JsonDict], source_blocks: Sequence[SourceBlock]) -> JsonDict:
        return self.client.complete_json(
            self.system_prompt,
            render_concept_page_user_prompt(concept, evidence_units, source_blocks),
        )


# Backwards-compatible aliases.
ApiSemanticExtractor = GenericChatCompletionsExtractor
ApiConceptPageGenerator = GenericChatCompletionsConceptPageGenerator
