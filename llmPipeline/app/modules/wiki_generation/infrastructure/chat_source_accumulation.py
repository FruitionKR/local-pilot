from __future__ import annotations

from typing import Any, Sequence


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

    if str(summary.get("text") or "").strip():
        normalized["source_accumulation_polish"] = {
            "summary": {
                "text": str(summary.get("text") or "").strip(),
                "anchor_reference_ids": clean_refs(summary.get("anchor_block_ids", []), "source_accumulation.summary"),
            },
            "key_points": {"items": key_points},
        }
    if observations:
        normalized["observations"] = observations
    if categories:
        normalized["categories"] = categories
    normalized["source_accumulation_evaluation"] = raw
    return normalized
