from __future__ import annotations

import argparse
import json
import logging
import os
import re
import threading
import uuid
from pathlib import Path
from typing import Any, Literal

from fastapi import BackgroundTasks, FastAPI, HTTPException
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel, ConfigDict, Field

from app.modules.agent.interfaces.http.routes import router as agent_router
from app.modules.query.interfaces.http.routes import router as query_router
from app.modules.wiki_schema.interfaces.http.routes import router as wiki_schema_router
from app.modules.wiki_embedding.application.build_wiki_page_embeddings import BuildWikiPageEmbeddingsUseCase
from app.modules.wiki_embedding.infrastructure.bge_m3_embedding_model import BgeM3EmbeddingModel
from app.modules.wiki_embedding.infrastructure.minio_markdown_reader import MinioMarkdownReader
from app.modules.wiki_embedding.infrastructure.postgres_wiki_page_embedding_repository import PostgresWikiPageEmbeddingRepository
from app.modules.wiki_generation.infrastructure.chat_completions_llm import ChatClientConfig, ChatCompletionsJsonClient
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database
from app.modules.wiki_ingestion.infrastructure.object_storage import read_text_object
from app.modules.wiki_ingestion.infrastructure.promotion_concept_page import (
    build_promotion_concept_page as _promotion_concept_page,
    promotion_representative as _promotion_representative,
)
from app.modules.wiki_schema.infrastructure import postgres_wiki_schema_repository as wiki_schema_database
from run_lab import run_pipeline


app = FastAPI(title="Fruition Pipeline Lab API", version="0.1.0")
app.include_router(agent_router)
app.include_router(query_router)
app.include_router(wiki_schema_router)
logger = logging.getLogger("fruition.pipeline")

DOCUMENT_SEMANTIC_PROMPT = "prompts/semantic_extraction.system.md"
CHAT_SEMANTIC_PROMPT = "prompts/chat_semantic_extraction.system.md"
CHAT_APPEND_SEMANTIC_PROMPT = "prompts/chat_semantic_append.system.md"


def _safe_name(value: str) -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9_.-]+", "_", value).strip("._")
    return cleaned or "document"


class PipelineRunIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    document_id: str
    input_name: str | None = None
    out: str | None = None
    mode: Literal["api", "generic-chat"] = "api"
    provider: Literal["upstage", "generic"] = "upstage"
    env_file: str | None = None
    source_page_mode: Literal["auto", "skeleton", "section-polish"] = "auto"
    concept_page_mode: Literal["auto", "api", "full-llm", "skeleton", "section-polish"] = Field(
        default="auto",
        description="auto는 backend skeleton concept page만 생성합니다. section-polish를 명시하면 concept별 LLM polish를 수행합니다.",
    )
    max_packet_chars: int = 7000
    overlap_blocks: int = 1
    endpoint: str | None = None
    api_base_url: str | None = None
    api_key_env: str | None = None
    api_key: str | None = None
    model: str | None = None
    temperature: float = 0.2
    timeout_seconds: int = 180
    max_tokens: int | None = None
    json_mode: bool = False
    system_prompt: str = DOCUMENT_SEMANTIC_PROMPT
    concept_system_prompt: str = "prompts/concept_page_generation.system.md"
    concept_resolution_system_prompt: str = "prompts/concept_resolution.system.md"
    section_polish_system_prompt: str = "prompts/section_polish.system.md"
    source_accumulation_system_prompt: str = "prompts/source_accumulation_evaluator.system.md"
    wiki_evaluator_system_prompt: str = "prompts/wiki_generation_evaluator.system.md"
    existing_wiki_dir: str | None = None
    wiki_evaluation_loop: bool = False
    max_eval_attempts: int = 2
    save_debug_json: bool = Field(default=False, description="True이면 raw LLM output, packet, block_map 같은 디버그 JSON을 저장합니다.")
    log_callback_url: str | None = Field(default=None, description="설정하면 pipeline.log 이벤트가 생길 때마다 이 URL로 JSON POST합니다.")
    wait: bool = Field(default=False, description="True이면 요청 안에서 완료까지 기다립니다. False이면 백그라운드 실행 후 로그를 조회합니다.")
    user_id: str | None = Field(default=None, description="기존 backend 요청 호환 필드이며 Wiki 저장 범위에는 사용하지 않습니다.")
    workspace_id: str | None = Field(default=None, description="기존 backend 요청 호환 필드이며 Wiki 저장 범위에는 사용하지 않습니다.")

class ChatWikiRunIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    document_id: str
    selection_mode: Literal["full", "partial"] = Field(
        description="full은 기존 chat source page에 누적하고, partial은 독립 source page를 생성합니다.",
    )
    input_markdown: str | None = Field(
        default=None,
        description="기존 source page가 있는 full 누적에서 backend가 중복 필터링해 직렬화한 신규 pair Markdown입니다.",
    )
    input_name: str | None = None
    out: str | None = None
    mode: Literal["api", "generic-chat"] = "api"
    provider: Literal["upstage", "generic"] = "upstage"
    env_file: str | None = None
    source_page_mode: Literal["auto", "skeleton", "section-polish"] = "auto"
    concept_page_mode: Literal["auto", "api", "full-llm", "skeleton", "section-polish"] = Field(
        default="auto",
        description="auto는 backend skeleton concept page만 생성합니다. section-polish를 명시하면 concept별 LLM polish를 수행합니다.",
    )
    max_packet_chars: int = 7000
    overlap_blocks: int = 1
    endpoint: str | None = None
    api_base_url: str | None = None
    api_key_env: str | None = None
    api_key: str | None = None
    model: str | None = None
    temperature: float = 0.2
    timeout_seconds: int = 180
    max_tokens: int | None = None
    json_mode: bool = False
    chat_system_prompt: str = CHAT_SEMANTIC_PROMPT
    chat_append_system_prompt: str = CHAT_APPEND_SEMANTIC_PROMPT
    concept_system_prompt: str = "prompts/concept_page_generation.system.md"
    concept_resolution_system_prompt: str = "prompts/concept_resolution.system.md"
    section_polish_system_prompt: str = "prompts/section_polish.system.md"
    source_accumulation_system_prompt: str = "prompts/source_accumulation_evaluator.system.md"
    wiki_evaluator_system_prompt: str = "prompts/wiki_generation_evaluator.system.md"
    existing_wiki_dir: str | None = None
    wiki_evaluation_loop: bool = False
    max_eval_attempts: int = 2
    save_debug_json: bool = Field(default=False, description="True이면 raw LLM output, packet, block_map 같은 디버그 JSON을 저장합니다.")
    log_callback_url: str | None = Field(default=None, description="설정하면 pipeline.log 이벤트가 생길 때마다 이 URL로 JSON POST합니다.")
    wait: bool = Field(default=False, description="True이면 요청 안에서 완료까지 기다립니다. False이면 백그라운드 실행 후 로그를 조회합니다.")
    user_id: str | None = Field(default=None, description="기존 backend 요청 호환 필드이며 Wiki 저장 범위에는 사용하지 않습니다.")
    workspace_id: str | None = Field(default=None, description="기존 backend 요청 호환 필드이며 Wiki 저장 범위에는 사용하지 않습니다.")

class PipelineRunOut(BaseModel):
    run_id: str
    status: str
    manifest: dict[str, Any] | None = None
    output_dir: str
    log_path: str


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


def _build_pipeline_args(
    payload: PipelineRunIn | ChatWikiRunIn,
    run_id: str,
    input_path: Path | None,
    input_markdown: str | None,
    input_name: str,
    out: Path,
    log_path: Path,
    source_document_id: str | None,
    user_id: str,
    workspace_id: str,
) -> argparse.Namespace:
    existing_concept_index = _load_existing_concept_index_for_run(user_id, workspace_id)
    existing_source_context = _load_existing_source_context_for_run(payload, user_id, workspace_id)
    system_prompt = _semantic_prompt_for_run(payload, existing_source_context)
    return argparse.Namespace(
        run_id=run_id,
        source_document_id=source_document_id,
        selection_mode=getattr(payload, "selection_mode", None),
        input=str(input_path) if input_path else input_name,
        input_markdown=input_markdown,
        input_name=input_name,
        out=str(out),
        mode=payload.mode,
        provider=payload.provider,
        env_file=payload.env_file,
        source_page_mode=payload.source_page_mode,
        concept_page_mode=payload.concept_page_mode,
        max_packet_chars=payload.max_packet_chars,
        overlap_blocks=payload.overlap_blocks,
        endpoint=payload.endpoint,
        api_base_url=payload.api_base_url,
        api_key_env=payload.api_key_env,
        api_key=payload.api_key,
        model=payload.model,
        temperature=payload.temperature,
        timeout_seconds=payload.timeout_seconds,
        max_tokens=payload.max_tokens,
        json_mode=payload.json_mode,
        system_prompt=system_prompt,
        concept_system_prompt=payload.concept_system_prompt,
        concept_resolution_system_prompt=payload.concept_resolution_system_prompt,
        section_polish_system_prompt=payload.section_polish_system_prompt,
        source_accumulation_system_prompt=payload.source_accumulation_system_prompt,
        wiki_evaluator_system_prompt=payload.wiki_evaluator_system_prompt,
        existing_wiki_dir=payload.existing_wiki_dir,
        existing_concept_index=existing_concept_index,
        existing_source_artifact=(existing_source_context or {}).get("artifact"),
        existing_source_markdown=(existing_source_context or {}).get("source_markdown"),
        wiki_evaluation_loop=payload.wiki_evaluation_loop,
        max_eval_attempts=payload.max_eval_attempts,
        save_debug_json=payload.save_debug_json,
        log_path=str(log_path),
        log_callback_url=payload.log_callback_url,
        user_id=user_id,
        workspace_id=workspace_id,
    )


def _load_existing_concept_index_for_run(user_id: str, workspace_id: str) -> list[dict[str, Any]]:
    try:
        return database.list_active_concept_index(user_id, workspace_id)
    except Exception:
        logger.exception("failed to load existing concept index for pipeline run")
        return []


def _load_existing_source_context_for_run(
    payload: PipelineRunIn | ChatWikiRunIn,
    user_id: str,
    workspace_id: str,
) -> dict[str, Any] | None:
    if getattr(payload, "selection_mode", None) != "full" or not payload.document_id:
        return None
    try:
        return database.latest_source_page_context(payload.document_id, user_id, workspace_id)
    except Exception:
        logger.exception("failed to load existing source page context for pipeline run")
        return None


def _semantic_prompt_for_run(payload: PipelineRunIn | ChatWikiRunIn, existing_source_context: dict[str, Any] | None) -> str:
    selection_mode = getattr(payload, "selection_mode", None)
    if not selection_mode:
        return payload.system_prompt
    if selection_mode == "full" and existing_source_context:
        return payload.chat_append_system_prompt
    return payload.chat_system_prompt


def _validate_chat_inline_markdown(payload: ChatWikiRunIn, user_id: str, workspace_id: str) -> None:
    if not payload.input_markdown:
        return
    if payload.selection_mode != "full":
        raise HTTPException(status_code=422, detail="input_markdown is only allowed for full chat accumulation")
    try:
        existing_source_context = database.latest_source_page_context(
            payload.document_id,
            user_id,
            workspace_id,
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    if not existing_source_context:
        raise HTTPException(
            status_code=422,
            detail="input_markdown requires an existing source page for full chat accumulation",
        )


def _execute_pipeline_run(run_id: str, args: argparse.Namespace) -> None:
    try:
        manifest = run_pipeline(args)
        page_ids = database.finish_pipeline_run(run_id, manifest)
        _start_embedding_job(run_id, page_ids)
    except Exception as exc:
        database.fail_pipeline_run(run_id, str(exc))
        logger.error("ERROR: pipeline run failed run_id=%s error=%s", run_id, exc)


def _start_embedding_job(run_id: str, page_ids: list[str]) -> None:
    if not page_ids:
        return
    thread = threading.Thread(
        target=_execute_embedding_job,
        args=(run_id, page_ids),
        name=f"wiki-page-embedding-{run_id}",
        daemon=True,
    )
    thread.start()


def _execute_embedding_job(run_id: str, page_ids: list[str]) -> None:
    try:
        use_case = BuildWikiPageEmbeddingsUseCase(
            repository=PostgresWikiPageEmbeddingRepository(),
            embedding_model=BgeM3EmbeddingModel(),
            markdown_reader=MinioMarkdownReader(),
        )
        result = use_case.execute(page_ids)
        logger.info("wiki page embedding job completed run_id=%s result=%s", run_id, result)
    except Exception as exc:
        logger.error("wiki page embedding job failed run_id=%s error=%s", run_id, exc)


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


def _load_document_markdown(document: dict) -> tuple[str, str, str]:
    source_uri = document.get("source_uri")
    extracted_text_uri = document.get("extracted_text_uri")
    mime_type = (document.get("mime_type") or "").lower()

    if extracted_text_uri:
        object_uri = extracted_text_uri
    elif mime_type in {"text/markdown", "text/x-markdown", "text/plain"} or str(document.get("filename", "")).lower().endswith(".md"):
        object_uri = source_uri
    else:
        raise HTTPException(
            status_code=409,
            detail="Document needs extracted_text_uri before pipeline processing. Convert the source file to Markdown/text first.",
        )

    if not object_uri:
        raise HTTPException(status_code=409, detail="Document has no source_uri or extracted_text_uri")

    try:
        markdown = read_text_object(object_uri)
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Failed to read document object from storage: {exc}") from exc

    return markdown, object_uri, str(document.get("filename") or f"{_safe_name(document['id'])}.md")


def _load_document(document_id: str) -> dict:
    try:
        document = database.get_document(document_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    if not document:
        raise HTTPException(status_code=404, detail="Document not found")
    return document


def _load_stored_document_input(document: dict) -> tuple[str, str, str]:
    input_markdown, object_uri, input_name = _load_document_markdown(document)
    return input_markdown, f"storage:{object_uri}", input_name


def _resolve_chat_wiki_input(
    payload: ChatWikiRunIn,
    document: dict,
    user_id: str,
    workspace_id: str,
) -> tuple[str, str, str]:
    if payload.input_markdown:
        _validate_chat_inline_markdown(payload, user_id, workspace_id)
        input_name = payload.input_name or "chat.md"
        input_source = f"inline:{input_name}"
        return payload.input_markdown, input_source, input_name
    return _load_stored_document_input(document)


@app.post("/pipeline/runs", response_model=PipelineRunOut)
def run_pipeline_endpoint(payload: PipelineRunIn, background_tasks: BackgroundTasks) -> PipelineRunOut:
    return _run_pipeline_request(payload, background_tasks)


@app.post("/chat-wiki/runs", response_model=PipelineRunOut)
def run_chat_wiki_endpoint(payload: ChatWikiRunIn, background_tasks: BackgroundTasks) -> PipelineRunOut:
    return _run_pipeline_request(payload, background_tasks)


def _run_pipeline_request(payload: PipelineRunIn | ChatWikiRunIn, background_tasks: BackgroundTasks) -> PipelineRunOut:
    run_id = str(uuid.uuid4())
    out = Path(payload.out) if payload.out else Path("runs") / f"api_{run_id}"
    log_path = out / "pipeline.log"
    input_source = ""
    document_id = payload.document_id
    document = _load_document(document_id)
    user_id = str(document["user_id"])
    workspace_id = str(document["workspace_id"])
    input_markdown: str | None = None
    input_name = payload.input_name or "inline.md"

    if isinstance(payload, ChatWikiRunIn):
        input_markdown, input_source, input_name = _resolve_chat_wiki_input(
            payload,
            document,
            user_id,
            workspace_id,
        )
    else:
        input_markdown, input_source, input_name = _load_stored_document_input(document)

    try:
        database.create_pipeline_run(run_id, document_id, input_source, str(out), payload.mode)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    args = _build_pipeline_args(
        payload,
        run_id,
        None,
        input_markdown,
        input_name,
        out,
        log_path,
        document_id,
        user_id,
        workspace_id,
    )

    if not payload.wait:
        background_tasks.add_task(_execute_pipeline_run, run_id, args)
        return PipelineRunOut(run_id=run_id, status="running", manifest=None, output_dir=str(out), log_path=str(log_path))

    try:
        manifest = run_pipeline(args)
        page_ids = database.finish_pipeline_run(run_id, manifest)
        _start_embedding_job(run_id, page_ids)
    except Exception as exc:
        database.fail_pipeline_run(run_id, str(exc))
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    return PipelineRunOut(run_id=run_id, status="succeeded", manifest=manifest, output_dir=str(out), log_path=str(log_path))


@app.get("/pipeline/runs/{run_id}")
def get_pipeline_run(run_id: str) -> dict:
    try:
        row = database.get_pipeline_run(run_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    if not row:
        raise HTTPException(status_code=404, detail="Pipeline run not found")
    return row


@app.get("/pipeline/runs/{run_id}/logs", response_class=PlainTextResponse)
def get_pipeline_logs(run_id: str) -> str:
    try:
        row = database.get_pipeline_run(run_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    if not row:
        raise HTTPException(status_code=404, detail="Pipeline run not found")

    manifest = row.get("manifest") or {}
    log_path = manifest.get("pipeline_log") or str(Path(row["output_dir"]) / "pipeline.log")
    path = Path(log_path)
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8")
