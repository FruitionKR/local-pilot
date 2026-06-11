from __future__ import annotations

from dataclasses import asdict
from typing import Any, Dict, Iterable, List

from .models import SourceBlock, SourceDocument, NormalizedConcept, NormalizedEvidence
from .text_utils import slugify, unique_keep_order, contains_ci


class PipelineValidationError(Exception):
    pass


class SemanticNormalizer:
    """Turns loose LLM semantic notes into backend-friendly normalized records."""

    def __init__(self, document: SourceDocument, blocks: list[SourceBlock]) -> None:
        self.document = document
        self.blocks = blocks
        self.by_block_id = {b.block_id: b for b in blocks}
        self.by_ref_id = {b.source_reference_id: b for b in blocks}

    def normalize_notes(self, notes: list[dict[str, Any]]) -> dict[str, Any]:
        warnings: list[str] = []
        normalized_notes = []
        concepts_by_slug: dict[str, NormalizedConcept] = {}
        evidence_rows: list[NormalizedEvidence] = []

        for note_idx, note in enumerate(notes):
            chunk_id = note.get("chunk_id") or f"chunk_{note_idx + 1:04d}"
            normalized_note = self._normalize_single_note(note, warnings)
            normalized_notes.append(normalized_note)

            for rank, c in enumerate(note.get("concept_candidates", []), start=1):
                concept = self._normalize_concept(c, warnings)
                if concept.slug not in concepts_by_slug:
                    concept.importance_score += max(0, 10 - rank)
                    concepts_by_slug[concept.slug] = concept
                else:
                    self._merge_concept(concepts_by_slug[concept.slug], concept)
                    concepts_by_slug[concept.slug].importance_score += max(0, 10 - rank) * 0.5

            for ev_idx, ev in enumerate(note.get("evidence_claims", []), start=1):
                evidence_id = f"ev_{len(evidence_rows) + 1:04d}"
                row = self._normalize_evidence(evidence_id, ev, warnings)
                evidence_rows.append(row)

        # Attach evidence to concepts.
        for ev in evidence_rows:
            for slug in ev.related_concept_slugs:
                if slug in concepts_by_slug:
                    concepts_by_slug[slug].evidence_claim_ids.append(ev.evidence_id)
                    concepts_by_slug[slug].importance_score += 2 * max(0.0, min(1.0, ev.confidence))
                else:
                    warnings.append(f"evidence {ev.evidence_id} references missing concept slug: {slug}")

        # Backend expands direct mentions from all blocks by title/aliases.
        for concept in concepts_by_slug.values():
            mention_refs = self._expand_mentions(concept)
            concept.mention_reference_ids = unique_keep_order(concept.anchor_reference_ids + mention_refs)
            concept.mention_count = len(concept.mention_reference_ids)
            concept.display_reference_ids = concept.anchor_reference_ids[:3] or concept.mention_reference_ids[:3]
            concept.source_document_ids = unique_keep_order(concept.source_document_ids + [self.document.document_id])
            concept.importance_score += concept.mention_count * 0.25

        return {
            "document": asdict(self.document),
            "semantic_notes": normalized_notes,
            "concept_ledger": [asdict(c) for c in sorted(concepts_by_slug.values(), key=lambda c: (-c.importance_score, c.slug))],
            "evidence_units": [asdict(e) for e in evidence_rows],
            "warnings": warnings,
        }

    def _normalize_single_note(self, note: dict[str, Any], warnings: list[str]) -> dict[str, Any]:
        def map_anchor_ids(ids: Iterable[str], limit: int = 3) -> list[str]:
            out = []
            for bid in ids or []:
                if bid not in self.by_block_id:
                    warnings.append(f"unknown anchor_block_id: {bid}")
                    continue
                out.append(self.by_block_id[bid].source_reference_id)
                if len(out) >= limit:
                    break
            return unique_keep_order(out)

        return {
            "chunk_id": note.get("chunk_id"),
            "semantic_summary": note.get("semantic_summary", ""),
            "key_points": [
                {
                    "text": kp.get("text", ""),
                    "anchor_reference_ids": map_anchor_ids(kp.get("anchor_block_ids", []), limit=3),
                }
                for kp in note.get("key_points", [])
            ],
            "concept_candidates": [
                {
                    "title": c.get("title", ""),
                    "slug": slugify(c.get("slug_hint") or c.get("title", "")),
                    "anchor_reference_ids": map_anchor_ids(c.get("anchor_block_ids", []), limit=3),
                }
                for c in note.get("concept_candidates", [])
            ],
            "evidence_claims": [
                {
                    "claim": ev.get("claim", ""),
                    "anchor_reference_ids": map_anchor_ids(ev.get("anchor_block_ids", []), limit=3),
                    "related_concept_hints": [slugify(x) for x in ev.get("related_concept_hints", [])],
                    "confidence": ev.get("confidence", 0.0),
                }
                for ev in note.get("evidence_claims", [])
            ],
            "needs_neighbor_context": bool(note.get("needs_neighbor_context", False)),
            "context_problem": note.get("context_problem"),
        }

    def _anchor_refs(self, anchor_block_ids: Iterable[str], warnings: list[str], limit: int = 3) -> list[str]:
        refs = []
        for bid in anchor_block_ids or []:
            if bid not in self.by_block_id:
                warnings.append(f"unknown anchor_block_id: {bid}")
                continue
            refs.append(self.by_block_id[bid].source_reference_id)
            if len(refs) >= limit:
                break
        return unique_keep_order(refs)

    def _normalize_concept(self, c: dict[str, Any], warnings: list[str]) -> NormalizedConcept:
        title = c.get("title") or "Untitled Concept"
        slug = slugify(c.get("slug_hint") or title)
        aliases = unique_keep_order([str(a) for a in c.get("aliases", []) if a] + [title])
        anchor_refs = self._anchor_refs(c.get("anchor_block_ids", []), warnings, limit=3)
        return NormalizedConcept(
            slug=slug,
            title=title,
            aliases=aliases,
            definition=c.get("definition", ""),
            why_page_worthy=c.get("why_page_worthy", ""),
            anchor_reference_ids=anchor_refs,
            source_document_ids=[self.document.document_id],
        )

    def _merge_concept(self, target: NormalizedConcept, incoming: NormalizedConcept) -> None:
        target.aliases = unique_keep_order(target.aliases + incoming.aliases)
        if len(incoming.definition) > len(target.definition):
            target.definition = incoming.definition
        if len(incoming.why_page_worthy) > len(target.why_page_worthy):
            target.why_page_worthy = incoming.why_page_worthy
        target.anchor_reference_ids = unique_keep_order(target.anchor_reference_ids + incoming.anchor_reference_ids)
        target.source_document_ids = unique_keep_order(target.source_document_ids + incoming.source_document_ids)

    def _normalize_evidence(self, evidence_id: str, ev: dict[str, Any], warnings: list[str]) -> NormalizedEvidence:
        refs = self._anchor_refs(ev.get("anchor_block_ids", []), warnings, limit=3)
        slugs = [slugify(x) for x in ev.get("related_concept_hints", [])]
        confidence = ev.get("confidence", 0.0)
        try:
            confidence = float(confidence)
        except Exception:
            confidence = 0.0
        return NormalizedEvidence(
            evidence_id=evidence_id,
            claim=ev.get("claim", ""),
            anchor_reference_ids=refs,
            related_concept_slugs=unique_keep_order(slugs),
            confidence=max(0.0, min(1.0, confidence)),
            source_document_id=self.document.document_id,
        )

    def _expand_mentions(self, concept: NormalizedConcept) -> list[str]:
        # Exact-ish lexical expansion. This is backend calculation, not LLM output.
        # In production, this can be replaced with hybrid search + optional LLM yes/no verifier.
        needles = unique_keep_order([concept.title] + concept.aliases + [concept.slug.replace("-", " ")])
        refs = []
        for b in self.blocks:
            if any(self._direct_mention(b.text, n) for n in needles):
                refs.append(b.source_reference_id)
        return unique_keep_order(refs)

    def _direct_mention(self, text: str, needle: str) -> bool:
        needle = needle.strip()
        if not needle:
            return False
        # For Korean aliases, substring is acceptable. For English, require substring
        # across case; this keeps the prototype simple.
        return contains_ci(text, needle)
