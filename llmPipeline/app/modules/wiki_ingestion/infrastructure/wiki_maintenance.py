from __future__ import annotations

import json
import os
from collections.abc import Callable
from pathlib import Path
from typing import Any

from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ChatClientConfig,
    ChatCompletionsJsonClient,
)
from app.modules.wiki_ingestion.application.models import (
    WikiMaintenanceCommand,
    WikiMaintenanceConfigurationError,
)
from app.modules.wiki_ingestion.application.ports import WikiMaintenancePort
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database
from app.modules.wiki_ingestion.infrastructure.promotion_concept_page import (
    build_promotion_concept_page,
    promotion_representative,
)


class PostgresWikiMaintenance(WikiMaintenancePort):
    def lint(self, command: WikiMaintenanceCommand) -> dict[str, Any]:
        should_materialize = command.materialize_promotions and not command.dry_run
        promotion_generator = (
            self._build_promotion_page_generator(command)
            if should_materialize
            else None
        )
        return database.lint_wiki_workspace(
            command.user_id,
            command.workspace_id,
            materialize_promotions=should_materialize,
            promotion_page_generator=promotion_generator,
            write_log=not command.dry_run,
        )

    def _build_promotion_page_generator(
        self,
        command: WikiMaintenanceCommand,
    ) -> Callable[[dict[str, Any]], dict[str, Any]]:
        client = _lint_api_client(command)

        def generate(cluster: dict[str, Any]) -> dict[str, Any]:
            allowed_refs = {
                ref
                for claim in cluster.get("claims", [])
                for ref in claim.get("refs", [])
            }
            allowed_refs.update(
                block.get("ref")
                for block in cluster.get("source_blocks", [])
                if block.get("ref")
            )
            source_ref_by_block = {ref.rsplit(":", 1)[-1]: ref for ref in allowed_refs}
            user_payload = {
                "cluster": {
                    "id": cluster.get("id"),
                    "representative": promotion_representative(cluster),
                    "promotion_status": cluster.get("promotion_status"),
                    "promotion_source_refs": cluster.get("promotion_source_refs", []),
                    "claims": cluster.get("claims", []),
                    "relations": cluster.get("relations", []),
                },
                "source_blocks": cluster.get("source_blocks", []),
                "allowed_anchor_refs": sorted(allowed_refs),
            }
            draft = client.complete_json(
                _promotion_concept_system_prompt(),
                json.dumps(user_payload, ensure_ascii=False, indent=2),
            )
            return build_promotion_concept_page(
                cluster,
                draft,
                allowed_refs,
                source_ref_by_block,
            )

        return generate


def _lint_api_client(command: WikiMaintenanceCommand) -> ChatCompletionsJsonClient:
    if command.provider == "upstage":
        base_url = (
            command.api_base_url
            or os.environ.get("UPSTAGE_BASE_URL")
            or "https://api.upstage.ai/v1"
        )
        endpoint = command.endpoint or base_url.rstrip("/") + "/chat/completions"
        api_key_env = command.api_key_env or "UPSTAGE_API_KEY"
        model = command.model or os.environ.get("UPSTAGE_MODEL") or "solar-pro2"
    else:
        endpoint = command.endpoint or os.environ.get("LLM_ENDPOINT") or ""
        api_key_env = command.api_key_env or "LLM_API_KEY"
        model = command.model or os.environ.get("LLM_MODEL") or "gpt-4o-mini"
    api_key = command.api_key or os.environ.get(api_key_env)
    if not endpoint:
        raise WikiMaintenanceConfigurationError(
            "Set endpoint or api_base_url for lint LLM"
        )
    if not api_key:
        raise WikiMaintenanceConfigurationError(
            f"Missing API key. Set {api_key_env}=... or pass api_key"
        )
    return ChatCompletionsJsonClient(
        ChatClientConfig(
            endpoint=endpoint,
            api_key=api_key,
            model=model,
            temperature=command.temperature,
            timeout_seconds=command.timeout_seconds,
            max_tokens=command.max_tokens,
            json_mode=False,
        )
    )


def _promotion_concept_system_prompt() -> str:
    base_prompt = Path("prompts/concept_page_generation.system.md").read_text(
        encoding="utf-8"
    )
    return (
        base_prompt
        + "\n\nStage=PromotionClusterConceptPageGeneration.\n"
        "You receive one promotion cluster, evidence claims, existing relation candidates, and source blocks.\n"
        "Generate a real concept page draft from the supplied evidence only.\n"
        "Use allowed_anchor_refs exactly as anchor_block_ids. They may be global refs like doc_id:B0001.\n"
        "Do not use refs that are not listed in allowed_anchor_refs.\n"
    )
