import logging
import re
import uuid
from pathlib import Path
from typing import Any

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from fastapi.responses import PlainTextResponse

from app.modules.wiki_ingestion.application.models import (
    PipelineRunCommand,
    PipelineRunRegistration,
)
from app.modules.wiki_ingestion.application.ports import (
    PipelineLogReaderPort,
    PipelineRunRepositoryPort,
    PipelineSourceReaderPort,
)
from app.modules.wiki_ingestion.application.run_pipeline import RunPipelineUseCase
from app.modules.wiki_ingestion.interfaces.http.dependencies import (
    get_pipeline_log_reader,
    get_pipeline_run_repository,
    get_pipeline_run_use_case,
    get_pipeline_source_reader,
)
from app.modules.wiki_ingestion.interfaces.http.schemas import (
    CHAT_APPEND_SEMANTIC_PROMPT,
    CHAT_SEMANTIC_PROMPT,
    ChatWikiRunIn,
    PipelineRunIn,
    PipelineRunOut,
)


router = APIRouter(tags=["pipeline"])
logger = logging.getLogger("fruition.pipeline")


@router.post("/pipeline/runs", response_model=PipelineRunOut)
def run_pipeline_endpoint(
    payload: PipelineRunIn,
    background_tasks: BackgroundTasks,
    use_case: RunPipelineUseCase = Depends(get_pipeline_run_use_case),
    repository: PipelineRunRepositoryPort = Depends(get_pipeline_run_repository),
    source_reader: PipelineSourceReaderPort = Depends(get_pipeline_source_reader),
) -> PipelineRunOut:
    return _run_pipeline_request(
        payload,
        background_tasks,
        use_case,
        repository,
        source_reader,
    )


@router.post("/chat-wiki/runs", response_model=PipelineRunOut)
def run_chat_wiki_endpoint(
    payload: ChatWikiRunIn,
    background_tasks: BackgroundTasks,
    use_case: RunPipelineUseCase = Depends(get_pipeline_run_use_case),
    repository: PipelineRunRepositoryPort = Depends(get_pipeline_run_repository),
    source_reader: PipelineSourceReaderPort = Depends(get_pipeline_source_reader),
) -> PipelineRunOut:
    return _run_pipeline_request(
        payload,
        background_tasks,
        use_case,
        repository,
        source_reader,
    )


@router.get("/pipeline/runs/{run_id}")
def get_pipeline_run(
    run_id: str,
    repository: PipelineRunRepositoryPort = Depends(get_pipeline_run_repository),
) -> dict[str, Any]:
    try:
        row = repository.get_run(run_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    if not row:
        raise HTTPException(status_code=404, detail="Pipeline run not found")
    return row


@router.get("/pipeline/runs/{run_id}/logs", response_class=PlainTextResponse)
def get_pipeline_logs(
    run_id: str,
    repository: PipelineRunRepositoryPort = Depends(get_pipeline_run_repository),
    log_reader: PipelineLogReaderPort = Depends(get_pipeline_log_reader),
) -> str:
    try:
        row = repository.get_run(run_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    if not row:
        raise HTTPException(status_code=404, detail="Pipeline run not found")

    manifest = row.get("manifest") or {}
    log_path = manifest.get("pipeline_log") or str(
        Path(row["output_dir"]) / "pipeline.log"
    )
    return log_reader.read_text(log_path)


def _run_pipeline_request(
    payload: PipelineRunIn | ChatWikiRunIn,
    background_tasks: BackgroundTasks,
    use_case: RunPipelineUseCase,
    repository: PipelineRunRepositoryPort,
    source_reader: PipelineSourceReaderPort,
) -> PipelineRunOut:
    run_id = str(uuid.uuid4())
    out = Path(payload.out) if payload.out else Path("runs") / f"api_{run_id}"
    log_path = out / "pipeline.log"
    document = _load_document(payload.document_id, repository)
    user_id = str(document["user_id"])
    workspace_id = str(document["workspace_id"])
    input_name = payload.input_name or "inline.md"

    if isinstance(payload, ChatWikiRunIn):
        input_markdown, input_source, input_name = _resolve_chat_wiki_input(
            payload,
            document,
            user_id,
            workspace_id,
            repository,
            source_reader,
        )
    else:
        input_markdown, input_source, input_name = _load_stored_document_input(
            document,
            source_reader,
        )

    try:
        use_case.register(
            PipelineRunRegistration(
                run_id=run_id,
                document_id=payload.document_id,
                input_source=input_source,
                output_dir=str(out),
                mode=payload.mode,
            )
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    command = _build_pipeline_command(
        payload,
        run_id,
        input_markdown,
        input_name,
        out,
        log_path,
        payload.document_id,
        user_id,
        workspace_id,
        repository,
    )

    if not payload.wait:
        background_tasks.add_task(
            _execute_pipeline_run,
            run_id,
            command,
            use_case,
        )
        return PipelineRunOut(
            run_id=run_id,
            status="running",
            manifest=None,
            output_dir=str(out),
            log_path=str(log_path),
        )

    try:
        manifest = use_case.execute(run_id, command)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    return PipelineRunOut(
        run_id=run_id,
        status="succeeded",
        manifest=manifest,
        output_dir=str(out),
        log_path=str(log_path),
    )


def _build_pipeline_command(
    payload: PipelineRunIn | ChatWikiRunIn,
    run_id: str,
    input_markdown: str | None,
    input_name: str,
    out: Path,
    log_path: Path,
    source_document_id: str | None,
    user_id: str,
    workspace_id: str,
    repository: PipelineRunRepositoryPort,
) -> PipelineRunCommand:
    existing_concept_index = _load_existing_concept_index_for_run(
        user_id,
        workspace_id,
        repository,
    )
    existing_source_context = _load_existing_source_context_for_run(
        payload,
        user_id,
        workspace_id,
        repository,
    )
    return PipelineRunCommand(
        run_id=run_id,
        source_document_id=source_document_id,
        selection_mode=getattr(payload, "selection_mode", None),
        input=input_name,
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
        system_prompt=_semantic_prompt_for_run(payload, existing_source_context),
        concept_system_prompt=payload.concept_system_prompt,
        concept_resolution_system_prompt=payload.concept_resolution_system_prompt,
        section_polish_system_prompt=payload.section_polish_system_prompt,
        source_accumulation_system_prompt=payload.source_accumulation_system_prompt,
        wiki_evaluator_system_prompt=payload.wiki_evaluator_system_prompt,
        existing_wiki_dir=payload.existing_wiki_dir,
        existing_concept_index=existing_concept_index,
        existing_source_artifact=(existing_source_context or {}).get("artifact"),
        existing_source_markdown=(existing_source_context or {}).get(
            "source_markdown"
        ),
        wiki_evaluation_loop=payload.wiki_evaluation_loop,
        max_eval_attempts=payload.max_eval_attempts,
        save_debug_json=payload.save_debug_json,
        log_path=str(log_path),
        log_callback_url=payload.log_callback_url,
        user_id=user_id,
        workspace_id=workspace_id,
    )


def _load_existing_concept_index_for_run(
    user_id: str,
    workspace_id: str,
    repository: PipelineRunRepositoryPort,
) -> list[dict[str, Any]]:
    try:
        return repository.list_active_concept_index(user_id, workspace_id)
    except Exception:
        logger.exception("failed to load existing concept index for pipeline run")
        return []


def _load_existing_source_context_for_run(
    payload: PipelineRunIn | ChatWikiRunIn,
    user_id: str,
    workspace_id: str,
    repository: PipelineRunRepositoryPort,
) -> dict[str, Any] | None:
    if getattr(payload, "selection_mode", None) != "full" or not payload.document_id:
        return None
    try:
        return repository.latest_source_page_context(
            payload.document_id,
            user_id,
            workspace_id,
        )
    except Exception:
        logger.exception("failed to load existing source page context for pipeline run")
        return None


def _semantic_prompt_for_run(
    payload: PipelineRunIn | ChatWikiRunIn,
    existing_source_context: dict[str, Any] | None,
) -> str:
    selection_mode = getattr(payload, "selection_mode", None)
    if not selection_mode:
        return payload.system_prompt
    if selection_mode == "full" and existing_source_context:
        return payload.chat_append_system_prompt
    return payload.chat_system_prompt


def _validate_chat_inline_markdown(
    payload: ChatWikiRunIn,
    user_id: str,
    workspace_id: str,
    repository: PipelineRunRepositoryPort,
) -> None:
    if not payload.input_markdown:
        return
    if payload.selection_mode != "full":
        raise HTTPException(
            status_code=422,
            detail="input_markdown is only allowed for full chat accumulation",
        )
    try:
        existing_source_context = repository.latest_source_page_context(
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


def _safe_name(value: str) -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9_.-]+", "_", value).strip("._")
    return cleaned or "document"


def _load_document(
    document_id: str,
    repository: PipelineRunRepositoryPort,
) -> dict[str, Any]:
    try:
        document = repository.get_document(document_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    if not document:
        raise HTTPException(status_code=404, detail="Document not found")
    return document


def _load_document_markdown(
    document: dict[str, Any],
    source_reader: PipelineSourceReaderPort,
) -> tuple[str, str, str]:
    source_uri = document.get("source_uri")
    extracted_text_uri = document.get("extracted_text_uri")
    mime_type = (document.get("mime_type") or "").lower()

    if extracted_text_uri:
        object_uri = extracted_text_uri
    elif mime_type in {"text/markdown", "text/x-markdown", "text/plain"} or str(
        document.get("filename", "")
    ).lower().endswith(".md"):
        object_uri = source_uri
    else:
        raise HTTPException(
            status_code=409,
            detail="Document needs extracted_text_uri before pipeline processing. Convert the source file to Markdown/text first.",
        )

    if not object_uri:
        raise HTTPException(
            status_code=409,
            detail="Document has no source_uri or extracted_text_uri",
        )

    try:
        markdown = source_reader.read_text(str(object_uri))
    except Exception as exc:
        raise HTTPException(
            status_code=502,
            detail=f"Failed to read document object from storage: {exc}",
        ) from exc

    return (
        markdown,
        str(object_uri),
        str(document.get("filename") or f"{_safe_name(str(document['id']))}.md"),
    )


def _load_stored_document_input(
    document: dict[str, Any],
    source_reader: PipelineSourceReaderPort,
) -> tuple[str, str, str]:
    input_markdown, object_uri, input_name = _load_document_markdown(
        document,
        source_reader,
    )
    return input_markdown, f"storage:{object_uri}", input_name


def _resolve_chat_wiki_input(
    payload: ChatWikiRunIn,
    document: dict[str, Any],
    user_id: str,
    workspace_id: str,
    repository: PipelineRunRepositoryPort,
    source_reader: PipelineSourceReaderPort,
) -> tuple[str, str, str]:
    if payload.input_markdown:
        _validate_chat_inline_markdown(
            payload,
            user_id,
            workspace_id,
            repository,
        )
        input_name = payload.input_name or "chat.md"
        return payload.input_markdown, f"inline:{input_name}", input_name
    return _load_stored_document_input(document, source_reader)


def _execute_pipeline_run(
    run_id: str,
    command: PipelineRunCommand,
    use_case: RunPipelineUseCase,
) -> None:
    try:
        use_case.execute(run_id, command)
    except Exception as exc:
        logger.error("ERROR: pipeline run failed run_id=%s error=%s", run_id, exc)
