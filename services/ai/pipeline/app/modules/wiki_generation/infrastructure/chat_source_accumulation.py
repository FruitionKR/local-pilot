from __future__ import annotations

import json
from typing import Any, Sequence

from app.modules.wiki_generation.domain.text_utils import unique_keep_order


def build_chat_source_accumulation_payload(
    normalized: dict[str, Any],
    existing_source_markdown: str | None,
) -> dict[str, Any]:
    return {
        "context": {
            "document": normalized["document"],
            "existing_source_markdown": existing_source_markdown or "",
            "existing_source_summary": normalized.get("existing_source_context", {}).get("summary", ""),
        },
        "draft": {
            "accumulated_summary_candidates": [
                note.get("semantic_summary", "")
                for note in normalized.get("semantic_notes", [])
                if note.get("semantic_summary")
            ],
            "key_points": [
                key_point
                for note in normalized.get("semantic_notes", [])
                for key_point in note.get("key_points", [])
            ],
            "observations": normalized.get("observations", []),
            "categories": normalized.get("categories", []),
        },
    }


def apply_chat_source_accumulation_result(
    normalized: dict[str, Any],
    raw: dict[str, Any],
    source_blocks: Sequence[Any],
) -> dict[str, Any]:
    valid_refs = {block.block_id for block in source_blocks}
    revised = raw.get("revised_source", {}) if isinstance(raw.get("revised_source"), dict) else {}
    warnings = normalized.setdefault("warnings", [])

    def clean_refs(refs: list[str], context: str) -> list[str]:
        cleaned = []
        for ref in refs or []:
            if ref not in valid_refs:
                warnings.append(f"{context}: unknown accumulation anchor_block_id {ref}")
                continue
            if ref not in cleaned:
                cleaned.append(ref)
        return cleaned

    summary = revised.get("summary", {}) if isinstance(revised.get("summary"), dict) else {}
    key_points = [
        {
            "text": str(item.get("text") or "").strip(),
            "anchor_reference_ids": clean_refs(item.get("anchor_block_ids", []), "source_accumulation.key_points"),
        }
        for item in revised.get("key_points", []) or []
        if isinstance(item, dict) and str(item.get("text") or "").strip()
    ]
    observations = []
    for item in revised.get("observations", []) or []:
        if not isinstance(item, dict):
            continue
        observations.append(
            {
                "type": str(item.get("type") or "source_claim"),
                "title": str(item.get("title") or "").strip(),
                "query_text": item.get("query_text"),
                "summary": str(item.get("summary") or "").strip(),
                "claims": item.get("claims", []) if isinstance(item.get("claims"), list) else [],
                "related_concept_hints": item.get("related_concept_hints", []) if isinstance(item.get("related_concept_hints"), list) else [],
                "anchor_reference_ids": clean_refs(item.get("anchor_block_ids", []), "source_accumulation.observations"),
            }
        )
    categories = [
        {"name": str(item.get("name") or "").strip()}
        for item in revised.get("categories", []) or []
        if isinstance(item, dict) and str(item.get("name") or "").strip()
    ]

    existing_summary = [
        normalized.get("existing_source_context", {}).get("summary", ""),
        *(note.get("semantic_summary", "") for note in normalized.get("semantic_notes", [])),
    ]
    summary_text = _merge_summary(existing_summary, str(summary.get("text") or "").strip())
    key_points = _merge_key_points(
        [
            item
            for note in normalized.get("semantic_notes", [])
            for item in note.get("key_points", [])
            if isinstance(item, dict)
        ],
        key_points,
    )
    observations = _merge_items(normalized.get("observations", []), observations, "summary")
    categories = _merge_items(normalized.get("categories", []), categories, "name")

    if summary_text or key_points:
        normalized["source_accumulation_polish"] = {
            "summary": {
                "text": summary_text,
                "anchor_reference_ids": clean_refs(summary.get("anchor_block_ids", []), "source_accumulation.summary"),
            },
            "key_points": {"items": key_points},
        }
    normalized["observations"] = observations
    normalized["categories"] = categories
    normalized["source_accumulation_evaluation"] = raw
    return normalized


def _merge_summary(existing: list[Any], revised: str) -> str:
    merged = [str(item).strip() for item in existing if str(item or "").strip()]
    if not revised:
        return "\n\n".join(unique_keep_order(merged))
    if revised in merged:
        return "\n\n".join(unique_keep_order(merged))
    merged = [item for item in merged if item not in revised]
    merged.append(revised)
    return "\n\n".join(unique_keep_order(merged))


def _merge_key_points(existing: list[dict[str, Any]], revised: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    by_text: dict[str, dict[str, Any]] = {}
    for item in [*existing, *revised]:
        text = str(item.get("text") or "").strip()
        if not text:
            continue
        key = text
        if key in by_text:
            target = by_text[key]
            target["anchor_reference_ids"] = unique_keep_order(
                target.get("anchor_reference_ids", []) + item.get("anchor_reference_ids", [])
            )
            continue
        copied = {"text": text, "anchor_reference_ids": unique_keep_order(item.get("anchor_reference_ids", []))}
        by_text[key] = copied
        merged.append(copied)
    return merged


def _merge_items(existing: list[Any], revised: list[dict[str, Any]], field: str) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    by_text: dict[str, dict[str, Any]] = {}
    for item in [*existing, *revised]:
        if not isinstance(item, dict):
            continue
        value = str(item.get(field) or item.get("title") or item.get("name") or "").strip()
        if field == "summary":
            semantic_payload = {
                key: value
                for key, value in item.items()
                if key
                not in {
                    "anchor_reference_ids",
                    "anchor_block_ids",
                    "evidence_block_ids",
                    "observation_id",
                    "source_document_id",
                }
            }
            semantic_payload["type"] = semantic_payload.get("type") or "source_claim"
            semantic_payload["title"] = semantic_payload.get("title") or ""
            semantic_payload["query_text"] = semantic_payload.get("query_text")
            semantic_payload["summary"] = semantic_payload.get("summary") or ""
            semantic_payload["claims"] = semantic_payload.get("claims") or []
            semantic_payload["related_concept_hints"] = semantic_payload.get("related_concept_hints") or []
            key = json.dumps(semantic_payload, ensure_ascii=False, sort_keys=True)
        else:
            key = value
        if not key or key in by_text:
            if key in by_text:
                target = by_text[key]
                target["anchor_reference_ids"] = unique_keep_order(
                    target.get("anchor_reference_ids", []) + item.get("anchor_reference_ids", [])
                )
            continue
        copied = dict(item)
        copied["anchor_reference_ids"] = unique_keep_order(item.get("anchor_reference_ids", []))
        by_text[key] = copied
        merged.append(copied)
    return merged
