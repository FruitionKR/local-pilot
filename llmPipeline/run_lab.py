#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shutil
import urllib.error
import urllib.request
from dataclasses import asdict
from datetime import datetime
from pathlib import Path
from typing import Any

from fruition_lab.assemble import (
    ConceptPageAssembler,
    GeneratedConceptPageAssembler,
    LinkBuilder,
    ReviewReport,
    SourcePageAssembler,
)
from fruition_lab.extract import MarkdownBlockExtractor
from fruition_lab.io_utils import append_text, ensure_dir, write_json, write_text
from fruition_lab.llm import (
    ApiConceptPageGenerator,
    ApiSemanticExtractor,
    ChatClientConfig,
    ChatCompletionsJsonClient,
)
from fruition_lab.normalize import SemanticNormalizer
from fruition_lab.packet import SemanticPacketBuilder
from fruition_lab.prompt_io import collect_concept_source_blocks


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
    ap.add_argument("--concept-page-mode", choices=["auto", "api", "skeleton"], default="auto", help="auto: api when --mode api, otherwise skeleton")
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

    ap.add_argument("--system-prompt", default="prompts/semantic_extraction.system.md")
    ap.add_argument("--concept-system-prompt", default="prompts/concept_page_generation.system.md")
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
    return "api" if args.mode in {"api", "generic-chat"} else "skeleton"


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
            "Concept Page 모드": concept_page_mode(args),
        },
    )

    semantic_system_prompt = read_prompt(args.system_prompt)
    concept_system_prompt = read_prompt(args.concept_system_prompt)
    log.emit("프롬프트 로드", "시스템 프롬프트를 메모리에 로드했습니다.", {"semantic": args.system_prompt, "concept": args.concept_system_prompt})

    api_client = None
    if args.mode in {"api", "generic-chat"} or concept_page_mode(args) == "api":
        api_client = load_api_client(args)
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

    # 1. Extract blocks with long refs stored in backend artifacts.
    extractor = MarkdownBlockExtractor()
    document, blocks = extractor.extract(input_path)
    source_document_id = getattr(args, "source_document_id", None)
    if source_document_id:
        document.document_id = source_document_id
        for block in blocks:
            block.document_id = source_document_id
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
    raw_dir = ensure_dir(out / "raw_llm_outputs" / "semantic_extraction")
    for p in packets:
        note = semantic_llm.extract(p)
        notes.append(note)
        write_json(raw_dir / f"{p.chunk_id}.json", note)
        log.emit(
            "3. 의미 추출",
            "패킷에서 의미 노트를 추출했고, 노트 객체를 메모리에 추가했습니다.",
            {
                "패킷": p.chunk_id,
                "핵심 포인트 수": len(note.get("key_points", [])),
                "후보 개념 수": len(note.get("concept_candidates", [])),
                "근거 주장 수": len(note.get("evidence_claims", [])),
            },
        )
    log.emit("3. 의미 추출 완료", "의미 노트 목록을 정규화 단계 입력으로 전달합니다.", {"노트 수": len(notes)})

    # 4. Backend normalize/merge/mention expansion.
    normalizer = SemanticNormalizer(document, blocks)
    normalized = normalizer.normalize_notes(notes)
    write_json(out / "normalized.json", normalized)
    log.emit(
        "4. 정규화",
        "의미 노트를 concept ledger와 evidence unit으로 정규화했고, normalized 객체를 다음 단계로 전달합니다.",
        {
            "개념 수": len(normalized["concept_ledger"]),
            "근거 수": len(normalized["evidence_units"]),
            "경고 수": len(normalized.get("warnings", [])),
        },
    )

    # 4b. Collect source blocks for concept page generation.
    concept_source_blocks_by_slug = {}
    for concept in normalized["concept_ledger"]:
        source_blocks = collect_concept_source_blocks(concept, normalized["evidence_units"], blocks, max_blocks=12)
        concept_source_blocks_by_slug[concept["slug"]] = source_blocks
    log.emit("4-보조. Concept 입력 준비", "상위 개념별 source block을 메모리에 모았습니다.", {"대상 개념 수": len(concept_source_blocks_by_slug)})

    # 5. Assemble source page, generate/assemble concept pages, and build links.
    source_page = SourcePageAssembler().assemble(normalized, out)
    log.emit("5. Source Page 생성", "정규화 결과에서 source page markdown을 생성했습니다.", {"파일": source_page})
    generated_concept_pages = []
    raw_concept_dir = ensure_dir(out / "raw_llm_outputs" / "concept_page_generation")
    cp_mode = concept_page_mode(args)
    if cp_mode == "api":
        assert api_client is not None
        concept_generator = ApiConceptPageGenerator(api_client, concept_system_prompt)
        generator_assembler = GeneratedConceptPageAssembler()
        for concept in normalized["concept_ledger"]:
            source_blocks = concept_source_blocks_by_slug.get(concept["slug"], [])
            raw_page = concept_generator.generate(concept, normalized["evidence_units"], source_blocks)
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
        concept_pages = ConceptPageAssembler().assemble_top(normalized, out, top_n=len(normalized["concept_ledger"]))
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
        "concept_page_mode": cp_mode,
        "document_id": document.document_id,
        "source_document_id": source_document_id,
        "block_count": len(blocks),
        "packet_count": len(packets),
        "semantic_note_count": len(notes),
        "concept_count": len(normalized["concept_ledger"]),
        "evidence_count": len(normalized["evidence_units"]),
        "generated_concept_page_count": len(generated_concept_pages),
        "source_page": source_page,
        "concept_pages": concept_pages,
        "links": str(out / "wiki" / "links.json"),
        "review_report": report,
        "pipeline_log": str(log.path),
        "log_callback_url": getattr(args, "log_callback_url", None),
        "warnings": normalized.get("warnings", []),
    }
    write_json(out / "manifest.json", manifest)
    log.emit(
        "완료",
        "파이프라인 실행이 완료되었습니다.",
        {
            "문서 ID": document.document_id,
            "개념 수": len(normalized["concept_ledger"]),
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
