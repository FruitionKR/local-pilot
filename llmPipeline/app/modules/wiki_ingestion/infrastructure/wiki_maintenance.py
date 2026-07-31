from __future__ import annotations

import json
import os
from collections.abc import Callable
from contextlib import nullcontext
from pathlib import Path
from typing import Any

from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ChatClientConfig,
    ChatCompletionsJsonClient,
)
from app.core.llm_env import provider_api_endpoint, resolve_llm_provider_defaults
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
        if not command.dry_run and not str(command.operation_id or "").strip():
            raise WikiMaintenanceConfigurationError(
                "operation_id is required when dry_run is false"
            )
        should_materialize = command.materialize_promotions and not command.dry_run
        promotion_generator = (
            self._build_promotion_page_generator(command)
            if should_materialize
            else None
        )
        transaction = (
            nullcontext(None) if command.dry_run else database.connect()
        )
        with transaction as connection:
            result = database.lint_wiki_workspace(
                command.user_id,
                command.workspace_id,
                materialize_promotions=should_materialize,
                promotion_page_generator=promotion_generator,
                apply_reconciliation=not command.dry_run,
                operation_id=command.operation_id,
                write_log=False,
                connection=connection,
            )
            result.update(
                database.lint_orphan_wiki_links(
                    command.user_id,
                    command.workspace_id,
                    apply=not command.dry_run,
                    connection=connection,
                )
            )
            if not command.dry_run:
                result["operation_id"] = command.operation_id
                operation_artifacts = database.persist_lint_operation_result(
                    command.user_id,
                    command.workspace_id,
                    str(command.operation_id),
                    result,
                    connection=connection,
                )
                result["operation_artifacts"] = operation_artifacts
                result["changed_pages"] = operation_artifacts
                database.write_wiki_lint_log(result)
                database.apply_lint_object_changes(result)
        return result

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
    defaults = resolve_llm_provider_defaults(
        provider=command.provider,
        base_url=command.api_base_url,
        api_key_env=command.api_key_env,
        api_key=command.api_key,
        model=command.model,
    )
    endpoint = (
        command.endpoint
        or os.environ.get("LLM_ENDPOINT")
        or provider_api_endpoint(defaults.base_url, defaults.provider)
    )
    if not defaults.api_key:
        raise WikiMaintenanceConfigurationError(
            f"Missing API key. Set {defaults.api_key_env}=... or pass api_key"
        )
    if not defaults.model:
        raise WikiMaintenanceConfigurationError(
            "Missing model. Set LLM_MODEL or pass model"
        )
    return ChatCompletionsJsonClient(
        ChatClientConfig(
            endpoint=endpoint,
            api_key=defaults.api_key,
            model=defaults.model,
            temperature=command.temperature,
            timeout_seconds=command.timeout_seconds,
            max_tokens=command.max_tokens,
            json_mode=False,
            provider=defaults.provider,
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
