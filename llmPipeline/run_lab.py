#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shutil
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Any

from app.modules.wiki_generation.application.run_generation_loop import (
    EvaluationGuardRepairer,
    generation_evaluation_status,
)
from app.modules.wiki_generation.application.section_polish_mapping import (
    map_polish_output as _map_polish_output,
)
from app.modules.wiki_generation.application.judge_candidates import (
    judge_concept_update_candidates as _judge_concept_update_candidates,
    judge_meaning_cluster_candidates as _judge_meaning_cluster_candidates,
)
from app.modules.wiki_generation.domain.entities import SourceBlock, SourceDocument
from app.modules.wiki_generation.infrastructure.assemble import (
    ConceptPageAssembler,
    GeneratedConceptPageAssembler,
    LinkBuilder,
    MeaningClusterArtifactAssembler,
    SourcePageAssembler,
)
from app.modules.wiki_generation.infrastructure.extract import MarkdownBlockExtractor
from app.modules.wiki_generation.infrastructure.generation_loop_adapters import (
    EvaluationArtifactAdapter,
    GenerationEvaluatorAdapter,
    SemanticGenerationAdapter,
)
from app.modules.wiki_generation.infrastructure.wiki_generation_evaluator_graph import (
    LangGraphWikiGenerationEvaluator,
)
from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ApiConceptResolver,
    ApiConceptPageGenerator,
    ApiSectionPolisher,
    ApiSourceAccumulator,
    ChatClientConfig,
    ChatCompletionsJsonClient,
    SectionPolishParseError,
)
from app.modules.wiki_generation.infrastructure.chat_source_accumulation import (
    apply_chat_source_accumulation_result,
    build_chat_source_accumulation_payload,
)
from app.modules.wiki_generation.infrastructure.concept_resolution import (
    apply_concept_resolutions,
    load_existing_concept_index,
    normalize_hint_resolution_output,
    normalize_resolution_output,
)
from app.modules.wiki_generation.infrastructure.normalize import SemanticNormalizer
from app.modules.wiki_generation.infrastructure.packet import SemanticPacketBuilder
from app.modules.wiki_generation.infrastructure.pipeline_log import PipelineLog
from app.modules.wiki_generation.infrastructure.prompt_io import collect_concept_source_blocks
from app.modules.wiki_generation.infrastructure.source_context_merge import (
    apply_same_source_core_context,
    source_context_blocks,
    source_page_context_normalized,
)
from app.modules.wiki_ingestion.application.models import PipelineRunCommand
from app.modules.wiki_ingestion.infrastructure.file_io import ensure_dir, write_json, write_text
from app.modules.wiki_ingestion.infrastructure.object_storage import read_text_object


@dataclass(frozen=True)
class PipelinePrompts:
    semantic: str
    concept: str
    concept_resolution: str
    section_polish: str
    source_accumulation: str
    wiki_evaluator: str
    wiki_patch: str


@dataclass(frozen=True)
class WikiPageOutputs:
    source_page: dict[str, Any]
    source_page_normalized: dict[str, Any]
    concept_pages: list[dict[str, Any]]
    links: list[dict[str, Any]]
    source_page_mode: str
    concept_page_mode: str


@dataclass(frozen=True)
class _SourcePagePreparation:
    normalized: dict[str, Any]
    existing_context_blocks: list[SourceBlock]
    polish: dict[str, Any]
    key_points_for_concepts: list[dict[str, Any]]
    mode: str
    section_polisher: ApiSectionPolisher | None
    raw_polish_dir: Path | None
    invalid_polish_dir: Path


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="Fruition v0.9 API pipeline lab v6 (Upstage Solar Pro 2 default)")
    ap.add_argument("--input", required=True, help="Input Markdown file")
    ap.add_argument("--out", default="runs/latest", help="Output directory")
    ap.add_argument("--mode", choices=["api", "generic-chat"], default="api", help="api/generic-chat=OpenAI-compatible chat-completions")
    ap.add_argument("--provider", choices=["upstage", "generic"], default=os.environ.get("LLM_PROVIDER", "upstage"), help="API defaults preset. upstage defaults to Solar Pro 2.")
    ap.add_argument("--env-file", help="Optional .env file to load before resolving API settings")
    ap.add_argument(
        "--source-page-mode",
        choices=["auto", "skeleton", "section-polish"],
        default="auto",
        help="auto/section-polish: backend source page assembly with LLM-polished summary/key points; skeleton: backend only",
    )
    ap.add_argument(
        "--concept-page-mode",
        choices=["auto", "api", "full-llm", "skeleton", "section-polish"],
        default="auto",
        help="auto/skeleton: backend concept pages only; section-polish: optional LLM-polished concept sections; api/full-llm: legacy full concept page LLM writer",
    )
    ap.add_argument("--max-packet-chars", type=int, default=7000)
    ap.add_argument("--overlap-blocks", type=int, default=1)

    # OpenAI-compatible chat-completions API options. Upstage Solar Pro 2 is the default provider preset.
    ap.add_argument("--endpoint", help="Full chat-completions endpoint. If omitted, uses {api_base_url}/chat/completions")
    ap.add_argument("--api-base-url", help="Base URL for OpenAI-compatible APIs. Upstage default: https://api.upstage.ai/v1")
    ap.add_argument("--api-key-env", help="API key environment variable. Upstage default: UPSTAGE_API_KEY")
    ap.add_argument("--api-key", help="API key value. Prefer env var for safety")
    ap.add_argument("--model", help="Model name. Upstage default: solar-pro2. Override with UPSTAGE_MODEL or --model")
    ap.add_argument("--temperature", type=float, default=0.2)
    ap.add_argument("--timeout-seconds", type=int, default=180)
    ap.add_argument("--max-tokens", type=int, default=None)
    ap.add_argument("--json-mode", action="store_true", help="Send response_format={type: json_object}; disable if your provider rejects it")
    ap.add_argument("--log-path", help="Pipeline progress log path. Default: {out}/pipeline.log")
    ap.add_argument("--log-callback-url", help="Optional URL to POST each Korean pipeline log event to")
    ap.add_argument("--save-debug-json", action="store_true", help="Save intermediate/debug JSON such as raw LLM outputs, document.json, block_map.json, and api_config.json")
    ap.add_argument("--user-id", default=os.environ.get("WIKI_USER_ID", "local-user"), help="Wiki artifact owner namespace")
    ap.add_argument("--workspace-id", default=os.environ.get("WIKI_WORKSPACE_ID", "local-workspace"), help="Wiki artifact workspace namespace")

    ap.add_argument("--system-prompt", default="prompts/semantic_extraction.system.md")
    ap.add_argument("--concept-system-prompt", default="prompts/concept_page_generation.system.md")
    ap.add_argument("--concept-resolution-system-prompt", default="prompts/concept_resolution.system.md")
    ap.add_argument("--section-polish-system-prompt", default="prompts/section_polish.system.md")
    ap.add_argument("--source-accumulation-system-prompt", default="prompts/source_accumulation_evaluator.system.md")
    ap.add_argument("--wiki-evaluator-system-prompt", default="prompts/wiki_generation_evaluator.system.md")
    ap.add_argument("--wiki-patch-system-prompt", default="prompts/wiki_generation_patch.system.md")
    ap.add_argument("--existing-wiki-dir", help="Optional existing wiki directory. If set, existing wiki/concepts/*.md pages are used for concept resolution before page generation.")
    ap.add_argument(
        "--wiki-evaluation-loop",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Evaluate normalized wiki generation and retry semantic extraction with evaluator feedback when needed",
    )
    ap.add_argument("--max-eval-attempts", type=int, default=2)
    return ap.parse_args()




def load_env_file(path_like: str | None) -> None:
    """Tiny .env loader; avoids python-dotenv dependency. Existing env wins."""
    if not path_like:
        return
    env_path = Path(path_like)
    if not env_path.exists():
        raise SystemExit(f".env file not found: {path_like}")
    for raw in env_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def resolve_api_defaults(command: PipelineRunCommand) -> PipelineRunCommand:
    """Resolve provider-specific API defaults after optional .env loading."""
    if command.provider == "upstage":
        api_base_url = (
            command.api_base_url
            or os.environ.get("UPSTAGE_BASE_URL")
            or os.environ.get("LLM_BASE_URL")
            or "https://api.upstage.ai/v1"
        )
        api_key_env = command.api_key_env or "UPSTAGE_API_KEY"
        model = (
            command.model
            or os.environ.get("UPSTAGE_MODEL")
            or os.environ.get("LLM_MODEL")
            or "solar-pro2"
        )
    else:
        api_base_url = (
            command.api_base_url
            or os.environ.get("LLM_BASE_URL")
            or "https://api.openai.com/v1"
        )
        api_key_env = command.api_key_env or "LLM_API_KEY"
        model = command.model or os.environ.get("LLM_MODEL")
    return replace(
        command,
        api_base_url=api_base_url,
        api_key_env=api_key_env,
        model=model,
    )


def read_prompt(path_like: str) -> str:
    prompt_path = Path(path_like)
    if not prompt_path.exists():
        prompt_path = Path(__file__).parent / path_like
    if not prompt_path.exists():
        raise SystemExit(f"Prompt not found: {path_like}")
    return prompt_path.read_text(encoding="utf-8")


def resolve_endpoint(args: PipelineRunCommand) -> str:
    if args.endpoint:
        return args.endpoint
    return args.api_base_url.rstrip("/") + "/chat/completions"


def load_api_client(args: PipelineRunCommand) -> ChatCompletionsJsonClient:
    api_key = args.api_key or os.environ.get(args.api_key_env)
    if not api_key:
        raise SystemExit(f"Missing API key. Set {args.api_key_env}=... or pass --api-key")
    if not args.model:
        raise SystemExit("Missing model. Pass --model or set LLM_MODEL")
    return ChatCompletionsJsonClient(
        ChatClientConfig(
            endpoint=resolve_endpoint(args),
            api_key=api_key,
            model=args.model,
            temperature=args.temperature,
            timeout_seconds=args.timeout_seconds,
            max_tokens=args.max_tokens,
            json_mode=args.json_mode,
        )
    )


def concept_page_mode(args: PipelineRunCommand) -> str:
    if args.concept_page_mode != "auto":
        return args.concept_page_mode
    return "skeleton"


def source_page_mode(args: PipelineRunCommand) -> str:
    if getattr(args, "source_page_mode", "auto") != "auto":
        return args.source_page_mode
    return "section-polish" if args.mode in {"api", "generic-chat"} else "skeleton"


def _json_safe(value: Any) -> Any:
    try:
        json.dumps(value)
        return value
    except TypeError:
        pass
    if hasattr(value, "__dataclass_fields__"):
        return _json_safe(asdict(value))
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_json_safe(item) for item in value]
    if isinstance(value, tuple):
        return [_json_safe(item) for item in value]
    return str(value)


def _read_existing_active_clusters(user_id: str, workspace_id: str) -> str:
    object_name = f"wiki/{user_id}/{workspace_id}/clusters/active.md"
    try:
        return read_text_object(object_name)
    except Exception:
        return ""



def _run_wiki_generation_loop(
    *,
    api_client: ChatCompletionsJsonClient,
    semantic_system_prompt: str,
    wiki_evaluator_system_prompt: str,
    packets: list[Any],
    raw_dir: Path | None,
    log: PipelineLog,
    normalizer: SemanticNormalizer,
    document: Any,
    blocks: list[Any],
    out: Path,
    save_debug_json: bool,
    wiki_evaluation_loop: bool,
    max_eval_attempts: int,
    source_context: dict[str, Any] | None = None,
    wiki_patch_system_prompt: str = "",
) -> tuple[list[dict[str, Any]], dict[str, Any], list[dict[str, Any]]]:
    return LangGraphWikiGenerationEvaluator(
        semantic_generation=SemanticGenerationAdapter(
            api_client,
            packets,
            raw_dir,
            log,
            blocks,
            wiki_patch_system_prompt,
        ),
        normalizer=normalizer,
        evaluator=GenerationEvaluatorAdapter(api_client, wiki_evaluator_system_prompt, document, blocks),
        repairer=EvaluationGuardRepairer(),
        events=log,
        evaluation_artifacts=EvaluationArtifactAdapter(out, save_debug_json),
        source_block_ids=[block.block_id for block in blocks],
    ).run(
        semantic_system_prompt=semantic_system_prompt,
        source_context=source_context,
        evaluation_enabled=wiki_evaluation_loop,
        max_attempts=max_eval_attempts,
    )


def _prepare_source_page_polish(
    args: PipelineRunCommand,
    normalized: dict[str, Any],
    blocks: list[Any],
    section_polisher: ApiSectionPolisher | None,
    raw_polish_dir: Path | None,
    invalid_polish_dir: Path,
    log: PipelineLog,
) -> tuple[dict[str, Any], list[dict[str, Any]], str]:
    source_polish: dict[str, Any] = {}
    raw_source_key_points_for_concepts: list[dict[str, Any]] = [
        kp
        for note in normalized.get("semantic_notes", [])
        for kp in note.get("key_points", [])
    ]
    source_key_points_for_concepts = list(raw_source_key_points_for_concepts)
    sp_mode = source_page_mode(args)
    if sp_mode != "section-polish":
        return source_polish, source_key_points_for_concepts, sp_mode

    assert section_polisher is not None
    source_payload = {
        "page_type": "source",
        "section": "source_summary_and_key_points",
        "context": {
            "document": normalized["document"],
            "concept_slugs": [concept["slug"] for concept in normalized["concept_ledger"]],
            "existing_source_summary": normalized.get("existing_source_context", {}).get("summary", ""),
            "existing_source_markdown": normalized.get("existing_source_context", {}).get("source_markdown", ""),
            "summary_instruction": "기존 source page 문맥과 새 SOURCE BLOCKS를 함께 반영해 전체 source page 요약을 새로 작성한다. 기존 요약 뒤에 새 요약을 붙이는 append 형식으로 쓰지 않는다.",
        },
        "draft": {
            "new_summary_candidates": [n.get("semantic_summary", "") for n in normalized["semantic_notes"] if n.get("semantic_summary")],
            "key_points": [kp for note in normalized["semantic_notes"] for kp in note.get("key_points", [])],
        },
        "evidence": normalized["evidence_units"],
    }
    try:
        raw_source_polish = section_polisher.polish(source_payload, blocks)
    except SectionPolishParseError as exc:
        invalid_path = invalid_polish_dir / "source_page.txt"
        if args.save_debug_json:
            ensure_dir(invalid_polish_dir)
            write_text(invalid_path, exc.raw_content)
        normalized.setdefault("warnings", []).append("source_page: section polish output was not repairable; used backend skeleton")
        log.emit(
            "5-보조. Source Section Polish",
            "Source page section polish가 복구 불가능해 backend skeleton으로 대체했습니다.",
            {"invalid_raw": invalid_path if args.save_debug_json else "not_saved"},
        )
        return source_polish, source_key_points_for_concepts, sp_mode

    if raw_polish_dir is not None:
        write_json(raw_polish_dir / "source_page.json", raw_source_polish)
    mapped_source_polish = _map_polish_output(raw_source_polish, blocks, normalized.setdefault("warnings", []), "source_page")
    source_polish = {
        "title": mapped_source_polish.get("title"),
        "summary": mapped_source_polish,
        "key_points": mapped_source_polish,
    }
    source_key_points_for_concepts = [
        *mapped_source_polish.get("items", []),
        *raw_source_key_points_for_concepts,
    ]
    log.emit(
        "5-보조. Source Section Polish",
        "Source page의 summary/key points 섹션만 LLM으로 다듬었습니다.",
        {"confidence": mapped_source_polish.get("confidence"), "항목 수": len(mapped_source_polish.get("items", []))},
    )
    return source_polish, source_key_points_for_concepts, sp_mode


def _evaluate_source_accumulation(
    *,
    source_accumulator: ApiSourceAccumulator,
    normalized: dict[str, Any],
    source_blocks: list[Any],
    existing_source_markdown: str | None,
    raw_dir: Path | None,
    log: PipelineLog,
) -> dict[str, Any]:
    payload = build_chat_source_accumulation_payload(normalized, existing_source_markdown)
    raw = source_accumulator.evaluate(payload, source_blocks)
    if raw_dir is not None:
        write_json(raw_dir / "source_accumulation.json", raw)
    result = apply_chat_source_accumulation_result(normalized, raw, source_blocks)
    log.emit(
        "5-보조. Source 누적 평가",
        "기존 source page와 새 대화를 함께 평가해 summary/key points/observations/categories 누적 결과를 보정했습니다.",
        {
            "passed": raw.get("passed"),
            "issue 수": len(raw.get("issues", []) or []),
            "key point 수": len(result.get("source_accumulation_polish", {}).get("key_points", {}).get("items", [])),
        },
    )
    return result


def _prepare_concept_section_polish(
    args: PipelineRunCommand,
    normalized: dict[str, Any],
    concept_source_blocks_by_slug: dict[str, list[Any]],
    source_key_points_for_concepts: list[dict[str, Any]],
    section_polisher: ApiSectionPolisher,
    raw_polish_dir: Path | None,
    invalid_polish_dir: Path,
    log: PipelineLog,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    concept_polish_by_slug: dict[str, Any] = {}
    generated_concept_pages: list[dict[str, Any]] = []

    for concept in normalized["concept_ledger"]:
        source_blocks = concept_source_blocks_by_slug.get(concept["slug"], [])
        related_evidence = [ev for ev in normalized["evidence_units"] if concept["slug"] in ev.get("related_concept_slugs", [])]
        resolution_links = [
            target
            for resolution in normalized.get("concept_resolutions", [])
            if (resolution.get("canonical_slug") or resolution.get("incoming_slug")) == concept["slug"]
            for target in resolution.get("link_targets", [])
        ]
        payload = {
            "page_type": "concept",
            "section": "concept_definition_key_points_and_related",
            "context": {
                "title": concept.get("title"),
                "slug": concept.get("slug"),
                "aliases": concept.get("aliases", []),
                "why_page_worthy": concept.get("why_page_worthy"),
                "resolution_link_targets": resolution_links,
            },
            "draft": {
                "definition": concept.get("definition"),
            },
            "evidence": related_evidence,
        }
        try:
            raw_polish = section_polisher.polish(payload, source_blocks)
        except SectionPolishParseError as exc:
            invalid_path = invalid_polish_dir / f"concept_{concept['slug']}.txt"
            if args.save_debug_json:
                ensure_dir(invalid_polish_dir)
                write_text(invalid_path, exc.raw_content)
            normalized.setdefault("warnings", []).append(f"concept:{concept['slug']}: section polish output was not repairable; used backend skeleton")
            log.emit(
                "6-보조. Concept Section Polish",
                "Concept section polish가 복구 불가능해 해당 concept은 backend skeleton으로 대체했습니다.",
                {"개념": concept["slug"], "invalid_raw": invalid_path if args.save_debug_json else "not_saved"},
            )
            continue

        if raw_polish_dir is not None:
            write_json(raw_polish_dir / f"concept_{concept['slug']}.json", raw_polish)
        mapped = _map_polish_output(raw_polish, source_blocks, normalized.setdefault("warnings", []), f"concept:{concept['slug']}")
        concept_polish_by_slug[concept["slug"]] = {
            "definition": mapped,
            "key_points": mapped,
            "related_concept_hints": mapped.get("related_concept_hints", []),
        }
        generated_concept_pages.append(
            {
                "slug": concept["slug"],
                "title": concept.get("title"),
                "confidence": mapped.get("confidence"),
                "related_concept_hints": mapped.get("related_concept_hints", []),
            }
        )
        log.emit(
            "6-보조. Concept Section Polish",
            "Concept page의 definition/key points/related hint 섹션만 LLM으로 다듬었습니다.",
            {"개념": concept["slug"], "근거 블록 수": len(source_blocks), "confidence": mapped.get("confidence")},
        )

    concept_pages = ConceptPageAssembler().build_top(
        normalized,
        top_n=None,
        polish_by_slug=concept_polish_by_slug,
        source_key_points=source_key_points_for_concepts,
    )
    log.emit("6. Concept Page 생성", "백엔드 조립과 섹션 polish로 concept page markdown 데이터를 생성했습니다.", {"페이지 수": len(concept_pages)})
    return concept_pages, generated_concept_pages


def _load_pipeline_prompts(args: PipelineRunCommand, log: PipelineLog) -> PipelinePrompts:
    wiki_patch_path = getattr(
        args,
        "wiki_patch_system_prompt",
        "prompts/wiki_generation_patch.system.md",
    )
    prompts = PipelinePrompts(
        semantic=read_prompt(args.system_prompt),
        concept=read_prompt(args.concept_system_prompt),
        concept_resolution=read_prompt(args.concept_resolution_system_prompt),
        section_polish=read_prompt(args.section_polish_system_prompt),
        source_accumulation=read_prompt(args.source_accumulation_system_prompt),
        wiki_evaluator=read_prompt(args.wiki_evaluator_system_prompt),
        wiki_patch=read_prompt(wiki_patch_path),
    )
    log.emit(
        "프롬프트 로드",
        "시스템 프롬프트를 메모리에 로드했습니다.",
        {
            "semantic": args.system_prompt,
            "concept": args.concept_system_prompt,
            "concept_resolution": args.concept_resolution_system_prompt,
            "section_polish": args.section_polish_system_prompt,
            "source_accumulation": args.source_accumulation_system_prompt,
            "wiki_evaluator": args.wiki_evaluator_system_prompt,
            "wiki_patch": wiki_patch_path,
        },
    )
    return prompts


def _prepare_api_client(
    args: PipelineRunCommand,
    out: Path,
    log: PipelineLog,
) -> ChatCompletionsJsonClient | None:
    requires_api = (
        args.mode in {"api", "generic-chat"}
        or concept_page_mode(args) in {"api", "full-llm", "section-polish"}
        or source_page_mode(args) == "section-polish"
    )
    if not requires_api:
        return None

    api_client = load_api_client(args)
    if args.save_debug_json:
        write_json(
            out / "api_config.json",
            {
                "provider": args.provider,
                "endpoint": resolve_endpoint(args),
                "api_base_url": args.api_base_url,
                "api_key_source": "--api-key" if args.api_key else args.api_key_env,
                "model": args.model,
                "temperature": args.temperature,
                "timeout_seconds": args.timeout_seconds,
                "max_tokens": args.max_tokens,
                "json_mode": args.json_mode,
                "secret_values_saved": False,
            },
        )
    log.emit(
        "API 설정",
        "LLM API 클라이언트를 준비했습니다.",
        {
            "provider": args.provider,
            "model": args.model,
            "endpoint": resolve_endpoint(args),
        },
    )
    return api_client


def _extract_pipeline_source(
    args: PipelineRunCommand,
    *,
    input_text: str | None,
    input_source_name: str,
    input_path: Path,
    out: Path,
    log: PipelineLog,
) -> tuple[SourceDocument, list[SourceBlock], list[dict[str, str]]]:
    extractor = MarkdownBlockExtractor()
    preserve_prefixed_refs = bool(getattr(args, "selection_mode", None))
    if input_text is not None:
        document, blocks = extractor.extract_text(
            input_text,
            source_path=input_source_name,
            fallback_title=Path(input_source_name).stem,
            preserve_prefixed_refs=preserve_prefixed_refs,
        )
    else:
        document, blocks = extractor.extract(input_path)

    source_document_id = getattr(args, "source_document_id", None)
    if source_document_id:
        document.document_id = source_document_id
        for block in blocks:
            block.document_id = source_document_id
    if args.save_debug_json:
        write_json(out / "document.json", asdict(document))
        write_json(out / "block_map.json", {block.block_id: block.source_reference_id for block in blocks})

    source_block_records = [
        {
            "document_id": block.document_id,
            "block_id": block.block_id,
            "text": block.text,
        }
        for block in blocks
    ]
    log.emit(
        "1. 블록 추출",
        "Markdown 원문을 블록 객체로 변환했고, 이 블록 목록을 다음 단계 입력으로 전달합니다.",
        {
            "문서 ID": document.document_id,
            "문서 제목": document.title,
            "블록 수": len(blocks),
        },
    )
    return document, blocks, source_block_records


def _resolve_pipeline_concepts(
    args: PipelineRunCommand,
    *,
    api_client: ChatCompletionsJsonClient,
    concept_resolution_prompt: str,
    normalized: dict[str, Any],
    existing_source_artifact: dict[str, Any] | None,
    out: Path,
    log: PipelineLog,
) -> tuple[dict[str, Any], list[SourceBlock]]:
    existing_concepts = getattr(args, "existing_concept_index", None)
    if existing_concepts is None:
        existing_concepts = load_existing_concept_index(
            getattr(args, "existing_wiki_dir", None)
        )
    missing_related_hints = normalized.get("missing_related_concept_hints", [])
    raw_resolution = ApiConceptResolver(
        api_client,
        concept_resolution_prompt,
    ).resolve(
        normalized["concept_ledger"],
        existing_concepts,
        missing_related_hints,
    )
    if args.save_debug_json:
        write_json(
            ensure_dir(out / "raw_llm_outputs") / "concept_resolution.json",
            raw_resolution,
        )
    resolutions = normalize_resolution_output(
        raw_resolution,
        normalized["concept_ledger"],
        existing_concepts,
        normalized.setdefault("warnings", []),
    )
    hint_resolutions = normalize_hint_resolution_output(
        raw_resolution,
        missing_related_hints,
        normalized["concept_ledger"],
        existing_concepts,
        normalized.setdefault("warnings", []),
    )
    resolved = apply_concept_resolutions(
        normalized,
        resolutions,
        existing_concepts,
        hint_resolutions,
    )
    same_source_context_blocks: list[SourceBlock] = []
    if getattr(args, "selection_mode", None) == "full" and existing_source_artifact:
        resolved, same_source_context_blocks = apply_same_source_core_context(
            resolved,
            existing_source_artifact,
        )
    log.emit(
        "4-보조. Concept Resolution",
        "새 concept 후보끼리와 기존 concept page index, missing related hint를 비교해 canonical slug와 관련 링크를 확정했습니다.",
        {
            "기존 개념 수": len(existing_concepts),
            "기존 source context": bool(existing_source_artifact),
            "같은 source context 누적 수": len(resolved.get("same_source_context_merges", [])),
            "해결 전 개념 수": len(resolutions),
            "해결 후 개념 수": len(resolved["concept_ledger"]),
            "missing hint 수": len(missing_related_hints),
            "hint 해결 수": sum(
                1
                for item in hint_resolutions
                if item.get("decision") not in {"unresolved", "promote_new_concept"}
            ),
            "병합 수": sum(
                1 for item in resolutions if item.get("decision") == "merge_into"
            ),
            "링크 판단 수": sum(1 for item in resolutions if item.get("link_targets")),
        },
    )
    return resolved, same_source_context_blocks


def _prepare_source_page_assembly(
    args: PipelineRunCommand,
    *,
    api_client: ChatCompletionsJsonClient | None,
    prompts: PipelinePrompts,
    normalized: dict[str, Any],
    blocks: list[SourceBlock],
    existing_source_artifact: dict[str, Any] | None,
    existing_source_markdown: str | None,
    out: Path,
    log: PipelineLog,
) -> _SourcePagePreparation:
    existing_source_artifact_with_markdown = (
        {**existing_source_artifact, "source_markdown": existing_source_markdown}
        if existing_source_artifact
        else None
    )
    source_page_normalized = (
        source_page_context_normalized(normalized, existing_source_artifact_with_markdown)
        if getattr(args, "selection_mode", None) == "full"
        and existing_source_artifact_with_markdown
        else normalized
    )
    existing_source_context_blocks = source_context_blocks(
        existing_source_artifact_with_markdown
    )
    section_polisher = (
        ApiSectionPolisher(api_client, prompts.section_polish)
        if api_client is not None
        else None
    )
    source_accumulator = (
        ApiSourceAccumulator(api_client, prompts.source_accumulation)
        if api_client is not None
        else None
    )
    raw_polish_dir = (
        ensure_dir(out / "raw_llm_outputs" / "section_polish")
        if args.save_debug_json
        else None
    )
    invalid_polish_dir = out / "raw_llm_outputs" / "section_polish_invalid"
    source_polish = {}
    source_key_points_for_concepts = [
        key_point
        for note in source_page_normalized.get("semantic_notes", [])
        for key_point in note.get("key_points", [])
    ]
    source_mode = source_page_mode(args)
    if (
        getattr(args, "selection_mode", None) == "full"
        and existing_source_artifact_with_markdown
        and source_accumulator is not None
    ):
        source_page_normalized = _evaluate_source_accumulation(
            source_accumulator=source_accumulator,
            normalized=source_page_normalized,
            source_blocks=[*existing_source_context_blocks, *blocks],
            existing_source_markdown=existing_source_markdown,
            raw_dir=raw_polish_dir,
            log=log,
        )
        source_polish = source_page_normalized.get("source_accumulation_polish", {})
        source_key_points_for_concepts = [
            *source_polish.get("key_points", {}).get("items", []),
            *source_key_points_for_concepts,
        ]
        source_mode = "source-accumulation"
    else:
        source_polish, source_key_points_for_concepts, source_mode = (
            _prepare_source_page_polish(
                args,
                source_page_normalized,
                [*existing_source_context_blocks, *blocks],
                section_polisher,
                raw_polish_dir,
                invalid_polish_dir,
                log,
            )
        )

    return _SourcePagePreparation(
        normalized=source_page_normalized,
        existing_context_blocks=existing_source_context_blocks,
        polish=source_polish,
        key_points_for_concepts=source_key_points_for_concepts,
        mode=source_mode,
        section_polisher=section_polisher,
        raw_polish_dir=raw_polish_dir,
        invalid_polish_dir=invalid_polish_dir,
    )


def _prepare_concept_source_blocks(
    normalized: dict[str, Any],
    *,
    existing_source_context_blocks: list[SourceBlock],
    blocks: list[SourceBlock],
    same_source_context_blocks: list[SourceBlock],
    source_key_points_for_concepts: list[dict[str, Any]],
    log: PipelineLog,
) -> dict[str, list[SourceBlock]]:
    concept_source_blocks_by_slug: dict[str, list[SourceBlock]] = {}
    concept_input_blocks = [
        *existing_source_context_blocks,
        *blocks,
        *same_source_context_blocks,
    ]
    for concept in normalized["concept_ledger"]:
        concept_blocks = collect_concept_source_blocks(
            concept,
            normalized["evidence_units"],
            concept_input_blocks,
            source_key_points=source_key_points_for_concepts,
        )
        concept_source_blocks_by_slug[concept["slug"]] = concept_blocks
    log.emit(
        "5-보조. Concept 입력 준비",
        "Source 누적 평가 결과를 반영해 전체 개념별 source block을 메모리에 모았습니다.",
        {"대상 개념 수": len(concept_source_blocks_by_slug)},
    )
    return concept_source_blocks_by_slug


def _assemble_source_page(
    preparation: _SourcePagePreparation,
    *,
    log: PipelineLog,
) -> dict[str, Any]:
    source_page = SourcePageAssembler().build(
        preparation.normalized,
        polish=preparation.polish,
    )
    log.emit(
        "5. Source Page 생성",
        "백엔드 조립 방식으로 source page markdown 데이터를 생성했습니다.",
        {
            "source_page": source_page.get("markdown_path"),
            "source_json": bool(
                preparation.normalized.get("source_extraction_artifact")
            ),
            "mode": preparation.mode,
        },
    )
    return source_page


def _assemble_concept_pages(
    args: PipelineRunCommand,
    *,
    api_client: ChatCompletionsJsonClient | None,
    prompts: PipelinePrompts,
    normalized: dict[str, Any],
    concept_source_blocks_by_slug: dict[str, list[SourceBlock]],
    source_key_points_for_concepts: list[dict[str, Any]],
    section_polisher: ApiSectionPolisher | None,
    raw_polish_dir: Path | None,
    invalid_polish_dir: Path,
    out: Path,
    log: PipelineLog,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], str]:
    generated_concept_pages = []
    raw_concept_dir = (
        ensure_dir(out / "raw_llm_outputs" / "concept_page_generation")
        if args.save_debug_json
        else None
    )
    concept_mode = concept_page_mode(args)
    if concept_mode == "section-polish":
        assert section_polisher is not None
        concept_pages, generated_concept_pages = _prepare_concept_section_polish(
            args,
            normalized,
            concept_source_blocks_by_slug,
            source_key_points_for_concepts,
            section_polisher,
            raw_polish_dir,
            invalid_polish_dir,
            log,
        )
    elif concept_mode in {"api", "full-llm"}:
        assert api_client is not None
        concept_generator = ApiConceptPageGenerator(api_client, prompts.concept)
        generator_assembler = GeneratedConceptPageAssembler()
        for concept in normalized["concept_ledger"]:
            concept_blocks = concept_source_blocks_by_slug.get(concept["slug"], [])
            raw_page = concept_generator.generate(
                concept,
                normalized["evidence_units"],
                concept_blocks,
            )
            if raw_concept_dir is not None:
                write_json(raw_concept_dir / f"{concept['slug']}.json", raw_page)
            generated_page = generator_assembler.normalize_generated_output(
                concept,
                raw_page,
                concept_blocks,
                normalized.setdefault("warnings", []),
            )
            generated_concept_pages.append(generated_page)
            log.emit(
                "6. Concept Page LLM 생성",
                "LLM concept page 출력을 backend 형식으로 정규화했습니다.",
                {
                    "개념": concept["slug"],
                    "근거 블록 수": len(concept_blocks),
                    "confidence": generated_page.get("confidence"),
                },
            )
        concept_pages = generator_assembler.build_pages(generated_concept_pages)
    else:
        concept_pages = ConceptPageAssembler().build_top(
            normalized,
            top_n=None,
            source_key_points=source_key_points_for_concepts,
        )
        log.emit(
            "6. Concept Page 생성",
            "Backend skeleton 방식으로 concept page markdown 데이터를 생성했습니다.",
            {"페이지 수": len(concept_pages)},
        )
    return concept_pages, generated_concept_pages, concept_mode


def _assemble_wiki_pages(
    args: PipelineRunCommand,
    *,
    api_client: ChatCompletionsJsonClient | None,
    prompts: PipelinePrompts,
    normalized: dict[str, Any],
    blocks: list[SourceBlock],
    same_source_context_blocks: list[SourceBlock],
    existing_source_artifact: dict[str, Any] | None,
    existing_source_markdown: str | None,
    out: Path,
    log: PipelineLog,
) -> WikiPageOutputs:
    source_preparation = _prepare_source_page_assembly(
        args,
        api_client=api_client,
        prompts=prompts,
        normalized=normalized,
        blocks=blocks,
        existing_source_artifact=existing_source_artifact,
        existing_source_markdown=existing_source_markdown,
        out=out,
        log=log,
    )
    concept_source_blocks_by_slug = _prepare_concept_source_blocks(
        normalized,
        existing_source_context_blocks=source_preparation.existing_context_blocks,
        blocks=blocks,
        same_source_context_blocks=same_source_context_blocks,
        source_key_points_for_concepts=source_preparation.key_points_for_concepts,
        log=log,
    )
    source_page = _assemble_source_page(source_preparation, log=log)
    concept_pages, generated_concept_pages, concept_mode = _assemble_concept_pages(
        args,
        api_client=api_client,
        prompts=prompts,
        normalized=normalized,
        concept_source_blocks_by_slug=concept_source_blocks_by_slug,
        source_key_points_for_concepts=source_preparation.key_points_for_concepts,
        section_polisher=source_preparation.section_polisher,
        raw_polish_dir=source_preparation.raw_polish_dir,
        invalid_polish_dir=source_preparation.invalid_polish_dir,
        out=out,
        log=log,
    )

    links = LinkBuilder().build(
        normalized,
        generated_concept_pages=generated_concept_pages,
    )
    log.emit(
        "7. 링크 생성",
        "위키 링크 데이터를 생성했습니다.",
        {"링크 수": len(links)},
    )
    return WikiPageOutputs(
        source_page=source_page,
        source_page_normalized=source_preparation.normalized,
        concept_pages=concept_pages,
        links=links,
        source_page_mode=source_preparation.mode,
        concept_page_mode=concept_mode,
    )


def _assemble_meaning_clusters(
    args: PipelineRunCommand,
    *,
    api_client: ChatCompletionsJsonClient,
    normalized: dict[str, Any],
    out: Path,
    log: PipelineLog,
) -> tuple[dict[str, Any], dict[str, Any]]:
    assembler = MeaningClusterArtifactAssembler()
    candidates = assembler.candidate_claims(normalized)
    concept_candidates = [
        *normalized.get("concept_ledger", []),
        *normalized.get("existing_concept_index", []),
    ]
    concept_update_decisions = _judge_concept_update_candidates(
        completion=api_client,
        concepts=concept_candidates,
        candidates=candidates,
    )
    concept_update_by_candidate = {
        item["candidate_id"]: item
        for item in concept_update_decisions
        if item.get("decision") == "same_concept"
    }
    candidates_by_id = {
        candidate["candidate_id"]: candidate for candidate in candidates
    }
    core_relation_decisions = [
        item
        for item in concept_update_decisions
        if item.get("decision") == "relation_candidate"
    ]
    cluster_judge_candidates = [
        item
        for item in candidates
        if item["candidate_id"] not in concept_update_by_candidate
    ]
    log.emit(
        "8-보조. Concept 갱신 후보 판단",
        "section/mention evidence claim이 이미 존재하는 core concept에 속하는지 먼저 판단했습니다.",
        {
            "candidate 수": len(candidates),
            "same_concept 수": len(concept_update_by_candidate),
            "relation_candidate 수": len(core_relation_decisions),
            "cluster judge 대상": len(cluster_judge_candidates),
        },
    )
    existing_active_clusters = _read_existing_active_clusters(
        args.user_id,
        args.workspace_id,
    )
    cluster_decisions = _judge_meaning_cluster_candidates(
        completion=api_client,
        existing_active_markdown=existing_active_clusters,
        candidates=cluster_judge_candidates,
    )
    log.emit(
        "8-보조. Meaning Cluster 판단",
        "section/mention evidence claim을 기존 active cluster와 비교해 생성 또는 갱신 대상을 판단했습니다.",
        {
            "candidate 수": len(cluster_judge_candidates),
            "decision 수": len(cluster_decisions),
            "기존 active 크기": len(existing_active_clusters),
        },
    )
    artifact = assembler.assemble(
        normalized,
        out,
        user_id=args.user_id,
        workspace_id=args.workspace_id,
        cluster_decisions=cluster_decisions,
        core_relation_decisions=core_relation_decisions,
        concept_update_decisions=[
            {
                **item,
                "claim_id": candidates_by_id.get(item["candidate_id"], {}).get(
                    "claim_id"
                ),
                "claim": candidates_by_id.get(item["candidate_id"], {}).get("claim"),
                "refs": candidates_by_id.get(item["candidate_id"], {}).get("refs", []),
                "candidate_type": candidates_by_id.get(item["candidate_id"], {}).get(
                    "candidate_type"
                ),
            }
            for item in concept_update_by_candidate.values()
        ],
    )
    maintenance_summary = artifact.get("maintenance_summary", {})
    log.emit(
        "8. Meaning Cluster 생성",
        "section/mention evidence claim 기반 active cluster와 ingest log artifact를 생성했습니다.",
        {
            "active": artifact["active_path"],
            "log": artifact["log_path"],
            "cluster 수": len(artifact["clusters"]),
            "promotion 후보 수": maintenance_summary.get(
                "promotion_candidate_count",
                0,
            ),
            "relation 후보 수": maintenance_summary.get("relation_candidate_count", 0),
            "invalid 후보 수": maintenance_summary.get("invalid_candidate_count", 0),
        },
    )
    return artifact, maintenance_summary


def run_pipeline(command: PipelineRunCommand) -> dict:
    load_env_file(command.env_file)
    args = resolve_api_defaults(command)
    input_text = getattr(args, "input_markdown", None)
    input_source_name = getattr(args, "input_name", None) or getattr(args, "input", None) or "inline.md"
    input_path = Path(args.input) if getattr(args, "input", None) else Path(input_source_name)
    out = Path(args.out)
    if out.exists():
        shutil.rmtree(out)
    ensure_dir(out)
    log = PipelineLog(
        getattr(args, "log_path", None) or out / "pipeline.log",
        callback_url=getattr(args, "log_callback_url", None),
        run_id=getattr(args, "run_id", None),
    )
    log.emit(
        "시작",
        "파이프라인 실행을 시작했습니다.",
        {
            "입력": input_source_name if input_text is not None else input_path,
            "출력 폴더": out,
            "실행 모드": args.mode,
            "선택 모드": getattr(args, "selection_mode", None),
            "Source Page 모드": source_page_mode(args),
            "Concept Page 모드": concept_page_mode(args),
        },
    )

    prompts = _load_pipeline_prompts(args, log)
    api_client = _prepare_api_client(args, out, log)
    document, blocks, source_block_records = _extract_pipeline_source(
        args,
        input_text=input_text,
        input_source_name=input_source_name,
        input_path=input_path,
        out=out,
        log=log,
    )

    # 2. Build LLM packets with short [B0001] anchors only.
    packet_builder = SemanticPacketBuilder(args.max_packet_chars, args.overlap_blocks)
    packets = packet_builder.build(document.document_id, blocks)
    if args.save_debug_json:
        packet_dir = ensure_dir(out / "packets")
        for p in packets:
            write_text(packet_dir / f"{p.chunk_id}.md", p.text)
    log.emit(
        "2. 패킷 생성",
        "블록 목록을 LLM 입력 패킷으로 나누었고, 패킷 객체 목록을 의미 추출 단계로 전달합니다.",
        {"패킷 수": len(packets), "최대 글자 수": args.max_packet_chars, "겹침 블록 수": args.overlap_blocks},
    )

    # 3. LLM semantic extraction: summary/key points/concepts/evidence with anchor_block_ids only.
    assert api_client is not None
    existing_source_artifact = (
        getattr(args, "existing_source_artifact", None)
        if getattr(args, "selection_mode", None) == "full"
        else None
    )
    existing_source_markdown = (
        getattr(args, "existing_source_markdown", None)
        if getattr(args, "selection_mode", None) == "full"
        else None
    )
    semantic_source_context = (
        {"source_markdown": existing_source_markdown}
        if existing_source_markdown
        else None
    )
    raw_dir = ensure_dir(out / "raw_llm_outputs" / "semantic_extraction") if args.save_debug_json else None
    normalizer = SemanticNormalizer(document, blocks)
    max_eval_attempts = max(1, int(getattr(args, "max_eval_attempts", 2) or 2))
    notes, normalized, generation_evaluations = _run_wiki_generation_loop(
        api_client=api_client,
        semantic_system_prompt=prompts.semantic,
        wiki_evaluator_system_prompt=prompts.wiki_evaluator,
        packets=packets,
        raw_dir=raw_dir,
        log=log,
        normalizer=normalizer,
        document=document,
        blocks=blocks,
        out=out,
        save_debug_json=args.save_debug_json,
        wiki_evaluation_loop=getattr(args, "wiki_evaluation_loop", True),
        max_eval_attempts=max_eval_attempts,
        source_context=semantic_source_context,
        wiki_patch_system_prompt=prompts.wiki_patch,
    )

    # 4. Backend normalize/merge/mention expansion.
    log.emit(
        "4. 정규화",
        "의미 노트를 concept ledger와 evidence unit으로 정규화했습니다.",
        {
            "core concept 수": len(normalized["concept_ledger"]),
            "section candidate 수": len(normalized.get("section_candidates", [])),
            "mention 수": len(normalized.get("mentions", [])),
            "category 수": len(normalized.get("categories", [])),
            "근거 수": len(normalized["evidence_units"]),
            "경고 수": len(normalized.get("warnings", [])),
        },
    )

    # 4a. Resolve incoming concepts against each other and existing wiki concepts before page generation.
    assert api_client is not None
    normalized, same_source_context_blocks = _resolve_pipeline_concepts(
        args,
        api_client=api_client,
        concept_resolution_prompt=prompts.concept_resolution,
        normalized=normalized,
        existing_source_artifact=existing_source_artifact,
        out=out,
        log=log,
    )
    if args.save_debug_json:
        write_json(out / "normalized.json", normalized)
        if generation_evaluations:
            write_json(out / "wiki_generation_evaluations.json", generation_evaluations)

    page_outputs = _assemble_wiki_pages(
        args,
        api_client=api_client,
        prompts=prompts,
        normalized=normalized,
        blocks=blocks,
        same_source_context_blocks=same_source_context_blocks,
        existing_source_artifact=existing_source_artifact,
        existing_source_markdown=existing_source_markdown,
        out=out,
        log=log,
    )

    meaning_cluster_artifact, maintenance_summary = _assemble_meaning_clusters(
        args,
        api_client=api_client,
        normalized=normalized,
        out=out,
        log=log,
    )

    manifest = {
        "input": input_source_name if input_text is not None else str(input_path),
        "out": str(out),
        "mode": args.mode,
        "selection_mode": getattr(args, "selection_mode", None),
        "user_id": args.user_id,
        "workspace_id": args.workspace_id,
        "source_page_mode": page_outputs.source_page_mode,
        "concept_page_mode": page_outputs.concept_page_mode,
        "document_id": document.document_id,
        "source_document_id": getattr(args, "source_document_id", None),
        "source_page": page_outputs.source_page,
        "source_extraction_artifact": _json_safe(
            page_outputs.source_page_normalized.get("source_extraction_artifact")
        ),
        "source_blocks": source_block_records,
        "concept_pages": page_outputs.concept_pages,
        "links": page_outputs.links,
        "meaning_clusters": meaning_cluster_artifact,
        "maintenance_summary": maintenance_summary,
        "normalized": normalized,
        "generation_evaluations": generation_evaluations,
        "generation_evaluation_status": generation_evaluation_status(generation_evaluations),
        "pipeline_log": str(log.path),
        "log_callback_url": getattr(args, "log_callback_url", None),
        "save_debug_json": args.save_debug_json,
        "warnings": normalized.get("warnings", []),
    }
    log.emit(
        "완료",
        "파이프라인 실행이 완료되었습니다.",
        {
            "문서 ID": document.document_id,
            "core concept 수": len(normalized["concept_ledger"]),
            "section candidate 수": len(normalized.get("section_candidates", [])),
            "mention 수": len(normalized.get("mentions", [])),
            "category 수": len(normalized.get("categories", [])),
            "근거 수": len(normalized["evidence_units"]),
        },
    )
    return _json_safe(manifest)


def pipeline_command_from_cli_args(args: argparse.Namespace) -> PipelineRunCommand:
    values = vars(args).copy()
    input_value = values.pop("input")
    return PipelineRunCommand(
        run_id=None,
        input=input_value,
        input_name=input_value,
        **values,
    )


def main() -> None:
    command = pipeline_command_from_cli_args(parse_args())
    manifest = run_pipeline(command)
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
