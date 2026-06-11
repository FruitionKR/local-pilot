from __future__ import annotations

import json
from typing import Any, Iterable, Sequence

from .models import SemanticPacket, SourceBlock


def render_semantic_user_prompt(packet: SemanticPacket) -> str:
    """User message for ChunkSemanticExtraction.

    The model sees only short local anchors such as [B0001]. The backend keeps
    the long source_reference_id map separately in block_map.json.
    """
    return f"""Stage input: ChunkSemanticExtraction

chunk_id: {packet.chunk_id}
document_id: {packet.document_id}

Read the following source blocks as one semantic packet. The [B0001] labels are local anchors. Use only these labels in anchor_block_ids.

SOURCE BLOCKS:
{packet.text}
""".rstrip() + "\n"


def build_messages(system_prompt: str, user_prompt: str) -> list[dict[str, str]]:
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]


def _unique(items: Iterable[str]) -> list[str]:
    seen = set()
    out = []
    for item in items:
        if not item or item in seen:
            continue
        seen.add(item)
        out.append(item)
    return out


def _all_ref_to_block_id_map(blocks: Sequence[SourceBlock]) -> dict[str, str]:
    return {b.source_reference_id: b.block_id for b in blocks}


def _blocks_by_ref_map(blocks: Sequence[SourceBlock]) -> dict[str, SourceBlock]:
    return {b.source_reference_id: b for b in blocks}


def collect_concept_source_blocks(concept: dict[str, Any], evidence_units: list[dict[str, Any]], blocks: Sequence[SourceBlock], max_blocks: int = 12) -> list[SourceBlock]:
    """Select blocks for optional ConceptPageGeneration prompt.

    This is deterministic and conservative: use display/anchor refs and linked
    evidence refs first, then a few mention refs. The prompt remains small.
    """
    by_ref = _blocks_by_ref_map(blocks)
    slug = concept.get("slug")
    refs: list[str] = []
    refs.extend(concept.get("display_reference_ids", []))
    refs.extend(concept.get("anchor_reference_ids", []))
    for ev in evidence_units:
        if slug and slug in ev.get("related_concept_slugs", []):
            refs.extend(ev.get("anchor_reference_ids", []))
    refs.extend(concept.get("mention_reference_ids", [])[: max(0, max_blocks - len(refs))])

    selected = []
    for ref in _unique(refs):
        block = by_ref.get(ref)
        if block is not None:
            selected.append(block)
        if len(selected) >= max_blocks:
            break
    return selected


def render_concept_page_user_prompt(concept: dict[str, Any], evidence_units: list[dict[str, Any]], source_blocks: Sequence[SourceBlock]) -> str:
    ref_to_bid = _all_ref_to_block_id_map(source_blocks)
    related_evidence = [ev for ev in evidence_units if concept.get("slug") in ev.get("related_concept_slugs", [])]
    evidence_json = json.dumps(
        [
            {
                "claim": ev.get("claim"),
                "anchor_block_ids": [ref_to_bid.get(ref, ref) for ref in ev.get("anchor_reference_ids", [])],
                "confidence": ev.get("confidence"),
            }
            for ev in related_evidence[:10]
        ],
        ensure_ascii=False,
        indent=2,
    )
    block_lines = "\n".join(b.to_llm_line() for b in source_blocks)
    concept_json = json.dumps(
        {
            "title": concept.get("title"),
            "slug": concept.get("slug"),
            "aliases": concept.get("aliases", []),
            "definition_draft": concept.get("definition"),
            "why_page_worthy": concept.get("why_page_worthy"),
            "display_block_ids": [ref_to_bid.get(ref, ref) for ref in concept.get("display_reference_ids", [])],
            "required_output_note": "definition must be {text, anchor_block_ids}; do not embed [B-id] citations in text fields",
        },
        ensure_ascii=False,
        indent=2,
    )
    return f"""Stage input: ConceptPageGeneration

Write one concept page from the supplied ledger card, evidence claims, and source blocks. Use only the supplied source blocks. Put citations only in anchor_block_ids; do not put [B0001] citations inside text fields.

CONCEPT LEDGER CARD:
{concept_json}

EVIDENCE CLAIMS:
{evidence_json}

SOURCE BLOCKS:
{block_lines}
""".rstrip() + "\n"
