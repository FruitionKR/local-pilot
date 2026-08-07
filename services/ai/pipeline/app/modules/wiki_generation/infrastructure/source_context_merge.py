from __future__ import annotations

import copy
from typing import Any

from app.modules.wiki_generation.domain.entities import SourceBlock
from app.modules.wiki_generation.domain.text_utils import slugify, unique_keep_order


def active_source_artifact(
    source_artifact: dict[str, Any] | None,
    active_block_ids: list[str],
    *,
    current_has_blocks: bool,
) -> dict[str, Any] | None:
    if not source_artifact:
        return None
    active_refs = set(active_block_ids)
    result = copy.deepcopy(source_artifact)
    if not current_has_blocks:
        result["summary"] = ""
        for field in (
            "key_points",
            "categories",
            "observations",
            "core_concepts",
            "section_candidates",
            "mentions",
            "evidence_claims",
        ):
            result[field] = []
        return result

    for field in (
        "key_points",
        "categories",
        "observations",
        "core_concepts",
        "section_candidates",
        "mentions",
        "evidence_claims",
    ):
        result[field] = _active_artifact_items(
            result.get(field, []),
            active_refs,
        )
    return result


def apply_same_source_core_context(
    normalized: dict[str, Any],
    source_artifact: dict[str, Any] | None,
) -> tuple[dict[str, Any], list[SourceBlock]]:
    if not source_artifact:
        return normalized, []
    result = copy.deepcopy(normalized)
    contexts = _source_context_candidates(source_artifact)
    if not contexts:
        return result, []

    context_blocks = []
    evidence_units = list(result.get("evidence_units", []))
    for concept in result.get("concept_ledger", []):
        matches = [context for context in contexts if _matches_concept(concept, context)]
        if not matches:
            continue
        refs = unique_keep_order([ref for context in matches for ref in context.get("refs", [])])
        evidence_ids = []
        for index, context in enumerate(matches, start=1):
            evidence_id = f"same_source_context_{concept['slug']}_{index:03d}"
            evidence_ids.append(evidence_id)
            evidence_units.append(
                {
                    "evidence_id": evidence_id,
                    "claim": context["claim"],
                    "source_document_id": source_artifact.get("document_id"),
                    "anchor_reference_ids": context["refs"],
                    "related_concept_slugs": [concept["slug"]],
                    "confidence": 0.8,
                    "origin": "same_source_context",
                }
            )
            context_blocks.extend(_context_blocks(source_artifact, context))
        concept["display_reference_ids"] = unique_keep_order(concept.get("display_reference_ids", []) + refs)
        concept["anchor_reference_ids"] = unique_keep_order(concept.get("anchor_reference_ids", []) + refs)
        concept["evidence_claim_ids"] = unique_keep_order(concept.get("evidence_claim_ids", []) + evidence_ids)
        source_document_id = source_artifact.get("document_id")
        if source_document_id:
            concept["source_document_ids"] = unique_keep_order(concept.get("source_document_ids", []) + [source_document_id])
        result.setdefault("same_source_context_merges", []).append(
            {
                "slug": concept["slug"],
                "refs": refs,
                "evidence_ids": evidence_ids,
            }
        )

    core_concepts = result.get("concept_ledger", [])
    result["section_candidates"] = [
        item
        for item in result.get("section_candidates", [])
        if not _matches_any_concept(item, core_concepts)
    ]
    result["mentions"] = [
        item
        for item in result.get("mentions", [])
        if not _matches_any_concept(item, core_concepts)
    ]
    if context_blocks:
        result["evidence_units"] = evidence_units
    return result, _unique_blocks(context_blocks)


def source_page_context_normalized(normalized: dict[str, Any], source_artifact: dict[str, Any] | None) -> dict[str, Any]:
    if not source_artifact:
        return normalized
    result = copy.deepcopy(normalized)
    existing_context = _existing_source_context_note(source_artifact)
    if existing_context:
        result.setdefault("semantic_notes", [])
        result["semantic_notes"] = [existing_context, *result["semantic_notes"]]

    result["categories"] = _merge_categories(source_artifact.get("categories", []), result.get("categories", []))
    result["observations"] = _merge_observations(source_artifact.get("observations", []), result.get("observations", []))
    result["evidence_units"] = _merge_evidence(source_artifact.get("evidence_claims", []), result.get("evidence_units", []))

    new_concepts = result.get("concept_ledger", [])
    existing_concepts = [_artifact_core_concept(item, source_artifact) for item in source_artifact.get("core_concepts", [])]
    result["concept_ledger"] = _merge_term_items(existing_concepts, new_concepts, "title")
    core_concepts = result["concept_ledger"]
    core_slugs = {slugify(str(item.get("slug") or item.get("title") or "")) for item in core_concepts}

    existing_sections = [
        _artifact_section(item)
        for item in source_artifact.get("section_candidates", [])
        if not _matches_any_concept(item, core_concepts)
    ]
    new_sections = [
        item
        for item in result.get("section_candidates", [])
        if slugify(str(item.get("slug") or item.get("title") or "")) not in core_slugs
        and not _matches_any_concept(item, core_concepts)
    ]
    result["section_candidates"] = _merge_term_items(existing_sections, new_sections, "title")

    existing_mentions = [
        _artifact_mention(item)
        for item in source_artifact.get("mentions", [])
        if not _matches_any_concept(item, core_concepts)
    ]
    new_mentions = [
        item
        for item in result.get("mentions", [])
        if slugify(str(item.get("slug") or item.get("name") or "")) not in core_slugs
        and not _matches_any_concept(item, core_concepts)
    ]
    result["mentions"] = _merge_term_items(existing_mentions, new_mentions, "name")
    result["existing_source_context"] = {
        "summary": source_artifact.get("summary") or "",
        "source_markdown": source_artifact.get("source_markdown") or "",
    }
    return result


def source_context_blocks(source_artifact: dict[str, Any] | None) -> list[SourceBlock]:
    if not source_artifact:
        return []
    blocks = []
    for item in source_artifact.get("key_points", []):
        blocks.extend(_artifact_item_blocks(source_artifact, item, item.get("text") or "기존 핵심 포인트"))
    for item in source_artifact.get("observations", []):
        text = item.get("summary") or item.get("title") or "기존 observation"
        blocks.extend(_artifact_item_blocks(source_artifact, item, text))
    for item in [*source_artifact.get("core_concepts", []), *source_artifact.get("section_candidates", []), *source_artifact.get("mentions", [])]:
        text = item.get("context") or item.get("term") or item.get("title") or item.get("name") or "기존 source context"
        blocks.extend(_artifact_item_blocks(source_artifact, item, text))
    for item in source_artifact.get("evidence_claims", []):
        blocks.extend(_artifact_item_blocks(source_artifact, item, item.get("claim") or "기존 evidence"))
    return _unique_blocks(blocks)


def _source_context_candidates(source_artifact: dict[str, Any]) -> list[dict[str, Any]]:
    candidates = []
    for level, key in [("section", "section_candidates"), ("mention", "mentions")]:
        for item in source_artifact.get(key, []):
            refs = item.get("evidence_block_ids", []) or []
            if not refs:
                continue
            term = str(item.get("term") or item.get("title") or item.get("name") or item.get("slug") or "").strip()
            slug = slugify(str(item.get("slug") or term))
            if not slug:
                continue
            context = str(item.get("context") or term).strip()
            candidates.append(
                {
                    "level": level,
                    "term": term,
                    "slug": slug,
                    "aliases": item.get("aliases", []) or [],
                    "claim": context or term or slug,
                    "refs": refs,
                }
            )
    return candidates


def _existing_source_context_note(source_artifact: dict[str, Any]) -> dict[str, Any] | None:
    key_points = [
        {
            "text": item.get("text", ""),
            "anchor_reference_ids": item.get("evidence_block_ids", []) or item.get("anchor_reference_ids", []),
        }
        for item in source_artifact.get("key_points", [])
        if item.get("text")
    ]
    summary = str(source_artifact.get("summary") or "")
    if not key_points and not summary:
        return None
    return {
        "chunk_id": "existing_source_page",
        "semantic_summary": summary,
        "key_points": key_points,
    }


def _merge_categories(existing: list[Any], incoming: list[dict[str, Any]]) -> list[dict[str, Any]]:
    existing_items = [
        copy.deepcopy(item) if isinstance(item, dict) else {"name": str(item)}
        for item in existing
        if isinstance(item, dict) or str(item or "").strip()
    ]
    return _merge_by_text(existing_items, incoming, "name")


def _merge_observations(existing: list[dict[str, Any]], incoming: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return _merge_by_text(existing, incoming, "summary")


def _merge_evidence(existing: list[dict[str, Any]], incoming: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return _merge_by_text(existing, incoming, "claim")


def _merge_by_text(existing: list[dict[str, Any]], incoming: list[dict[str, Any]], field: str) -> list[dict[str, Any]]:
    merged = []
    seen = set()
    for item in [*existing, *incoming]:
        value = str(item.get(field) or item.get("title") or item.get("name") or "").strip()
        key = slugify(value)
        if not key or key in seen:
            continue
        seen.add(key)
        merged.append(copy.deepcopy(item))
    return merged


def _merge_term_items(existing: list[dict[str, Any]], incoming: list[dict[str, Any]], title_field: str) -> list[dict[str, Any]]:
    merged = []
    by_slug: dict[str, dict[str, Any]] = {}
    for item in [*existing, *incoming]:
        slug = slugify(str(item.get("slug") or item.get(title_field) or item.get("term") or ""))
        if not slug:
            continue
        if slug in by_slug:
            target = by_slug[slug]
            target["anchor_reference_ids"] = unique_keep_order(target.get("anchor_reference_ids", []) + item.get("anchor_reference_ids", []))
            target["display_reference_ids"] = unique_keep_order(target.get("display_reference_ids", []) + item.get("display_reference_ids", []))
            target["aliases"] = unique_keep_order(target.get("aliases", []) + item.get("aliases", []))
            continue
        copied = copy.deepcopy(item)
        by_slug[slug] = copied
        merged.append(copied)
    return merged


def _active_artifact_items(
    items: list[Any],
    active_refs: set[str],
) -> list[Any]:
    active_items = []
    for item in items:
        if not isinstance(item, dict):
            active_items.append(item)
            continue
        ref_field = next(
            (
                field
                for field in ("evidence_block_ids", "anchor_reference_ids")
                if field in item
            ),
            None,
        )
        if ref_field is None or not item.get(ref_field):
            active_items.append(copy.deepcopy(item))
            continue
        refs = [
            str(ref)
            for ref in item.get(ref_field, [])
            if str(ref) in active_refs
        ]
        if not refs:
            continue
        copied = copy.deepcopy(item)
        copied[ref_field] = refs
        active_items.append(copied)
    return active_items


def _matches_any_concept(item: dict[str, Any], concepts: list[dict[str, Any]]) -> bool:
    context = {
        "slug": slugify(str(item.get("slug") or item.get("term") or item.get("title") or item.get("name") or "")),
        "term": item.get("term") or item.get("title") or item.get("name") or item.get("slug"),
        "aliases": item.get("aliases", []) or [],
    }
    return any(_matches_concept(concept, context) for concept in concepts)


def _artifact_item_blocks(source_artifact: dict[str, Any], item: dict[str, Any], text: str) -> list[SourceBlock]:
    refs = item.get("evidence_block_ids", []) or item.get("anchor_reference_ids", [])
    if not refs:
        return []
    document_id = source_artifact.get("document_id") or ""
    return [
        SourceBlock(
            document_id=document_id,
            block_id=ref,
            source_reference_id=ref,
            text=str(text),
            line_start=0,
            line_end=0,
            section_path=["기존 source page context"],
            block_type="same_source_context",
        )
        for ref in refs
    ]


def _matches_concept(concept: dict[str, Any], context: dict[str, Any]) -> bool:
    concept_keys = _match_keys(concept.get("slug"), concept.get("title"), concept.get("aliases", []))
    context_keys = _match_keys(context.get("slug"), context.get("term"), context.get("aliases", []))
    if concept_keys & context_keys:
        return True
    for concept_key in concept_keys:
        for context_key in context_keys:
            if _token_subset_match(concept_key, context_key):
                return True
    return False


def _match_keys(slug: Any, title: Any, aliases: list[Any]) -> set[str]:
    values = [slug, title, *aliases]
    return {slugify(str(value)) for value in values if slugify(str(value))}


def _token_subset_match(left: str, right: str) -> bool:
    left_tokens = {token for token in left.split("-") if len(token) >= 4}
    right_tokens = {token for token in right.split("-") if len(token) >= 4}
    return bool(left_tokens and right_tokens and (left_tokens <= right_tokens or right_tokens <= left_tokens))


def _context_blocks(source_artifact: dict[str, Any], context: dict[str, Any]) -> list[SourceBlock]:
    document_id = source_artifact.get("document_id") or ""
    text = context["claim"]
    return [
        SourceBlock(
            document_id=document_id,
            block_id=ref,
            source_reference_id=ref,
            text=text,
            line_start=0,
            line_end=0,
            section_path=["기존 source page context"],
            block_type="same_source_context",
        )
        for ref in context["refs"]
    ]


def _unique_blocks(blocks: list[SourceBlock]) -> list[SourceBlock]:
    seen = set()
    unique_blocks = []
    for block in blocks:
        key = (block.document_id, block.block_id)
        if key in seen:
            continue
        seen.add(key)
        unique_blocks.append(block)
    return unique_blocks


def _artifact_core_concept(item: dict[str, Any], source_artifact: dict[str, Any]) -> dict[str, Any]:
    term = str(item.get("term") or item.get("slug") or "").strip()
    slug = slugify(str(item.get("slug") or term))
    refs = item.get("evidence_block_ids", []) or []
    source_document_id = source_artifact.get("document_id")
    return {
        "slug": slug,
        "title": term or slug,
        "definition": item.get("context") or "",
        "why_page_worthy": "기존 source page context",
        "aliases": unique_keep_order(item.get("aliases", []) + [term]),
        "display_reference_ids": refs,
        "anchor_reference_ids": refs,
        "mention_reference_ids": refs,
        "source_document_ids": [source_document_id] if source_document_id else [],
        "evidence_claim_ids": [],
        "mention_count": len(refs),
        "importance_score": 0.0,
    }


def _artifact_section(item: dict[str, Any]) -> dict[str, Any]:
    term = str(item.get("term") or item.get("slug") or "").strip()
    return {
        "title": term,
        "slug": slugify(str(item.get("slug") or term)),
        "context": item.get("context") or "",
        "anchor_reference_ids": item.get("evidence_block_ids", []) or [],
    }


def _artifact_mention(item: dict[str, Any]) -> dict[str, Any]:
    term = str(item.get("term") or item.get("slug") or "").strip()
    return {
        "name": term,
        "slug": slugify(str(item.get("slug") or term)),
        "context": item.get("context") or "",
        "anchor_reference_ids": item.get("evidence_block_ids", []) or [],
    }
