import json
import os
import re
from pathlib import Path

from app.core.llm_env import (
    api_key_from_env,
    chat_completions_endpoint,
    int_env,
    model_from_env,
    provider_base_url,
    resolve_llm_provider,
)
from app.modules.skill.domain.entities import SkillAuthoringMode, SkillAuthoringReference
from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ChatClientConfig,
    ChatCompletionsJsonClient,
)


DEFAULT_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "skill_authoring.system.md"
HEADING_PATTERN = re.compile(r"^ {0,3}#{1,6}\s+\S")
LIST_ITEM_PATTERN = re.compile(r"^(\s*)(?:[-+*]|\d+[.)])\s+\S")
TABLE_SEPARATOR_PATTERN = re.compile(r"^\s*\|?(?:\s*:?-+:?\s*\|)+\s*:?-+:?\s*\|?\s*$")


class ChatCompletionsSkillAuthoringGenerator:
    def __init__(self, client: ChatCompletionsJsonClient, system_prompt: str) -> None:
        self._client = client
        self._system_prompt = system_prompt

    def generate(
        self,
        instruction: str,
        references: tuple[SkillAuthoringReference, ...],
        *,
        allow_clarification: bool,
        authoring_mode: SkillAuthoringMode,
        requested_name: str | None,
        requested_description: str | None = None,
    ) -> dict[str, object]:
        payload: dict[str, object] = {
            "instruction": instruction,
            "authoring_mode": authoring_mode,
            "requested_name": requested_name,
            "requested_description": requested_description,
            "interaction_mode": "multi_turn" if allow_clarification else "single_turn",
            "references": [
                {
                    "markdown_structure": _extract_markdown_structure(reference.markdown),
                }
                for reference in references
            ],
        }
        result = self._complete(payload)
        if allow_clarification or result.get("status") != "clarification_required":
            return result
        payload["contract_failures"] = [
            "single_turn authoring must create a conservative editable proposal instead of asking a question"
        ]
        return self._complete(payload)

    def _complete(self, payload: dict[str, object]) -> dict[str, object]:
        return self._client.complete_json(
            self._system_prompt,
            json.dumps(payload, ensure_ascii=False, indent=2),
        )


def _extract_markdown_structure(markdown: str) -> str:
    lines = markdown.splitlines()
    structure: list[str] = []
    fence_marker: str | None = None
    for index, line in enumerate(lines):
        stripped = line.lstrip()
        if stripped.startswith(("```", "~~~")):
            marker = stripped[:3]
            if fence_marker is None:
                fence_marker = marker
            elif fence_marker == marker:
                fence_marker = None
            continue
        if fence_marker is not None:
            continue
        if HEADING_PATTERN.match(line):
            structure.append(line.rstrip())
            continue
        list_item = LIST_ITEM_PATTERN.match(line)
        if list_item:
            marker = line[len(list_item.group(1)) :].split(maxsplit=1)[0]
            structure.append(f"{list_item.group(1)}{marker} [item]")
            continue
        if index + 1 < len(lines) and "|" in line and TABLE_SEPARATOR_PATTERN.match(lines[index + 1]):
            structure.extend((line.rstrip(), lines[index + 1].rstrip()))
    return "\n".join(structure)


def build_skill_authoring_generator() -> ChatCompletionsSkillAuthoringGenerator:
    api_key = api_key_from_env(
        key_env_name="SKILL_AUTHORING_LLM_API_KEY_ENV",
        key_env_names=("SKILL_AUTHORING_LLM_API_KEY", "SKILL_DRAFT_LLM_API_KEY", "LLM_API_KEY"),
    )
    model = model_from_env(
        ("SKILL_AUTHORING_LLM_MODEL", "SKILL_DRAFT_LLM_MODEL", "LLM_MODEL"),
        "solar-pro2" if resolve_llm_provider() == "upstage" else "",
    )
    if not api_key or not model:
        raise RuntimeError("Set SKILL_AUTHORING_LLM_API_KEY or LLM_API_KEY and a model.")
    endpoint = chat_completions_endpoint(
        endpoint_env_names=("SKILL_AUTHORING_LLM_ENDPOINT", "SKILL_DRAFT_LLM_ENDPOINT", "LLM_ENDPOINT"),
        base_url_env_names=("SKILL_AUTHORING_LLM_BASE_URL", "SKILL_DRAFT_LLM_BASE_URL", "LLM_BASE_URL"),
        default_base_url=provider_base_url(),
    )
    prompt_path = Path(os.environ.get("SKILL_AUTHORING_SYSTEM_PROMPT", str(DEFAULT_PROMPT)))
    return ChatCompletionsSkillAuthoringGenerator(
        ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint=endpoint,
                api_key=api_key,
                model=model,
                temperature=0.0,
                timeout_seconds=int_env("SKILL_AUTHORING_LLM_TIMEOUT_SECONDS", 180),
                max_tokens=None,
                json_mode=True,
            )
        ),
        prompt_path.read_text(encoding="utf-8"),
    )
