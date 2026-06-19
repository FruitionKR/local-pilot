#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import urllib.error
import urllib.request
from dataclasses import asdict
from datetime import datetime
from pathlib import Path
from typing import Any

from app.modules.wiki_generation.infrastructure.assemble import (
    ConceptPageAssembler,
    GeneratedConceptPageAssembler,
    LinkBuilder,
    ReviewReport,
    SourcePageAssembler,
)
from app.modules.wiki_generation.infrastructure.extract import MarkdownBlockExtractor
from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ApiConceptResolver,
    ApiConceptPageGenerator,
    ApiSectionPolisher,
    ApiSemanticExtractor,
    ChatClientConfig,
    ChatCompletionsJsonClient,
    SectionPolishParseError,
)
from app.modules.wiki_generation.infrastructure.concept_resolution import (
    apply_concept_resolutions,
    load_existing_concept_index,
    normalize_hint_resolution_output,
    normalize_resolution_output,
)
from app.modules.wiki_generation.infrastructure.normalize import SemanticNormalizer
from app.modules.wiki_generation.infrastructure.packet import SemanticPacketBuilder
from app.modules.wiki_generation.infrastructure.prompt_io import collect_concept_source_blocks
from app.modules.wiki_ingestion.infrastructure.file_io import append_text, ensure_dir, write_json, write_text


class PipelineLog:
    def __init__(self, path: str | Path, callback_url: str | None = None, run_id: str | None = None) -> None:
        self.path = Path(path)
        self.callback_url = callback_url
        self.run_id = run_id
        if self.path.exists():
            self.path.unlink()

    def emit(self, stage: str, message: str, data: dict[str, Any] | None = None) -> None:
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        event = {
            "run_id": self.run_id,
            "timestamp": now,
            "stage": stage,
            "message": message,
            "data": {key: str(value) for key, value in (data or {}).items()},
        }
        lines = [f"[{now}] [{stage}] {message}"]
        for key, value in event["data"].items():
            lines.append(f"  - {key}: {value}")
        append_text(self.path, "\n".join(lines) + "\n")
        if self.callback_url:
            self._post_event(event)

    def _post_event(self, event: dict[str, Any]) -> None:
        body = json.dumps(event, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.callback_url,
            data=body,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=5):
                pass
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            append_text(self.path, f"[{event['timestamp']}] [로그 전송 실패] {exc}\n")


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

    ap.add_argument("--system-prompt", default="prompts/semantic_extraction.system.md")
    ap.add_argument("--concept-system-prompt", default="prompts/concept_page_generation.system.md")
    ap.add_argument("--concept-resolution-system-prompt", default="prompts/concept_resolution.system.md")
    ap.add_argument("--section-polish-system-prompt", default="prompts/section_polish.system.md")
    ap.add_argument("--existing-wiki-dir", help="Optional existing wiki directory. If set, existing wiki/concepts/*.md pages are used for concept resolution before page generation.")
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


def resolve_api_defaults(args: argparse.Namespace) -> None:
    """Resolve provider-specific API defaults after optional .env loading."""
    if args.provider == "upstage":
        args.api_base_url = (
            args.api_base_url
            or os.environ.get("UPSTAGE_BASE_URL")
            or os.environ.get("LLM_BASE_URL")
            or "https://api.upstage.ai/v1"
        )
        args.api_key_env = args.api_key_env or "UPSTAGE_API_KEY"
        args.model = (
            args.model
            or os.environ.get("UPSTAGE_MODEL")
            or os.environ.get("LLM_MODEL")
            or "solar-pro2"
        )
    else:
        args.api_base_url = args.api_base_url or os.environ.get("LLM_BASE_URL") or "https://api.openai.com/v1"
        args.api_key_env = args.api_key_env or "LLM_API_KEY"
        args.model = args.model or os.environ.get("LLM_MODEL")

def read_prompt(path_like: str) -> str:
    prompt_path = Path(path_like)
    if not prompt_path.exists():
        prompt_path = Path(__file__).parent / path_like
    if not prompt_path.exists():
        raise SystemExit(f"Prompt not found: {path_like}")
    return prompt_path.read_text(encoding="utf-8")


def resolve_endpoint(args: argparse.Namespace) -> str:
    if args.endpoint:
        return args.endpoint
    return args.api_base_url.rstrip("/") + "/chat/completions"


def load_api_client(args: argparse.Namespace) -> ChatCompletionsJsonClient:
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


def concept_page_mode(args: argparse.Namespace) -> str:
    if args.concept_page_mode != "auto":
        return args.concept_page_mode
    return "skeleton"


def source_page_mode(args: argparse.Namespace) -> str:
    if getattr(args, "source_page_mode", "auto") != "auto":
        return args.source_page_mode
    return "section-polish" if args.mode in {"api", "generic-chat"} else "skeleton"


def _map_polish_output(raw: dict[str, Any], source_blocks: list[Any], warnings: list[str], context: str) -> dict[str, Any]:
    valid_bids = {b.block_id for b in source_blocks}

    def map_refs(anchor_block_ids: list[str]) -> list[str]:
        refs = []
        for bid in anchor_block_ids or []:
            if bid not in valid_bids:
                warnings.append(f"{context}: unknown polish anchor_block_id {bid}")
                continue
            refs.append(bid)
        return refs

    def clean_text(text: Any) -> str:
        text = str(text or "")
        text = re.sub(r"\s*[\[(]B\d{4}[\])]", "", text)
        text = re.sub(r"\s+", " ", text).strip()
        return text

    mapped = {
        "section": raw.get("section"),
        "text": clean_text(raw.get("text", "")),
        "anchor_reference_ids": map_refs(raw.get("anchor_block_ids", [])),
        "items": [],
        "related_concept_hints": raw.get("related_concept_hints", []),
        "confidence": raw.get("confidence", 0.0),
    }
    for item in raw.get("items", []) or []:
        mapped["items"].append(
            {
                "text": clean_text(item.get("text", "")),
                "anchor_reference_ids": map_refs(item.get("anchor_block_ids", [])),
            }
        )
    return mapped


def run_pipeline(args: argparse.Namespace) -> dict:
    load_env_file(args.env_file)
    resolve_api_defaults(args)
    input_path = Path(args.input)
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
            "입력": input_path,
            "출력 폴더": out,
            "실행 모드": args.mode,
            "Source Page 모드": source_page_mode(args),
            "Concept Page 모드": concept_page_mode(args),
        },
    )

    semantic_system_prompt = read_prompt(args.system_prompt)
    concept_system_prompt = read_prompt(args.concept_system_prompt)
    concept_resolution_system_prompt = read_prompt(args.concept_resolution_system_prompt)
    section_polish_system_prompt = read_prompt(args.section_polish_system_prompt)
    log.emit(
        "프롬프트 로드",
        "시스템 프롬프트를 메모리에 로드했습니다.",
        {
            "semantic": args.system_prompt,
            "concept": args.concept_system_prompt,
            "concept_resolution": args.concept_resolution_system_prompt,
            "section_polish": args.section_polish_system_prompt,
        },
    )

    api_client = None
    if args.mode in {"api", "generic-chat"} or concept_page_mode(args) in {"api", "full-llm", "section-polish"} or source_page_mode(args) == "section-polish":
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
        log.emit("API 설정", "LLM API 클라이언트를 준비했습니다.", {"provider": args.provider, "model": args.model, "endpoint": resolve_endpoint(args)})

    # 1. Extract blocks. Normalized refs use short B-ids; source_reference_id stays on SourceBlock for DB/export use.
    extractor = MarkdownBlockExtractor()
    document, blocks = extractor.extract(input_path)
    source_document_id = getattr(args, "source_document_id", None)
    if source_document_id:
        document.document_id = source_document_id
        for block in blocks:
            block.document_id = source_document_id
    if args.save_debug_json:
        write_json(out / "document.json", asdict(document))
        write_json(out / "block_map.json", {b.block_id: b.source_reference_id for b in blocks})
    log.emit(
        "1. 블록 추출",
        "Markdown 원문을 블록 객체로 변환했고, 이 블록 목록을 다음 단계 입력으로 전달합니다.",
        {"문서 ID": document.document_id, "문서 제목": document.title, "블록 수": len(blocks)},
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
    semantic_llm = ApiSemanticExtractor(api_client, semantic_system_prompt)

    notes = []
    raw_dir = ensure_dir(out / "raw_llm_outputs" / "semantic_extraction") if args.save_debug_json else None
    for p in packets:
        note = semantic_llm.extract(p)
        notes.append(note)
        if raw_dir is not None:
            write_json(raw_dir / f"{p.chunk_id}.json", note)
        log.emit(
            "3. 의미 추출",
            "패킷에서 의미 노트를 추출했고, 노트 객체를 메모리에 추가했습니다.",
            {
                "패킷": p.chunk_id,
                "핵심 포인트 수": len(note.get("key_points", [])),
                "core concept 수": len(note.get("core_concepts") or note.get("concept_candidates", [])),
                "section/mention/category 수": f"{len(note.get('section_candidates', []))}/{len(note.get('mentions', []))}/{len(note.get('categories', []))}",
                "근거 주장 수": len(note.get("evidence_claims", [])),
            },
        )
    log.emit("3. 의미 추출 완료", "의미 노트 목록을 정규화 단계 입력으로 전달합니다.", {"노트 수": len(notes)})

    # 4. Backend normalize/merge/mention expansion.
    normalizer = SemanticNormalizer(document, blocks)
    normalized = normalizer.normalize_notes(notes)
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
    existing_concepts = load_existing_concept_index(getattr(args, "existing_wiki_dir", None))
    missing_related_hints = normalized.get("missing_related_concept_hints", [])
    assert api_client is not None
    concept_resolver = ApiConceptResolver(api_client, concept_resolution_system_prompt)
    raw_resolution = concept_resolver.resolve(normalized["concept_ledger"], existing_concepts, missing_related_hints)
    if args.save_debug_json:
        write_json(ensure_dir(out / "raw_llm_outputs") / "concept_resolution.json", raw_resolution)
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
    normalized = apply_concept_resolutions(normalized, resolutions, existing_concepts, hint_resolutions)
    log.emit(
        "4-보조. Concept Resolution",
        "새 concept 후보끼리와 기존 concept page index, missing related hint를 비교해 canonical slug와 관련 링크를 확정했습니다.",
        {
            "기존 개념 수": len(existing_concepts),
            "해결 전 개념 수": len(resolutions),
            "해결 후 개념 수": len(normalized["concept_ledger"]),
            "missing hint 수": len(missing_related_hints),
            "hint 해결 수": sum(1 for item in hint_resolutions if item.get("decision") not in {"unresolved", "promote_new_concept"}),
            "병합 수": sum(1 for item in resolutions if item.get("decision") == "merge_into"),
            "링크 판단 수": sum(1 for item in resolutions if item.get("link_targets")),
        },
    )
    write_json(out / "normalized.json", normalized)

    # 4b. Collect source blocks for concept page generation.
    concept_source_blocks_by_slug = {}
    for concept in normalized["concept_ledger"]:
        source_blocks = collect_concept_source_blocks(concept, normalized["evidence_units"], blocks)
        concept_source_blocks_by_slug[concept["slug"]] = source_blocks
    log.emit("4-보조. Concept 입력 준비", "전체 개념별 source block을 메모리에 모았습니다.", {"대상 개념 수": len(concept_source_blocks_by_slug)})

    # 5. Assemble source page with optional section polish.
    section_polisher = ApiSectionPolisher(api_client, section_polish_system_prompt) if api_client is not None else None
    raw_polish_dir = ensure_dir(out / "raw_llm_outputs" / "section_polish") if args.save_debug_json else None
    invalid_polish_dir = out / "raw_llm_outputs" / "section_polish_invalid"
    source_polish: dict[str, Any] = {}
    raw_source_key_points_for_concepts: list[dict[str, Any]] = [
        kp
        for note in normalized.get("semantic_notes", [])
        for kp in note.get("key_points", [])
    ]
    source_key_points_for_concepts = list(raw_source_key_points_for_concepts)
    sp_mode = source_page_mode(args)
    if sp_mode == "section-polish":
        assert section_polisher is not None
        source_payload = {
            "page_type": "source",
            "section": "source_summary_and_key_points",
            "context": {
                "document": normalized["document"],
                "concept_slugs": [concept["slug"] for concept in normalized["concept_ledger"]],
            },
            "draft": {
                "summary_candidates": [n.get("semantic_summary", "") for n in normalized["semantic_notes"] if n.get("semantic_summary")],
                "key_points": [kp for note in normalized["semantic_notes"] for kp in note.get("key_points", [])],
            },
            "evidence": normalized["evidence_units"],
        }
        try:
            raw_source_polish = section_polisher.polish(source_payload, blocks)
        except SectionPolishParseError as exc:
            ensure_dir(invalid_polish_dir)
            write_text(invalid_polish_dir / "source_page.txt", exc.raw_content)
            normalized.setdefault("warnings", []).append("source_page: section polish output was not repairable; used backend skeleton")
            log.emit("5-보조. Source Section Polish", "Source page section polish가 복구 불가능해 backend skeleton으로 대체했습니다.", {"invalid_raw": invalid_polish_dir / "source_page.txt"})
        else:
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
    source_page = SourcePageAssembler().assemble(normalized, out, polish=source_polish)
    source_artifact = normalized.get("source_extraction_artifact")
    log.emit(
        "5. Source Page 생성",
        "백엔드 조립 방식으로 source page markdown과 source extraction JSON을 생성했습니다.",
        {"파일": source_page, "source_json": source_artifact, "mode": sp_mode},
    )
    generated_concept_pages = []
    concept_polish_by_slug: dict[str, Any] = {}
    raw_concept_dir = ensure_dir(out / "raw_llm_outputs" / "concept_page_generation") if args.save_debug_json else None
    cp_mode = concept_page_mode(args)
    if cp_mode == "section-polish":
        assert section_polisher is not None
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
                ensure_dir(invalid_polish_dir)
                invalid_path = invalid_polish_dir / f"concept_{concept['slug']}.txt"
                write_text(invalid_path, exc.raw_content)
                normalized.setdefault("warnings", []).append(f"concept:{concept['slug']}: section polish output was not repairable; used backend skeleton")
                log.emit(
                    "6-보조. Concept Section Polish",
                    "Concept section polish가 복구 불가능해 해당 concept은 backend skeleton으로 대체했습니다.",
                    {"개념": concept["slug"], "invalid_raw": invalid_path},
                )
                continue
            else:
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
        concept_pages = ConceptPageAssembler().assemble_top(
            normalized,
            out,
            top_n=None,
            polish_by_slug=concept_polish_by_slug,
            source_key_points=source_key_points_for_concepts,
        )
        log.emit("6. Concept Page 생성", "백엔드 조립과 섹션 polish로 concept page를 생성했습니다.", {"파일 수": len(concept_pages)})
    elif cp_mode in {"api", "full-llm"}:
        assert api_client is not None
        concept_generator = ApiConceptPageGenerator(api_client, concept_system_prompt)
        generator_assembler = GeneratedConceptPageAssembler()
        for concept in normalized["concept_ledger"]:
            source_blocks = concept_source_blocks_by_slug.get(concept["slug"], [])
            raw_page = concept_generator.generate(concept, normalized["evidence_units"], source_blocks)
            if raw_concept_dir is not None:
                write_json(raw_concept_dir / f"{concept['slug']}.json", raw_page)
            generated_page = generator_assembler.normalize_generated_output(
                concept,
                raw_page,
                source_blocks,
                normalized.setdefault("warnings", []),
            )
            generated_concept_pages.append(generated_page)
            log.emit(
                "6. Concept Page LLM 생성",
                "LLM concept page 출력을 backend 형식으로 정규화했습니다.",
                {"개념": concept["slug"], "근거 블록 수": len(source_blocks), "confidence": generated_page.get("confidence")},
            )
        concept_pages = generator_assembler.assemble_pages(generated_concept_pages, out)
    else:
        concept_pages = ConceptPageAssembler().assemble_top(
            normalized,
            out,
            top_n=None,
            source_key_points=source_key_points_for_concepts,
        )
        log.emit("6. Concept Page 생성", "Backend skeleton 방식으로 concept page를 생성했습니다.", {"파일 수": len(concept_pages)})

    links = LinkBuilder().build(normalized, generated_concept_pages=generated_concept_pages)
    write_json(out / "wiki" / "links.json", links)
    report = ReviewReport().write(normalized, out, generated_concept_pages=generated_concept_pages)
    log.emit(
        "7. 링크/리뷰 생성",
        "위키 링크와 리뷰 리포트를 생성했습니다.",
        {"링크 수": len(links), "리뷰 리포트": report},
    )

    manifest = {
        "input": str(input_path),
        "out": str(out),
        "mode": args.mode,
        "source_page_mode": sp_mode,
        "concept_page_mode": cp_mode,
        "document_id": document.document_id,
        "source_document_id": source_document_id,
        "block_count": len(blocks),
        "packet_count": len(packets),
        "semantic_note_count": len(notes),
        "concept_count": len(normalized["concept_ledger"]),
        "section_candidate_count": len(normalized.get("section_candidates", [])),
        "mention_count": len(normalized.get("mentions", [])),
        "category_count": len(normalized.get("categories", [])),
        "existing_concept_count": len(normalized.get("existing_concept_index", [])),
        "concept_resolution_count": len(normalized.get("concept_resolutions", [])),
        "hint_resolution_count": len(normalized.get("hint_resolutions", [])),
        "unresolved_related_hint_count": len(normalized.get("unresolved_related_concept_hints", [])),
        "evidence_count": len(normalized["evidence_units"]),
        "generated_concept_page_count": len(generated_concept_pages),
        "source_page": source_page,
        "source_extraction_artifact": normalized.get("source_extraction_artifact"),
        "concept_pages": concept_pages,
        "links": str(out / "wiki" / "links.json"),
        "review_report": report,
        "pipeline_log": str(log.path),
        "log_callback_url": getattr(args, "log_callback_url", None),
        "save_debug_json": args.save_debug_json,
        "warnings": normalized.get("warnings", []),
    }
    write_json(out / "manifest.json", manifest)
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
            "manifest": out / "manifest.json",
        },
    )
    return manifest


def main() -> None:
    manifest = run_pipeline(parse_args())
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
