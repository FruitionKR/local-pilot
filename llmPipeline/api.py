from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from app.modules.agent.interfaces.http.routes import router as agent_router
from app.modules.query.interfaces.http.routes import router as query_router
from app.modules.wiki_ingestion.interfaces.http.routes import router as pipeline_router
from app.modules.wiki_ingestion.interfaces.http.schemas import (
    CHAT_APPEND_SEMANTIC_PROMPT,
    CHAT_SEMANTIC_PROMPT,
    DOCUMENT_SEMANTIC_PROMPT,
    ChatWikiRunIn,
    PipelineRunIn,
    PipelineRunOut,
)
from app.modules.wiki_schema.interfaces.http.routes import router as wiki_schema_router
from app.modules.wiki_generation.infrastructure.chat_completions_llm import ChatClientConfig, ChatCompletionsJsonClient
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database
from app.modules.wiki_ingestion.infrastructure.promotion_concept_page import (
    build_promotion_concept_page as _promotion_concept_page,
    promotion_representative as _promotion_representative,
)
from app.modules.wiki_schema.infrastructure import postgres_wiki_schema_repository as wiki_schema_database
app = FastAPI(title="Fruition Pipeline Lab API", version="0.1.0")
app.include_router(agent_router)
app.include_router(query_router)
app.include_router(pipeline_router)
app.include_router(wiki_schema_router)


class WikiLintIn(BaseModel):
    user_id: str = "local-user"
    workspace_id: str = "local-workspace"
    materialize_promotions: bool = False
    dry_run: bool = True
    provider: Literal["upstage", "generic"] = "upstage"
    endpoint: str | None = None
    api_base_url: str | None = None
    api_key_env: str | None = None
    api_key: str | None = None
    model: str | None = None
    temperature: float = 0.2
    timeout_seconds: int = 180
    max_tokens: int | None = None


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/admin/init-db")
def init_db() -> dict[str, str]:
    try:
        database.init_db()
        wiki_schema_database.init_db()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return {"status": "initialized"}


@app.post("/wiki/maintenance/lint")
def lint_wiki_workspace(payload: WikiLintIn) -> dict[str, Any]:
    try:
        should_materialize = payload.materialize_promotions and not payload.dry_run
        promotion_generator = _build_promotion_page_generator(payload) if should_materialize else None
        return database.lint_wiki_workspace(
            payload.user_id,
            payload.workspace_id,
            materialize_promotions=should_materialize,
            promotion_page_generator=promotion_generator,
            write_log=not payload.dry_run,
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


def _build_promotion_page_generator(payload: WikiLintIn):
    client = _lint_api_client(payload)

    def generate(cluster: dict[str, Any]) -> dict[str, Any]:
        allowed_refs = {
            ref
            for claim in cluster.get("claims", [])
            for ref in claim.get("refs", [])
        }
        allowed_refs.update(block.get("ref") for block in cluster.get("source_blocks", []) if block.get("ref"))
        source_ref_by_block = {ref.rsplit(":", 1)[-1]: ref for ref in allowed_refs}
        system_prompt = _promotion_concept_system_prompt()
        user_payload = {
            "cluster": {
                "id": cluster.get("id"),
                "representative": _promotion_representative(cluster),
                "promotion_status": cluster.get("promotion_status"),
                "promotion_source_refs": cluster.get("promotion_source_refs", []),
                "claims": cluster.get("claims", []),
                "relations": cluster.get("relations", []),
            },
            "source_blocks": cluster.get("source_blocks", []),
            "allowed_anchor_refs": sorted(allowed_refs),
        }
        draft = client.complete_json(system_prompt, json.dumps(user_payload, ensure_ascii=False, indent=2))
        return _promotion_concept_page(cluster, draft, allowed_refs, source_ref_by_block)

    return generate


def _lint_api_client(payload: WikiLintIn) -> ChatCompletionsJsonClient:
    if payload.provider == "upstage":
        base_url = payload.api_base_url or os.environ.get("UPSTAGE_BASE_URL") or "https://api.upstage.ai/v1"
        endpoint = payload.endpoint or base_url.rstrip("/") + "/chat/completions"
        api_key_env = payload.api_key_env or "UPSTAGE_API_KEY"
        model = payload.model or os.environ.get("UPSTAGE_MODEL") or "solar-pro2"
    else:
        endpoint = payload.endpoint or os.environ.get("LLM_ENDPOINT") or ""
        api_key_env = payload.api_key_env or "LLM_API_KEY"
        model = payload.model or os.environ.get("LLM_MODEL") or "gpt-4o-mini"
    api_key = payload.api_key or os.environ.get(api_key_env)
    if not endpoint:
        raise HTTPException(status_code=400, detail="Set endpoint or api_base_url for lint LLM")
    if not api_key:
        raise HTTPException(status_code=400, detail=f"Missing API key. Set {api_key_env}=... or pass api_key")
    return ChatCompletionsJsonClient(
        ChatClientConfig(
            endpoint=endpoint,
            api_key=api_key,
            model=model,
            temperature=payload.temperature,
            timeout_seconds=payload.timeout_seconds,
            max_tokens=payload.max_tokens,
            json_mode=False,
        )
    )


def _promotion_concept_system_prompt() -> str:
    base_prompt = Path("prompts/concept_page_generation.system.md").read_text(encoding="utf-8")
    return (
        base_prompt
        + "\n\nStage=PromotionClusterConceptPageGeneration.\n"
        "You receive one promotion cluster, evidence claims, existing relation candidates, and source blocks.\n"
        "Generate a real concept page draft from the supplied evidence only.\n"
        "Use allowed_anchor_refs exactly as anchor_block_ids. They may be global refs like doc_id:B0001.\n"
        "Do not use refs that are not listed in allowed_anchor_refs.\n"
    )


@app.get("/documents/{document_id}")
def get_document(document_id: str) -> dict:
    try:
        document = database.get_document(document_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    if not document:
        raise HTTPException(status_code=404, detail="Document not found")
    return document
