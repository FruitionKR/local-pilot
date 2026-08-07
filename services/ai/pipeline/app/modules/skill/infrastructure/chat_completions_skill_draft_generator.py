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
from app.modules.skill.domain.entities import SkillDraftSourceRun
from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ChatClientConfig,
    ChatCompletionsJsonClient,
)


DEFAULT_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "skill_draft_proposal.system.md"


class ChatCompletionsSkillDraftGenerator:
    def __init__(self, client: ChatCompletionsJsonClient, system_prompt: str) -> None:
        self._client = client
        self._system_prompt = system_prompt

    def generate(
        self,
        source_runs: tuple[SkillDraftSourceRun, ...],
        user_directives: tuple[str, ...],
    ) -> dict[str, object]:
        return self._client.complete_json(
            self._system_prompt,
            json.dumps(
                {
                    "source_runs": [
                        {
                            "request_summary": source.request_summary,
                            "plan_summary": source.plan_summary,
                            "successful_operations": [
                                {
                                    "tool_name": operation.tool_name,
                                    "reason": operation.reason,
                                }
                                for operation in source.successful_operations
                            ],
                        }
                        for source in source_runs
                    ],
                    "user_directives": list(user_directives),
                },
                ensure_ascii=False,
                indent=2,
            ),
        )


def build_skill_draft_generator() -> ChatCompletionsSkillDraftGenerator:
    api_key = api_key_from_env(
        key_env_name="SKILL_DRAFT_LLM_API_KEY_ENV",
        key_env_names=("SKILL_DRAFT_LLM_API_KEY", "AGENT_PLAN_LLM_API_KEY", "LLM_API_KEY"),
    )
    model = model_from_env(
        ("SKILL_DRAFT_LLM_MODEL", "AGENT_PLAN_LLM_MODEL", "LLM_MODEL"),
        "solar-pro2" if resolve_llm_provider() == "upstage" else "",
    )
    if not api_key or not model:
        raise RuntimeError("Set SKILL_DRAFT_LLM_API_KEY or LLM_API_KEY and a model.")
    endpoint = chat_completions_endpoint(
        endpoint_env_names=("SKILL_DRAFT_LLM_ENDPOINT", "AGENT_PLAN_LLM_ENDPOINT", "LLM_ENDPOINT"),
        base_url_env_names=("SKILL_DRAFT_LLM_BASE_URL", "AGENT_PLAN_LLM_BASE_URL", "LLM_BASE_URL"),
        default_base_url=provider_base_url(),
    )
    prompt_path = Path(os.environ.get("SKILL_DRAFT_SYSTEM_PROMPT", str(DEFAULT_PROMPT)))
    return ChatCompletionsSkillDraftGenerator(
        ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint=endpoint,
                api_key=api_key,
                model=model,
                temperature=0.0,
                timeout_seconds=int_env("SKILL_DRAFT_LLM_TIMEOUT_SECONDS", 180),
                max_tokens=None,
                json_mode=True,
            )
        ),
        prompt_path.read_text(encoding="utf-8"),
    )
