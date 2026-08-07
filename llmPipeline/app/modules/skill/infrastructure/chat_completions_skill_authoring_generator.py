import json
import os
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
from app.modules.skill.domain.reference_template import extract_markdown_structure
from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ChatClientConfig,
    ChatCompletionsJsonClient,
)


DEFAULT_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "skill_authoring.system.md"
DEFAULT_CLASSIFIER_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "skill_intent_classifier.system.md"
DEFAULT_VERIFIER_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "skill_intent_verifier.system.md"


class ChatCompletionsSkillAuthoringGenerator:
    def __init__(
        self,
        client: ChatCompletionsJsonClient,
        system_prompt: str,
        classifier_prompt: str,
        verifier_prompt: str,
    ) -> None:
        self._client = client
        self._system_prompt = system_prompt
        self._classifier_prompt = classifier_prompt
        self._verifier_prompt = verifier_prompt

    def classify(
        self,
        instruction: str,
        references: tuple[SkillAuthoringReference, ...],
        *,
        requested_description: str | None,
    ) -> dict[str, object]:
        return self._complete_intent(
            self._classifier_prompt,
            instruction,
            references,
            requested_description,
        )

    def verify(
        self,
        instruction: str,
        references: tuple[SkillAuthoringReference, ...],
        *,
        requested_description: str | None,
    ) -> dict[str, object]:
        return self._complete_intent(
            self._verifier_prompt,
            instruction,
            references,
            requested_description,
        )

    def generate(
        self,
        instruction: str,
        references: tuple[SkillAuthoringReference, ...],
        *,
        allow_clarification: bool,
        authoring_mode: SkillAuthoringMode,
        requested_name: str | None,
        requested_description: str | None = None,
        reference_mode: str,
    ) -> dict[str, object]:
        payload: dict[str, object] = {
            "instruction": instruction,
            "authoring_mode": authoring_mode,
            "requested_name": requested_name,
            "requested_description": requested_description,
            "reference_mode": reference_mode,
            "interaction_mode": "multi_turn" if allow_clarification else "single_turn",
            "references": [
                {
                    "markdown_structure": extract_markdown_structure(reference.markdown),
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

    def _complete_intent(
        self,
        system_prompt: str,
        instruction: str,
        references: tuple[SkillAuthoringReference, ...],
        requested_description: str | None,
    ) -> dict[str, object]:
        return self._client.complete_json(
            system_prompt,
            json.dumps(
                {
                    "instruction": instruction,
                    "requested_description": requested_description,
                    "references": [
                        {"markdown_structure": extract_markdown_structure(reference.markdown)}
                        for reference in references
                    ],
                },
                ensure_ascii=False,
                indent=2,
            ),
        )

    def _complete(self, payload: dict[str, object]) -> dict[str, object]:
        return self._client.complete_json(
            self._system_prompt,
            json.dumps(payload, ensure_ascii=False, indent=2),
        )


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
    classifier_prompt_path = Path(
        os.environ.get("SKILL_INTENT_CLASSIFIER_SYSTEM_PROMPT", str(DEFAULT_CLASSIFIER_PROMPT))
    )
    verifier_prompt_path = Path(
        os.environ.get("SKILL_INTENT_VERIFIER_SYSTEM_PROMPT", str(DEFAULT_VERIFIER_PROMPT))
    )
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
        classifier_prompt_path.read_text(encoding="utf-8"),
        verifier_prompt_path.read_text(encoding="utf-8"),
    )
