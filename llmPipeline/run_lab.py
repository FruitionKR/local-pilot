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
from typing import Any, TypedDict

from langgraph.graph import END, StateGraph

from app.modules.wiki_generation.infrastructure.assemble import (
    ConceptPageAssembler,
    GeneratedConceptPageAssembler,
    LinkBuilder,
    MeaningClusterArtifactAssembler,
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
from app.modules.wiki_ingestion.infrastructure.object_storage import read_text_object


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


class _WikiGenerationGraphState(TypedDict):
    attempt: int
    semantic_prompt_for_attempt: str
    notes: list[dict[str, Any]]
    normalized: dict[str, Any]
    evaluation: dict[str, Any] | None
    generation_evaluations: list[dict[str, Any]]


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
    ap.add_argument("--wiki-evaluator-system-prompt", default="prompts/wiki_generation_evaluator.system.md")
    ap.add_argument("--existing-wiki-dir", help="Optional existing wiki directory. If set, existing wiki/concepts/*.md pages are used for concept resolution before page generation.")
    ap.add_argument("--wiki-evaluation-loop", action="store_true", help="Evaluate normalized wiki generation and retry semantic extraction with evaluator feedback when needed")
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


def _run_semantic_extraction(
    *,
    api_client: ChatCompletionsJsonClient,
    system_prompt: str,
    packets: list[Any],
    raw_dir: Path | None,
    log: PipelineLog,
    attempt: int,
) -> list[dict[str, Any]]:
    semantic_llm = ApiSemanticExtractor(api_client, system_prompt)
    notes = []
    for p in packets:
        note = semantic_llm.extract(p)
        notes.append(note)
        if raw_dir is not None:
            suffix = "" if attempt == 1 else f".attempt{attempt}"
            write_json(raw_dir / f"{p.chunk_id}{suffix}.json", note)
        log.emit(
            "3. 의미 추출",
            "패킷에서 의미 노트를 추출했고, 노트 객체를 메모리에 추가했습니다.",
            {
                "시도": attempt,
                "패킷": p.chunk_id,
                "핵심 포인트 수": len(note.get("key_points", [])),
                "core concept 수": len(note.get("core_concepts") or note.get("concept_candidates", [])),
                "section/mention/category 수": f"{len(note.get('section_candidates', []))}/{len(note.get('mentions', []))}/{len(note.get('categories', []))}",
                "근거 주장 수": len(note.get("evidence_claims", [])),
            },
        )
    return notes


def _evaluate_generation(
    *,
    api_client: ChatCompletionsJsonClient,
    evaluator_prompt: str,
    document: Any,
    blocks: list[Any],
    normalized: dict[str, Any],
) -> dict[str, Any]:
    payload = {
        "document": asdict(document),
        "source_blocks": [
            {"block_id": block.block_id, "text": block.text}
            for block in blocks
        ],
        "normalized": {
            "semantic_notes": normalized.get("semantic_notes", []),
            "concept_ledger": normalized.get("concept_ledger", []),
            "categories": normalized.get("categories", []),
            "section_candidates": normalized.get("section_candidates", []),
            "mentions": normalized.get("mentions", []),
            "observations": normalized.get("observations", []),
            "evidence_units": normalized.get("evidence_units", []),
            "warnings": normalized.get("warnings", []),
        },
    }
    evaluation = api_client.complete_json(evaluator_prompt, json.dumps(payload, ensure_ascii=False, indent=2))
    evaluation.setdefault("scores", {})
    evaluation.setdefault("passed", False)
    evaluation.setdefault("retry_recommended", not bool(evaluation.get("passed")))
    evaluation.setdefault("issues", [])
    evaluation.setdefault("retry_feedback", "")
    _apply_generation_evaluation_guards(evaluation, normalized)
    return evaluation


def _apply_generation_evaluation_guards(evaluation: dict[str, Any], normalized: dict[str, Any]) -> None:
    core_slugs = {str(item.get("slug")) for item in normalized.get("concept_ledger", [])}
    metadata_fragments = {
        "citation-marker",
        "citation-rank",
        "retrieval-rank",
        "source-block",
        "web-url",
        "confidence",
    }
    fragmented = sorted(core_slugs.intersection(metadata_fragments))
    if len(fragmented) >= 3:
        _append_eval_issue(
            evaluation,
            {
                "metric": "concept_groundedness",
                "type": "over_fragmented_concept",
                "severity": "medium",
                "target": fragmented,
                "reason": "citation/source metadata가 독립 core concept로 과하게 분리됨",
                "feedback": "citation marker, citation_rank, retrieval_rank, source block, web URL, confidence는 독립 core concept가 아니라 citation/provenance metadata의 section_candidate 또는 mention으로 낮추세요.",
            },
        )
    _apply_observation_evaluation_guards(evaluation, normalized)
    if any(issue.get("severity") in {"medium", "high"} for issue in evaluation.get("issues", [])):
        evaluation["passed"] = False
        evaluation["retry_recommended"] = True
        scores = evaluation.setdefault("scores", {})
        if isinstance(scores.get("overall"), int | float):
            scores["overall"] = min(float(scores["overall"]), 0.74)
        feedbacks = [str(issue.get("feedback")) for issue in evaluation.get("issues", []) if issue.get("feedback")]
        evaluation["retry_feedback"] = " ".join(_unique(feedbacks))


def _repair_normalized_from_evaluation(normalized: dict[str, Any], evaluation: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    repairable_types = {"observation_missing_ref", "broken_observation", "duplicate_observation"}
    issues = [issue for issue in evaluation.get("issues", []) if issue.get("type") in repairable_types]
    if not issues:
        return normalized, []
    repaired = {**normalized}
    observations = [dict(item) for item in normalized.get("observations", [])]
    if not observations:
        return normalized, []

    remove_ids: set[str] = set()
    duplicate_groups: list[list[str]] = []
    for issue in issues:
        targets = [str(target) for target in issue.get("target", []) if str(target)]
        if issue.get("type") in {"observation_missing_ref", "broken_observation"}:
            remove_ids.update(targets)
        elif issue.get("type") == "duplicate_observation" and len(targets) > 1:
            duplicate_groups.append(targets)

    operations: list[str] = []
    observations_by_id = {str(item.get("observation_id")): item for item in observations}
    for ids in duplicate_groups:
        candidates = [observations_by_id[item_id] for item_id in ids if item_id in observations_by_id and item_id not in remove_ids]
        if len(candidates) < 2:
            continue
        keeper = _select_observation_keeper(candidates)
        for candidate in candidates:
            if candidate is keeper:
                continue
            _merge_observation(keeper, candidate)
            remove_ids.add(str(candidate.get("observation_id")))
        operations.append(f"merged duplicate observations {ids} into {keeper.get('observation_id')}")

    before_count = len(observations)
    observations = [item for item in observations if str(item.get("observation_id")) not in remove_ids]
    if len(observations) != before_count:
        operations.append(f"removed {before_count - len(observations)} broken or duplicate observations")
    observations = _renumber_observations(observations)
    repaired["observations"] = observations

    repaired_notes = []
    valid_signatures = {_observation_content_signature(item) for item in observations}
    for note in normalized.get("semantic_notes", []):
        note_copy = {**note}
        note_observations = []
        for observation in note.get("observations", []):
            if _observation_content_signature(observation) in valid_signatures:
                note_observations.append(observation)
        note_copy["observations"] = note_observations
        repaired_notes.append(note_copy)
    repaired["semantic_notes"] = repaired_notes
    return repaired, operations


def _select_observation_keeper(observations: list[dict[str, Any]]) -> dict[str, Any]:
    return max(
        observations,
        key=lambda item: (
            len(item.get("anchor_reference_ids", []) or []),
            len(str(item.get("summary") or "")),
            len(item.get("claims", []) or []),
        ),
    )


def _merge_observation(target: dict[str, Any], incoming: dict[str, Any]) -> None:
    target["anchor_reference_ids"] = _unique((target.get("anchor_reference_ids", []) or []) + (incoming.get("anchor_reference_ids", []) or []))
    target["claims"] = _unique([str(item).strip() for item in (target.get("claims", []) or []) + (incoming.get("claims", []) or []) if str(item).strip()])
    target["related_concept_hints"] = _unique(
        [str(item).strip() for item in (target.get("related_concept_hints", []) or []) + (incoming.get("related_concept_hints", []) or []) if str(item).strip()]
    )
    for field in ("summary", "title", "query_text"):
        if len(str(incoming.get(field) or "")) > len(str(target.get(field) or "")):
            target[field] = incoming.get(field)


def _renumber_observations(observations: list[dict[str, Any]]) -> list[dict[str, Any]]:
    renumbered = []
    for idx, observation in enumerate(observations, start=1):
        renumbered.append({**observation, "observation_id": f"O{idx:03d}"})
    return renumbered


def _observation_content_signature(observation: dict[str, Any]) -> str:
    return "\n".join(
        [
            str(observation.get("type") or ""),
            str(observation.get("title") or ""),
            str(observation.get("query_text") or ""),
            str(observation.get("summary") or ""),
        ]
    )


def _apply_observation_evaluation_guards(evaluation: dict[str, Any], normalized: dict[str, Any]) -> None:
    observations = normalized.get("observations", [])
    for observation in observations:
        observation_id = str(observation.get("observation_id") or "unknown")
        refs = observation.get("anchor_reference_ids", []) or []
        summary = str(observation.get("summary") or "").strip()
        title = str(observation.get("title") or "").strip()
        claims = [str(claim).strip() for claim in observation.get("claims", []) if str(claim).strip()]
        if not refs:
            _append_eval_issue(
                evaluation,
                {
                    "metric": "source_faithfulness",
                    "type": "observation_missing_ref",
                    "severity": "medium",
                    "target": [observation_id],
                    "reason": "Observation이 원문 source block anchor 없이 생성되어 검색 근거로 신뢰하기 어렵습니다.",
                    "feedback": "모든 observation은 직접 사용한 anchor_block_ids를 포함해야 합니다. 근거가 없으면 해당 observation을 제거하세요.",
                },
            )
        if _is_broken_observation_text(summary) or (not summary and not claims):
            _append_eval_issue(
                evaluation,
                {
                    "metric": "source_faithfulness",
                    "type": "broken_observation",
                    "severity": "medium",
                    "target": [observation_id],
                    "reason": "Observation summary가 중간에서 끊겼거나 검색 단위로 쓸 수 있을 만큼 완성되지 않았습니다.",
                    "feedback": "chunk 경계에서 깨진 observation은 제거하고, 같은 의미의 정상 observation만 남기세요.",
                },
            )
        if not title and not summary and not claims:
            _append_eval_issue(
                evaluation,
                {
                    "metric": "source_faithfulness",
                    "type": "broken_observation",
                    "severity": "medium",
                    "target": [observation_id],
                    "reason": "Observation에 title, summary, claims가 모두 없어 검색 단위로 사용할 수 없습니다.",
                    "feedback": "빈 observation을 생성하지 마세요.",
                },
            )

    buckets: dict[str, list[str]] = {}
    for observation in observations:
        signature = _observation_signature(observation)
        if not signature:
            continue
        buckets.setdefault(signature, []).append(str(observation.get("observation_id") or "unknown"))
    duplicates = [ids for ids in buckets.values() if len(ids) > 1]
    for ids in duplicates:
        _append_eval_issue(
            evaluation,
            {
                "metric": "source_coverage",
                "type": "duplicate_observation",
                "severity": "medium",
                "target": ids,
                "reason": "서로 다른 chunk나 registry 섹션에서 같은 의미의 observation이 중복 생성되었습니다.",
                "feedback": "query_text/resolved intent와 summary가 같은 observation은 하나로 병합하고 가장 직접적인 source block refs를 유지하세요.",
            },
        )


def _is_broken_observation_text(text: str) -> bool:
    if not text:
        return False
    stripped = text.strip()
    if stripped in {"-", "없음", "N/A"}:
        return True
    openers = {"(": ")", "[": "]", "{": "}", "“": "”", "\"": "\"", "'": "'"}
    for opener, closer in openers.items():
        if stripped.endswith(opener):
            return True
        if stripped.count(opener) > stripped.count(closer):
            return True
    return len(stripped) < 8


def _observation_signature(observation: dict[str, Any]) -> str:
    query = _compact_observation_text(str(observation.get("query_text") or ""))
    summary = _compact_observation_text(str(observation.get("summary") or ""))
    title = _compact_observation_text(str(observation.get("title") or ""))
    if query:
        return f"q:{query}"
    if summary:
        return f"s:{summary[:80]}"
    return f"t:{title}" if title else ""


def _compact_observation_text(text: str) -> str:
    text = re.sub(r"\s+", " ", text.lower()).strip()
    text = re.sub(r"[^0-9a-z가-힣 ]+", "", text)
    return text


def _append_eval_issue(evaluation: dict[str, Any], issue: dict[str, Any]) -> None:
    issues = evaluation.setdefault("issues", [])
    signature = (issue.get("type"), tuple(issue.get("target", [])))
    for existing in issues:
        if (existing.get("type"), tuple(existing.get("target", []))) == signature:
            return
    issues.append(issue)


def _unique(values: list[str]) -> list[str]:
    seen = set()
    rows = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        rows.append(value)
    return rows


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


def _judge_meaning_cluster_candidates(
    *,
    api_client: ChatCompletionsJsonClient,
    existing_active_markdown: str,
    candidates: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    if not candidates:
        return []
    system_prompt = """Stage=MeaningClusterJudge.
You decide whether section/mention evidence claims should update an existing term_cluster or create a new term_cluster.

Rules:
- Return JSON only.
- Do not create core concepts or graph edges.
- Prefer same_cluster when the candidate is a synonym, abbreviation, translation, spelling variant, or narrower wording of an existing cluster.
- Use new_cluster only when no existing cluster or other incoming candidate has the same meaning.
- Incoming candidates may share the same new target_cluster_id if they should be grouped together.
- target_cluster_id must be descriptive kebab-case such as "back-emf" or "manufacturing-uncertainty".
- Never use generic ids such as "cluster-001", "new-cluster", "candidate-1", or "term".
- Use needs_review only when the candidate is ambiguous.
- promotion_status is usually "none".
- Respect the earlier extraction decision: if a term arrived as a
  section_candidate, mention, or evidence item instead of a core_concept, do
  not immediately promote it in the same ingest pass.
- Never set promotion_status to "candidate" for a new_cluster decision. A newly
  created cluster must first remain in active.md and accumulate more evidence.
- Never promote a cluster based on a single incoming candidate, a single claim,
  or a single source in this pass. Promotion requires accumulated evidence that
  already exists in existing_active_clusters plus the incoming claim.
- Use promotion_status "candidate" only when decision is same_cluster and the
  target cluster already exists in existing_active_clusters with multiple
  grounded claims or multiple source refs.
- Use promotion_status "candidate" only when the cluster is worth becoming a
  long-lived core wiki page: it is a reusable domain concept, has grounded
  evidence claims, and can support future relations or retrieval beyond the
  current source.
- Do not promote merely because a term is definable. Definition extractability
  is insufficient without reusable evidence and relation value.
- Do not promote bibliographic/entity metadata: author names, researcher names,
  universities, funders, journals, conferences, publishers, citations, document
  titles, affiliations, or project metadata.
- Do not promote one-off named tools, software, product names, experimental
  labels, parameter labels, or isolated metrics unless the accumulated claims
  show a reusable domain concept with meaningful relations to existing concepts.
- If evidence comes from only one source and mainly identifies a name/entity,
  keep promotion_status as "none".

Schema:
{
  "decisions": [
    {
      "candidate_id": "cand_001",
      "decision": "same_cluster | new_cluster | needs_review",
      "target_cluster_id": "cluster-id-to-update-or-create",
      "representative": "short display label",
      "promotion_status": "none | candidate | needs_review",
      "reason": "brief Korean reason"
    }
  ]
}
"""
    payload = {
        "existing_active_clusters": existing_active_markdown[-24000:],
        "incoming_candidates": [
            {
                "candidate_id": item["candidate_id"],
                "term": item["term"],
                "suggested_slug": item["slug"],
                "claim": item["claim"],
                "refs": item["refs"],
                "candidate_type": item["candidate_type"],
                "suggested_promotion_status": item.get("suggested_promotion_status", "none"),
                "suggested_promotion_reason": item.get("suggested_promotion_reason", ""),
            }
            for item in candidates
        ],
    }
    raw = api_client.complete_json(system_prompt, json.dumps(payload, ensure_ascii=False, indent=2))
    decisions = raw.get("decisions", [])
    if not isinstance(decisions, list):
        return []
    valid_candidate_ids = {item["candidate_id"] for item in candidates}
    normalized_decisions: list[dict[str, Any]] = []
    for item in decisions:
        if not isinstance(item, dict):
            continue
        candidate_id = str(item.get("candidate_id") or "")
        if candidate_id not in valid_candidate_ids:
            continue
        decision = str(item.get("decision") or "new_cluster")
        if decision not in {"same_cluster", "new_cluster", "needs_review"}:
            decision = "new_cluster"
        promotion_status = str(item.get("promotion_status") or "none")
        if promotion_status not in {"none", "candidate", "needs_review"}:
            promotion_status = "none"
        normalized_decisions.append(
            {
                "candidate_id": candidate_id,
                "decision": decision,
                "target_cluster_id": item.get("target_cluster_id"),
                "representative": item.get("representative"),
                "promotion_status": promotion_status,
                "reason": item.get("reason"),
            }
        )
    return normalized_decisions


def _judge_concept_update_candidates(
    *,
    api_client: ChatCompletionsJsonClient,
    concepts: list[dict[str, Any]],
    candidates: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    if not concepts or not candidates:
        return []
    system_prompt = """Stage=ConceptUpdateCandidateJudge.
You decide whether section/mention evidence claims already belong to or have an evidence-backed relation with an existing/current core concept.

Rules:
- Return JSON only.
- Use same_concept when the candidate is a synonym, translation, abbreviation, direct property, or evidence for an existing/current concept.
- Use relation_candidate when the candidate is not the same concept but has an evidence-backed relation to an existing/current concept.
- Use not_same_concept when it should remain available for active cluster judging.
- Do not create new concepts or clusters.
- Allowed relation values: part_of, child_of, uses_or_depends_on, contrasts_with, supports_or_enables, related_evidence, insufficient_evidence.

Schema:
{
  "decisions": [
    {
      "candidate_id": "cand_001",
      "decision": "same_concept | relation_candidate | not_same_concept",
      "concept_slug": "existing-concept-slug-or-empty",
      "relation": "part_of | child_of | uses_or_depends_on | contrasts_with | supports_or_enables | related_evidence | insufficient_evidence | empty",
      "reason": "brief Korean reason"
    }
  ]
}
"""
    payload = {
        "concepts": [
            {
                "slug": concept.get("slug"),
                "title": concept.get("title"),
                "aliases": concept.get("aliases", []),
                "definition": concept.get("definition", ""),
                "evidence": concept.get("evidence", []),
                "why_page_worthy": concept.get("why_page_worthy", ""),
            }
            for concept in concepts
            if concept.get("slug")
        ],
        "incoming_candidates": [
            {
                "candidate_id": item["candidate_id"],
                "term": item["term"],
                "suggested_slug": item["slug"],
                "claim": item["claim"],
                "refs": item["refs"],
                "candidate_type": item["candidate_type"],
            }
            for item in candidates
        ],
    }
    raw = api_client.complete_json(system_prompt, json.dumps(payload, ensure_ascii=False, indent=2))
    decisions = raw.get("decisions", [])
    if not isinstance(decisions, list):
        return []
    valid_candidate_ids = {item["candidate_id"] for item in candidates}
    valid_concept_slugs = {str(concept.get("slug")) for concept in concepts if concept.get("slug")}
    normalized_decisions: list[dict[str, Any]] = []
    for item in decisions:
        if not isinstance(item, dict):
            continue
        candidate_id = str(item.get("candidate_id") or "")
        if candidate_id not in valid_candidate_ids:
            continue
        decision = str(item.get("decision") or "not_same_concept")
        concept_slug = str(item.get("concept_slug") or "")
        relation = str(item.get("relation") or "")
        if relation not in {
            "part_of",
            "child_of",
            "uses_or_depends_on",
            "contrasts_with",
            "supports_or_enables",
            "related_evidence",
            "insufficient_evidence",
        }:
            relation = ""
        if decision == "same_concept" and concept_slug in valid_concept_slugs:
            relation = "same_concept"
        elif decision == "relation_candidate" and concept_slug in valid_concept_slugs and relation:
            pass
        else:
            decision = "not_same_concept"
            concept_slug = ""
            relation = ""
        normalized_decisions.append(
            {
                "candidate_id": candidate_id,
                "decision": decision,
                "concept_slug": concept_slug,
                "relation": relation,
                "reason": item.get("reason"),
            }
        )
    return normalized_decisions


def _run_wiki_generation_graph(
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
) -> tuple[list[dict[str, Any]], dict[str, Any], list[dict[str, Any]]]:
    graph = StateGraph(_WikiGenerationGraphState)

    def generate_semantics(state: _WikiGenerationGraphState) -> _WikiGenerationGraphState:
        attempt = int(state.get("attempt", 1))
        notes = _run_semantic_extraction(
            api_client=api_client,
            system_prompt=state["semantic_prompt_for_attempt"],
            packets=packets,
            raw_dir=raw_dir,
            log=log,
            attempt=attempt,
        )
        log.emit("3. 의미 추출 완료", "의미 노트 목록을 정규화 단계 입력으로 전달합니다.", {"시도": attempt, "노트 수": len(notes)})
        normalized = normalizer.normalize_notes(notes)
        return {**state, "notes": notes, "normalized": normalized}

    def should_evaluate(state: _WikiGenerationGraphState) -> str:
        return "evaluate" if wiki_evaluation_loop else "finished"

    def evaluate_generation(state: _WikiGenerationGraphState) -> _WikiGenerationGraphState:
        attempt = int(state.get("attempt", 1))
        evaluation = _evaluate_generation(
            api_client=api_client,
            evaluator_prompt=wiki_evaluator_system_prompt,
            document=document,
            blocks=blocks,
            normalized=state["normalized"],
        )
        generation_evaluations = [*state.get("generation_evaluations", []), evaluation]
        if save_debug_json:
            write_json(ensure_dir(out / "raw_llm_outputs" / "wiki_evaluation") / f"attempt_{attempt:02d}.json", evaluation)
        log.emit(
            "3-평가. Wiki 생성 평가",
            "정규화된 의미 구조를 평가했습니다.",
            {
                "시도": attempt,
                "passed": evaluation.get("passed"),
                "retry": evaluation.get("retry_recommended"),
                "overall": (evaluation.get("scores") or {}).get("overall"),
                "issue 수": len(evaluation.get("issues", [])),
            },
        )
        return {**state, "evaluation": evaluation, "generation_evaluations": generation_evaluations}

    def repair_generation(state: _WikiGenerationGraphState) -> _WikiGenerationGraphState:
        attempt = int(state.get("attempt", 1))
        normalized = state["normalized"]
        evaluation = state["evaluation"]
        if evaluation is None:
            return state
        repaired_normalized, repair_operations = _repair_normalized_from_evaluation(normalized, evaluation)
        if not repair_operations:
            return state
        repair_evaluation = _evaluate_generation(
            api_client=api_client,
            evaluator_prompt=wiki_evaluator_system_prompt,
            document=document,
            blocks=blocks,
            normalized=repaired_normalized,
        )
        repair_evaluation["repair_operations"] = repair_operations
        generation_evaluations = [*state.get("generation_evaluations", []), repair_evaluation]
        if save_debug_json:
            write_json(ensure_dir(out / "raw_llm_outputs" / "wiki_evaluation") / f"attempt_{attempt:02d}.repair.json", repair_evaluation)
        log.emit(
            "3-평가-보정. Wiki 생성 보정",
            "평가 issue를 바탕으로 명확한 observation 문제를 자동 보정하고 다시 평가했습니다.",
            {
                "시도": attempt,
                "보정 수": len(repair_operations),
                "passed": repair_evaluation.get("passed"),
                "retry": repair_evaluation.get("retry_recommended"),
                "overall": (repair_evaluation.get("scores") or {}).get("overall"),
                "issue 수": len(repair_evaluation.get("issues", [])),
            },
        )
        return {
            **state,
            "normalized": repaired_normalized,
            "evaluation": repair_evaluation,
            "generation_evaluations": generation_evaluations,
        }

    def route_after_evaluation(state: _WikiGenerationGraphState) -> str:
        evaluation = state.get("evaluation") or {}
        attempt = int(state.get("attempt", 1))
        if not evaluation.get("retry_recommended") or evaluation.get("passed") or attempt >= max_eval_attempts:
            return "finished"
        return "retry"

    def prepare_retry(state: _WikiGenerationGraphState) -> _WikiGenerationGraphState:
        evaluation = state.get("evaluation") or {}
        feedback = str(evaluation.get("retry_feedback") or "")
        semantic_prompt_for_attempt = (
            semantic_system_prompt
            + "\n\nEvaluator feedback for retry:\n"
            + feedback
            + "\nApply this feedback strictly. Keep source anchors exact. Return the same JSON schema."
        )
        return {
            **state,
            "attempt": int(state.get("attempt", 1)) + 1,
            "semantic_prompt_for_attempt": semantic_prompt_for_attempt,
        }

    graph.add_node("generate_semantics", generate_semantics)
    graph.add_node("evaluate_generation", evaluate_generation)
    graph.add_node("repair_generation", repair_generation)
    graph.add_node("prepare_retry", prepare_retry)
    graph.set_entry_point("generate_semantics")
    graph.add_conditional_edges(
        "generate_semantics",
        should_evaluate,
        {
            "evaluate": "evaluate_generation",
            "finished": END,
        },
    )
    graph.add_edge("evaluate_generation", "repair_generation")
    graph.add_conditional_edges(
        "repair_generation",
        route_after_evaluation,
        {
            "finished": END,
            "retry": "prepare_retry",
        },
    )
    graph.add_edge("prepare_retry", "generate_semantics")

    result = graph.compile().invoke(
        {
            "attempt": 1,
            "semantic_prompt_for_attempt": semantic_system_prompt,
            "notes": [],
            "normalized": {},
            "evaluation": None,
            "generation_evaluations": [],
        }
    )
    return result["notes"], result["normalized"], result["generation_evaluations"]


def run_pipeline(args: argparse.Namespace) -> dict:
    load_env_file(args.env_file)
    resolve_api_defaults(args)
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
            "Source Page 모드": source_page_mode(args),
            "Concept Page 모드": concept_page_mode(args),
        },
    )

    semantic_system_prompt = read_prompt(args.system_prompt)
    concept_system_prompt = read_prompt(args.concept_system_prompt)
    concept_resolution_system_prompt = read_prompt(args.concept_resolution_system_prompt)
    section_polish_system_prompt = read_prompt(args.section_polish_system_prompt)
    wiki_evaluator_system_prompt = read_prompt(args.wiki_evaluator_system_prompt)
    log.emit(
        "프롬프트 로드",
        "시스템 프롬프트를 메모리에 로드했습니다.",
        {
            "semantic": args.system_prompt,
            "concept": args.concept_system_prompt,
            "concept_resolution": args.concept_resolution_system_prompt,
            "section_polish": args.section_polish_system_prompt,
            "wiki_evaluator": args.wiki_evaluator_system_prompt,
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
    if input_text is not None:
        document, blocks = extractor.extract_text(input_text, source_path=input_source_name, fallback_title=Path(input_source_name).stem)
    else:
        document, blocks = extractor.extract(input_path)
    source_document_id = getattr(args, "source_document_id", None)
    if source_document_id:
        document.document_id = source_document_id
        for block in blocks:
            block.document_id = source_document_id
    if args.save_debug_json:
        write_json(out / "document.json", asdict(document))
        write_json(out / "block_map.json", {b.block_id: b.source_reference_id for b in blocks})
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
    raw_dir = ensure_dir(out / "raw_llm_outputs" / "semantic_extraction") if args.save_debug_json else None
    normalizer = SemanticNormalizer(document, blocks)
    max_eval_attempts = max(1, int(getattr(args, "max_eval_attempts", 2) or 2))
    notes, normalized, generation_evaluations = _run_wiki_generation_graph(
        api_client=api_client,
        semantic_system_prompt=semantic_system_prompt,
        wiki_evaluator_system_prompt=wiki_evaluator_system_prompt,
        packets=packets,
        raw_dir=raw_dir,
        log=log,
        normalizer=normalizer,
        document=document,
        blocks=blocks,
        out=out,
        save_debug_json=args.save_debug_json,
        wiki_evaluation_loop=getattr(args, "wiki_evaluation_loop", False),
        max_eval_attempts=max_eval_attempts,
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
    existing_concepts = getattr(args, "existing_concept_index", None)
    if existing_concepts is None:
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
    if args.save_debug_json:
        write_json(out / "normalized.json", normalized)
        if generation_evaluations:
            write_json(out / "wiki_generation_evaluations.json", generation_evaluations)

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
    source_page = SourcePageAssembler().build(normalized, polish=source_polish)
    source_artifact = normalized.get("source_extraction_artifact")
    log.emit(
        "5. Source Page 생성",
        "백엔드 조립 방식으로 source page markdown 데이터를 생성했습니다.",
        {"source_page": source_page.get("markdown_path"), "source_json": bool(source_artifact), "mode": sp_mode},
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
        concept_pages = ConceptPageAssembler().build_top(
            normalized,
            top_n=None,
            polish_by_slug=concept_polish_by_slug,
            source_key_points=source_key_points_for_concepts,
        )
        log.emit("6. Concept Page 생성", "백엔드 조립과 섹션 polish로 concept page markdown 데이터를 생성했습니다.", {"페이지 수": len(concept_pages)})
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
        concept_pages = generator_assembler.build_pages(generated_concept_pages)
    else:
        concept_pages = ConceptPageAssembler().build_top(
            normalized,
            top_n=None,
            source_key_points=source_key_points_for_concepts,
        )
        log.emit("6. Concept Page 생성", "Backend skeleton 방식으로 concept page markdown 데이터를 생성했습니다.", {"페이지 수": len(concept_pages)})

    links = LinkBuilder().build(normalized, generated_concept_pages=generated_concept_pages)
    log.emit(
        "7. 링크 생성",
        "위키 링크 데이터를 생성했습니다.",
        {"링크 수": len(links)},
    )

    meaning_cluster_assembler = MeaningClusterArtifactAssembler()
    meaning_cluster_candidates = meaning_cluster_assembler.candidate_claims(normalized)
    concept_candidates = [
        *normalized.get("concept_ledger", []),
        *normalized.get("existing_concept_index", []),
    ]
    concept_update_decisions = _judge_concept_update_candidates(
        api_client=api_client,
        concepts=concept_candidates,
        candidates=meaning_cluster_candidates,
    )
    concept_update_by_candidate = {
        item["candidate_id"]: item
        for item in concept_update_decisions
        if item.get("decision") == "same_concept"
    }
    candidates_by_id = {candidate["candidate_id"]: candidate for candidate in meaning_cluster_candidates}
    core_relation_decisions = [
        item
        for item in concept_update_decisions
        if item.get("decision") == "relation_candidate"
    ]
    cluster_judge_candidates = [
        item
        for item in meaning_cluster_candidates
        if item["candidate_id"] not in concept_update_by_candidate
    ]
    log.emit(
        "8-보조. Concept 갱신 후보 판단",
        "section/mention evidence claim이 이미 존재하는 core concept에 속하는지 먼저 판단했습니다.",
        {
            "candidate 수": len(meaning_cluster_candidates),
            "same_concept 수": len(concept_update_by_candidate),
            "relation_candidate 수": len(core_relation_decisions),
            "cluster judge 대상": len(cluster_judge_candidates),
        },
    )
    existing_active_clusters = _read_existing_active_clusters(args.user_id, args.workspace_id)
    cluster_decisions = _judge_meaning_cluster_candidates(
        api_client=api_client,
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
    meaning_cluster_artifact = meaning_cluster_assembler.assemble(
        normalized,
        out,
        user_id=args.user_id,
        workspace_id=args.workspace_id,
        cluster_decisions=cluster_decisions,
        core_relation_decisions=core_relation_decisions,
        concept_update_decisions=[
            {
                **item,
                "claim_id": candidates_by_id.get(item["candidate_id"], {}).get("claim_id"),
                "claim": candidates_by_id.get(item["candidate_id"], {}).get("claim"),
                "refs": candidates_by_id.get(item["candidate_id"], {}).get("refs", []),
                "candidate_type": candidates_by_id.get(item["candidate_id"], {}).get("candidate_type"),
            }
            for item in concept_update_by_candidate.values()
        ],
    )
    maintenance_summary = meaning_cluster_artifact.get("maintenance_summary", {})
    log.emit(
        "8. Meaning Cluster 생성",
        "section/mention evidence claim 기반 active cluster와 ingest log artifact를 생성했습니다.",
        {
            "active": meaning_cluster_artifact["active_path"],
            "log": meaning_cluster_artifact["log_path"],
            "cluster 수": len(meaning_cluster_artifact["clusters"]),
            "promotion 후보 수": maintenance_summary.get("promotion_candidate_count", 0),
            "relation 후보 수": maintenance_summary.get("relation_candidate_count", 0),
            "invalid 후보 수": maintenance_summary.get("invalid_candidate_count", 0),
        },
    )

    manifest = {
        "input": input_source_name if input_text is not None else str(input_path),
        "out": str(out),
        "mode": args.mode,
        "user_id": args.user_id,
        "workspace_id": args.workspace_id,
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
        "wiki_generation_evaluation_loop": getattr(args, "wiki_evaluation_loop", False),
        "wiki_generation_evaluation_count": len(generation_evaluations),
        "wiki_generation_final_evaluation": generation_evaluations[-1] if generation_evaluations else None,
        "source_page": source_page,
        "source_extraction_artifact": _json_safe(normalized.get("source_extraction_artifact")),
        "source_blocks": source_block_records,
        "concept_pages": concept_pages,
        "links": links,
        "meaning_clusters": meaning_cluster_artifact,
        "maintenance_summary": maintenance_summary,
        "normalized": normalized,
        "wiki_generation_evaluations": generation_evaluations,
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


def main() -> None:
    manifest = run_pipeline(parse_args())
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
