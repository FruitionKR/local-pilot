from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from app.modules.wiki_ingestion.infrastructure.file_io import write_json, write_text
from app.modules.wiki_generation.domain.entities import SourceBlock
from app.modules.wiki_generation.domain.text_utils import slugify, unique_keep_order


def ref_label(ref_id: str) -> str:
    m = re.search(r"_b(\d{4})$", ref_id)
    if m:
        return f"B{m.group(1)}"
    return ref_id


def cite_refs(refs: list[str]) -> str:
    refs = unique_keep_order([r for r in refs if r])
    return f" [{', '.join(ref_label(r) for r in refs)}]" if refs else ""


class SourcePageAssembler:
    def assemble(self, normalized: dict[str, Any], out_dir: str | Path, polish: dict[str, Any] | None = None) -> str:
        doc = normalized["document"]
        notes = normalized["semantic_notes"]
        ledger = normalized["concept_ledger"]
        polish = polish or {}
        title = str(polish.get("title") or doc.get("title") or doc["document_id"]).strip()

        summary_parts = [n.get("semantic_summary", "") for n in notes if n.get("semantic_summary")]
        summary = "\n\n".join(summary_parts[:4]) or "요약 없음."
        if polish.get("summary", {}).get("text"):
            summary = polish["summary"]["text"]

        key_points = []
        seen = set()
        polished_key_points = polish.get("key_points", {}).get("items", [])
        if polished_key_points:
            for kp in polished_key_points:
                text = kp.get("text", "").strip()
                if text:
                    key_points.append(f"- {text}{cite_refs(kp.get('anchor_reference_ids', []))}")
        else:
            for n in notes:
                for kp in n.get("key_points", []):
                    text = kp.get("text", "").strip()
                    if not text or text in seen:
                        continue
                    seen.add(text)
                    key_points.append(f"- {text}{cite_refs(kp.get('anchor_reference_ids', []))}")

        concept_lines = []
        for c in ledger:
            concept_lines.append(f"- [[{c['slug']}|{c['title']}]]{cite_refs(c.get('display_reference_ids', []))}")

        category_lines = [
            f"- {item.get('name')}"
            for item in normalized.get("categories", [])
            if item.get("name")
        ]
        section_lines = [
            _source_section_line(item)
            for item in normalized.get("section_candidates", [])
            if item.get("title") or item.get("slug")
        ]
        mention_lines = [
            _source_mention_line(item)
            for item in normalized.get("mentions", [])
            if item.get("name") or item.get("slug")
        ]
        observation_lines = [
            _source_observation_line(item)
            for item in normalized.get("observations", [])
            if item.get("title") or item.get("summary")
        ]

        md = f"""---
type: source
document_id: {doc['document_id']}
source_file: {doc['source_path']}
categories: {', '.join(item.get('name', '') for item in normalized.get('categories', []) if item.get('name')) or '-'}
---

# {title}

## Summary
{summary}

## Key Points
{chr(10).join(key_points) if key_points else '- 핵심 포인트 없음'}

## Observations
{chr(10).join(observation_lines) if observation_lines else '- observation 없음'}

## Categories
{chr(10).join(category_lines) if category_lines else '- 카테고리 없음'}

## Core Concepts
{chr(10).join(concept_lines) if concept_lines else '- core concept 없음'}

## Section Candidates
{chr(10).join(section_lines) if section_lines else '- section candidate 없음'}

## Mentions
{chr(10).join(mention_lines) if mention_lines else '- mention 없음'}
"""
        filename_slug = slugify(title)
        if filename_slug == "untitled":
            filename_slug = doc["document_id"]
        out_path = _unique_source_path(Path(out_dir) / "wiki" / "sources", filename_slug)
        write_text(out_path, md)
        artifact_path = out_path.with_suffix(".json")
        write_json(artifact_path, _source_extraction_artifact(normalized, title, summary, str(out_path)))
        normalized["source_extraction_artifact"] = str(artifact_path)
        return str(out_path)


def _term_record(item: dict[str, Any]) -> dict[str, Any]:
    term = item.get("term") or item.get("title") or item.get("name") or item.get("slug") or ""
    refs = unique_keep_order(item.get("anchor_reference_ids", []) or item.get("evidence_block_ids", []) or [])
    record = {
        "term": term,
        "slug": item.get("slug") or slugify(str(term)),
    }
    context = item.get("context") or item.get("definition") or ""
    if context:
        record["context"] = context
    if refs:
        record["evidence_block_ids"] = refs
    if item.get("aliases"):
        record["aliases"] = item.get("aliases")
    return record


def _source_extraction_artifact(
    normalized: dict[str, Any],
    title: str,
    summary: str,
    markdown_path: str,
) -> dict[str, Any]:
    doc = normalized["document"]
    core_concepts = [_term_record({**concept, "term": concept.get("title")}) for concept in normalized.get("concept_ledger", [])]
    section_candidates = [_term_record(item) for item in normalized.get("section_candidates", [])]
    mentions = [_term_record(item) for item in normalized.get("mentions", [])]
    categories = [item.get("slug") or slugify(str(item.get("name") or item.get("term") or "")) for item in normalized.get("categories", [])]
    categories = unique_keep_order([category for category in categories if category and category != "untitled"])
    return {
        "schema_version": "source-extraction.v1",
        "document_id": doc.get("document_id"),
        "title": title,
        "source_file": doc.get("source_path"),
        "markdown_path": markdown_path,
        "summary": summary,
        "key_points": [
            {"text": item.get("text", ""), "evidence_block_ids": _item_refs(item)}
            for note in normalized.get("semantic_notes", [])
            for item in note.get("key_points", [])
            if item.get("text")
        ],
        "categories": categories,
        "observations": normalized.get("observations", []),
        "core_concepts": core_concepts,
        "section_candidates": section_candidates,
        "mentions": mentions,
        "evidence_claims": normalized.get("evidence_units", []),
    }


def _source_section_line(item: dict[str, Any]) -> str:
    title = item.get("title") or item.get("slug") or "section"
    context = item.get("context") or ""
    suffix = f" - {context}" if context else ""
    return f"- {title}{suffix}{cite_refs(item.get('anchor_reference_ids', []))}"


def _source_observation_line(item: dict[str, Any]) -> str:
    observation_id = item.get("observation_id") or "O000"
    observation_type = item.get("type") or "source_claim"
    title = item.get("title") or "observation"
    query_text = item.get("query_text")
    summary = item.get("summary") or ""
    claims = item.get("claims", [])
    related = item.get("related_concept_hints", [])
    parts = [f"{observation_id} ({observation_type}) {title}"]
    if query_text:
        parts.append(f"query: {query_text}")
    if summary:
        parts.append(f"summary: {summary}")
    if claims:
        parts.append(f"claims: {'; '.join(claims[:3])}")
    if related:
        parts.append(f"related: {', '.join(related[:5])}")
    return f"- {' / '.join(parts)}{cite_refs(item.get('anchor_reference_ids', []))}"


def _source_mention_line(item: dict[str, Any]) -> str:
    name = item.get("name") or item.get("slug") or "mention"
    context = item.get("context") or ""
    suffix = f" - {context}" if context else ""
    return f"- {name}{suffix}{cite_refs(item.get('anchor_reference_ids', []))}"


def _unique_source_path(source_dir: Path, filename_slug: str) -> Path:
    candidate = source_dir / f"{filename_slug}.md"
    if not candidate.exists():
        return candidate
    i = 2
    while True:
        candidate = source_dir / f"{filename_slug}-{i}.md"
        if not candidate.exists():
            return candidate
        i += 1


class ConceptPageAssembler:
    def assemble_top(
        self,
        normalized: dict[str, Any],
        out_dir: str | Path,
        top_n: int | None = 6,
        polish_by_slug: dict[str, Any] | None = None,
        source_key_points: list[dict[str, Any]] | None = None,
    ) -> list[str]:
        """Deterministic skeleton concept pages.

        This is useful when concept page LLM generation is disabled.
        """
        out_paths = []
        evidence = normalized["evidence_units"]
        polish_by_slug = polish_by_slug or {}
        concepts = normalized["concept_ledger"] if top_n is None else normalized["concept_ledger"][:top_n]
        ledger_by_slug = {concept["slug"]: concept for concept in normalized["concept_ledger"]}
        source_key_points = source_key_points or _collect_source_key_points(normalized)
        for c in concepts:
            polish = polish_by_slug.get(c["slug"], {})
            related_evidence = _concept_evidence(c, evidence)
            ev_lines = []
            for ev in related_evidence:
                ev_lines.append(f"- {ev['claim']}{cite_refs(ev.get('anchor_reference_ids', []))}")
            definition_text = c.get("definition") or "정의 초안 없음."
            definition_refs = c.get("display_reference_ids", [])
            if polish.get("definition", {}).get("text"):
                definition_text = polish["definition"]["text"]
                definition_refs = polish["definition"].get("anchor_reference_ids", definition_refs)
            if not ev_lines and c.get("definition"):
                ev_lines.append(f"- {c['definition']}{cite_refs(definition_refs)}")
            key_point_lines = []
            for item in polish.get("key_points", {}).get("items", []):
                text = item.get("text", "").strip()
                if text:
                    key_point_lines.append(f"- {text}{cite_refs(item.get('anchor_reference_ids', []))}")
            if not key_point_lines:
                key_point_lines = _concept_key_points_from_source(c, related_evidence, source_key_points)
            if not key_point_lines and c.get("definition"):
                key_point_lines.append(f"- {c['definition']}{cite_refs(definition_refs)}")
            related_lines = []
            for target_slug in polish.get("related_concept_hints", []):
                target_slug = slugify(str(target_slug))
                target = ledger_by_slug.get(target_slug)
                if target and target_slug != c["slug"]:
                    related_lines.append(f"- [[{target_slug}|{target.get('title') or target_slug}]]")
            if not related_lines:
                related_lines = _concept_related_lines(c["slug"], normalized, ledger_by_slug, source_key_points)
            aliases = ", ".join(c.get("aliases", []))
            md = f"""---
type: concept
slug: {c['slug']}
sources: {', '.join(c.get('source_document_ids', []))}
mention_count: {c.get('mention_count', 0)}
importance_score: {round(c.get('importance_score', 0), 2)}
generated_by: backend_skeleton
---

# {c['title']}

## Definition
{definition_text}{cite_refs(definition_refs)}

## Why It Matters
{c.get('why_page_worthy') or '중요도 설명 없음.'}

## Key Points
{chr(10).join(key_point_lines) if key_point_lines else '- 핵심 포인트 없음'}

## Aliases
{aliases or '-'}

## Evidence
{chr(10).join(ev_lines) if ev_lines else '- 아직 연결된 evidence claim 없음'}

## Related Concepts
{chr(10).join(related_lines) if related_lines else '- 관련 개념 없음'}

## Reference Summary
- display refs: {', '.join(ref_label(r) for r in c.get('display_reference_ids', [])) or '-'}
- mention_count: {c.get('mention_count', 0)}
"""
            out_path = Path(out_dir) / "wiki" / "concepts" / f"{c['slug']}.md"
            write_text(out_path, md)
            out_paths.append(str(out_path))
        return out_paths


def _collect_source_key_points(normalized: dict[str, Any]) -> list[dict[str, Any]]:
    key_points = []
    seen = set()
    for note in normalized.get("semantic_notes", []):
        for item in note.get("key_points", []):
            text = str(item.get("text", "")).strip()
            refs = _item_refs(item)
            if not text or text in seen:
                continue
            seen.add(text)
            key_points.append({"text": text, "anchor_reference_ids": refs})
    return key_points


def _item_refs(item: dict[str, Any]) -> list[str]:
    return unique_keep_order(item.get("anchor_reference_ids", []) or item.get("anchor_block_ids", []))


def _concept_refs(concept: dict[str, Any], related_evidence: list[dict[str, Any]]) -> set[str]:
    refs: list[str] = []
    refs.extend(concept.get("display_reference_ids", []))
    refs.extend(concept.get("anchor_reference_ids", []))
    refs.extend(concept.get("mention_reference_ids", []))
    for ev in related_evidence:
        refs.extend(ev.get("anchor_reference_ids", []))
    return set(unique_keep_order(refs))


def _concept_base_refs(concept: dict[str, Any]) -> set[str]:
    refs: list[str] = []
    refs.extend(concept.get("display_reference_ids", []))
    refs.extend(concept.get("anchor_reference_ids", []))
    refs.extend(concept.get("mention_reference_ids", []))
    return set(unique_keep_order(refs))


def _concept_evidence(concept: dict[str, Any], evidence_units: list[dict[str, Any]]) -> list[dict[str, Any]]:
    slug = concept.get("slug")
    direct = [ev for ev in evidence_units if slug and slug in ev.get("related_concept_slugs", [])]
    if direct:
        return direct
    concept_refs = _concept_base_refs(concept)
    if not concept_refs:
        return []
    fallback = []
    for ev in evidence_units:
        ev_refs = set(ev.get("anchor_reference_ids", []))
        if concept_refs.intersection(ev_refs):
            fallback.append(ev)
    return fallback


def _concept_key_points_from_source(
    concept: dict[str, Any],
    related_evidence: list[dict[str, Any]],
    source_key_points: list[dict[str, Any]],
) -> list[str]:
    concept_refs = _concept_refs(concept, related_evidence)
    lines = []
    seen = set()
    used_refs: set[str] = set()
    for item in source_key_points:
        refs = _item_refs(item)
        text = str(item.get("text", "")).strip()
        if not text or text in seen:
            continue
        if concept_refs and not concept_refs.intersection(refs):
            continue
        if refs and used_refs.intersection(refs):
            continue
        seen.add(text)
        used_refs.update(refs)
        lines.append(f"- {text}{cite_refs(refs)}")
    return lines


def _concept_related_lines(
    slug: str,
    normalized: dict[str, Any],
    ledger_by_slug: dict[str, dict[str, Any]],
    source_key_points: list[dict[str, Any]] | None = None,
) -> list[str]:
    related_slugs: list[str] = []
    for ev in normalized.get("evidence_units", []):
        ev_slugs = [s for s in ev.get("related_concept_slugs", []) if s in ledger_by_slug]
        if slug in ev_slugs:
            related_slugs.extend([s for s in ev_slugs if s != slug])

    related_slugs.extend(
        target
        for source, target, _reason in _shared_key_point_pairs(normalized, ledger_by_slug, source_key_points or [])
        if source == slug
    )
    related_slugs.extend(
        source
        for source, target, _reason in _shared_key_point_pairs(normalized, ledger_by_slug, source_key_points or [])
        if target == slug
    )

    for resolution in normalized.get("concept_resolutions", []):
        source_slug = resolution.get("canonical_slug") or resolution.get("incoming_slug")
        targets = [target for target in resolution.get("link_targets", []) if target in ledger_by_slug]
        if source_slug == slug:
            related_slugs.extend(targets)
        elif slug in targets and source_slug in ledger_by_slug:
            related_slugs.append(source_slug)

    for resolution in normalized.get("hint_resolutions", []):
        source_slug = resolution.get("canonical_slug")
        targets = [target for target in resolution.get("link_targets", []) if target in ledger_by_slug]
        if source_slug == slug:
            related_slugs.extend(targets)
        elif slug in targets and source_slug in ledger_by_slug:
            related_slugs.append(source_slug)

    lines = []
    for target_slug in unique_keep_order([s for s in related_slugs if s and s != slug]):
        target = ledger_by_slug.get(target_slug)
        if target:
            lines.append(f"- [[{target_slug}|{target.get('title') or target_slug}]]")
    return lines


def _shared_key_point_pairs(
    normalized: dict[str, Any],
    ledger_by_slug: dict[str, dict[str, Any]],
    source_key_points: list[dict[str, Any]],
) -> list[tuple[str, str, str]]:
    concepts = list(ledger_by_slug.values())
    evidence = normalized.get("evidence_units", [])
    concept_refs_by_slug = {
        concept["slug"]: _concept_refs(concept, _concept_evidence(concept, evidence))
        for concept in concepts
    }
    pairs: list[tuple[str, str, str]] = []
    seen = set()
    for item in source_key_points or _collect_source_key_points(normalized):
        refs = set(_item_refs(item))
        if not refs:
            continue
        matched_slugs = sorted(
            slug
            for slug, concept_refs in concept_refs_by_slug.items()
            if concept_refs.intersection(refs)
        )
        for i, source_slug in enumerate(matched_slugs):
            for target_slug in matched_slugs[i + 1 :]:
                pair = (source_slug, target_slug)
                if pair in seen:
                    continue
                seen.add(pair)
                pairs.append((source_slug, target_slug, "shared_source_key_point"))
    return pairs


class GeneratedConceptPageAssembler:
    """Assemble concept pages from ConceptPageGeneration API outputs."""

    def _map_block_ids(self, anchor_block_ids: list[str], source_blocks: list[SourceBlock], warnings: list[str], context: str) -> list[str]:
        by_bid = {b.block_id: b.block_id for b in source_blocks}
        refs = []
        for bid in anchor_block_ids or []:
            if bid not in by_bid:
                warnings.append(f"{context}: unknown concept-page anchor_block_id {bid}")
                continue
            refs.append(by_bid[bid])
        return unique_keep_order(refs)

    def normalize_generated_output(
        self,
        concept: dict[str, Any],
        raw_page: dict[str, Any],
        source_blocks: list[SourceBlock],
        warnings: list[str],
    ) -> dict[str, Any]:
        definition = raw_page.get("definition", {})
        if isinstance(definition, str):
            warnings.append(f"{concept['slug']}: definition should be object; accepted string fallback")
            definition_obj = {"text": re.sub(r"\s*\[B\d{4}\](?:,\s*\[B\d{4}\])*\s*", "", definition).strip(), "anchor_block_ids": []}
        elif isinstance(definition, dict):
            definition_obj = definition
        else:
            definition_obj = {"text": "", "anchor_block_ids": []}
        normalized = {
            "slug": concept["slug"],
            "title": raw_page.get("title") or concept.get("title"),
            "definition": {
                "text": definition_obj.get("text", ""),
                "anchor_reference_ids": self._map_block_ids(definition_obj.get("anchor_block_ids", []), source_blocks, warnings, f"{concept['slug']}.definition"),
            },
            "key_points": [],
            "evidence": [],
            "related_concept_hints": raw_page.get("related_concept_hints", []),
            "confidence": raw_page.get("confidence", 0.0),
            "source_document_ids": concept.get("source_document_ids", []),
            "mention_count": concept.get("mention_count", 0),
            "importance_score": concept.get("importance_score", 0),
            "aliases": concept.get("aliases", []),
            "display_reference_ids": concept.get("display_reference_ids", []),
        }
        for i, kp in enumerate(raw_page.get("key_points", []) or [], start=1):
            normalized["key_points"].append(
                {
                    "text": kp.get("text", ""),
                    "anchor_reference_ids": self._map_block_ids(kp.get("anchor_block_ids", []), source_blocks, warnings, f"{concept['slug']}.key_points[{i}]"),
                }
            )
        for i, ev in enumerate(raw_page.get("evidence", []) or [], start=1):
            normalized["evidence"].append(
                {
                    "text": ev.get("text", ""),
                    "anchor_reference_ids": self._map_block_ids(ev.get("anchor_block_ids", []), source_blocks, warnings, f"{concept['slug']}.evidence[{i}]"),
                }
            )
        return normalized

    def assemble_pages(self, pages: list[dict[str, Any]], out_dir: str | Path) -> list[str]:
        out_paths = []
        for page in pages:
            definition = page.get("definition", {})
            kp_lines = [
                f"- {kp.get('text', '')}{cite_refs(kp.get('anchor_reference_ids', []))}"
                for kp in page.get("key_points", [])
                if kp.get("text")
            ]
            ev_lines = [
                f"- {ev.get('text', '')}{cite_refs(ev.get('anchor_reference_ids', []))}"
                for ev in page.get("evidence", [])
                if ev.get("text")
            ]
            rel_lines = []
            for hint in page.get("related_concept_hints", []):
                rel_lines.append(f"- [[{slugify(str(hint))}|{hint}]]")
            md = f"""---
type: concept
slug: {page['slug']}
sources: {', '.join(page.get('source_document_ids', []))}
mention_count: {page.get('mention_count', 0)}
importance_score: {round(page.get('importance_score', 0), 2)}
generated_by: llm_concept_page_generation
confidence: {page.get('confidence', 0.0)}
---

# {page.get('title') or page['slug']}

## Definition
{definition.get('text') or '정의 없음.'}{cite_refs(definition.get('anchor_reference_ids', []))}

## Key Points
{chr(10).join(kp_lines) if kp_lines else '- 핵심 포인트 없음'}

## Aliases
{', '.join(page.get('aliases', [])) or '-'}

## Evidence
{chr(10).join(ev_lines) if ev_lines else '- 근거 없음'}

## Related Concepts
{chr(10).join(rel_lines) if rel_lines else '- 관련 개념 없음'}

## Reference Summary
- display refs: {', '.join(ref_label(r) for r in page.get('display_reference_ids', [])) or '-'}
- mention_count: {page.get('mention_count', 0)}
"""
            out_path = Path(out_dir) / "wiki" / "concepts" / f"{page['slug']}.md"
            write_text(out_path, md)
            out_paths.append(str(out_path))
        return out_paths


    def assemble_generated(self, normalized: dict[str, Any], concept_page_outputs: list[dict[str, Any]], out_dir: str | Path) -> list[str]:
        """Assemble Markdown from actual ConceptPageGeneration JSON outputs."""
        ledger_by_slug = {c.get("slug"): c for c in normalized.get("concept_ledger", [])}
        out_paths = []

        def cite(refs: list[str]) -> str:
            labels = ", ".join(ref_label(r) for r in refs or [])
            return f" [{labels}]" if labels else ""

        for draft in concept_page_outputs:
            slug = draft.get("slug")
            ledger = ledger_by_slug.get(slug, {})
            title = draft.get("title") or ledger.get("title") or slug
            definition = draft.get("definition", {}) or {}
            key_points = draft.get("key_points", []) or []
            evidence = draft.get("evidence", []) or []
            related = draft.get("related_concept_hints", []) or []

            key_lines = []
            for item in key_points:
                key_lines.append(f"- {item.get('text', '').strip()}" + cite(item.get("anchor_reference_ids", [])))

            ev_lines = []
            for item in evidence:
                ev_lines.append(f"- {item.get('text', '').strip()}" + cite(item.get("anchor_reference_ids", [])))

            related_lines = [f"- {x}" for x in related if x]
            aliases = ", ".join(ledger.get("aliases", []))
            display_refs = ", ".join(ref_label(r) for r in ledger.get("display_reference_ids", []))
            confidence = draft.get("confidence", "")

            md = f"""---
type: concept
slug: {slug}
sources: {', '.join(ledger.get('source_document_ids', []))}
mention_count: {ledger.get('mention_count', 0)}
importance_score: {round(ledger.get('importance_score', 0), 2)}
llm_confidence: {confidence}
---

# {title}

## Definition
{definition.get('text') or ledger.get('definition') or '정의 없음.'}{cite(definition.get('anchor_reference_ids', []))}

## Key Points
{chr(10).join(key_lines) if key_lines else '- 핵심 포인트 없음'}

## Evidence
{chr(10).join(ev_lines) if ev_lines else '- evidence 없음'}

## Related Concepts
{chr(10).join(related_lines) if related_lines else '- 아직 없음'}

## Backend Metadata
- aliases: {aliases or '-'}
- display refs: {display_refs or '-'}
- mention_count: {ledger.get('mention_count', 0)}
"""
            out_path = Path(out_dir) / "wiki" / "concepts" / f"{slug}.md"
            write_text(out_path, md)
            out_paths.append(str(out_path))
        return out_paths


class LinkBuilder:
    def build(self, normalized: dict[str, Any], generated_concept_pages: list[dict[str, Any]] | None = None) -> list[dict[str, Any]]:
        doc = normalized["document"]
        ledger = normalized["concept_ledger"]
        links = []
        source_page_id = f"source:{doc['document_id']}"
        for c in ledger:
            links.append(
                {
                    "source": source_page_id,
                    "target": f"concept:{c['slug']}",
                    "relation": "source_mentions_concept",
                    "decided_by": "backend",
                }
            )
        ledger_slugs = {c["slug"] for c in ledger}
        ledger_by_slug = {c["slug"]: c for c in ledger}
        seen_evidence_pairs = set()
        for ev in normalized.get("evidence_units", []):
            ev_slugs = sorted({slug for slug in ev.get("related_concept_slugs", []) if slug in ledger_slugs})
            for i, source_slug in enumerate(ev_slugs):
                for target_slug in ev_slugs[i + 1 :]:
                    pair = (source_slug, target_slug, ev.get("evidence_id"))
                    if pair in seen_evidence_pairs:
                        continue
                    seen_evidence_pairs.add(pair)
                    links.append(
                        {
                            "source": f"concept:{source_slug}",
                            "target": f"concept:{target_slug}",
                            "relation": "concept_related_to",
                            "decided_by": "backend_shared_evidence",
                            "evidence_id": ev.get("evidence_id"),
                        }
                    )
        seen_key_point_pairs = set()
        for source_slug, target_slug, reason in _shared_key_point_pairs(normalized, ledger_by_slug, _collect_source_key_points(normalized)):
            pair = (source_slug, target_slug)
            if pair in seen_key_point_pairs:
                continue
            seen_key_point_pairs.add(pair)
            links.append(
                {
                    "source": f"concept:{source_slug}",
                    "target": f"concept:{target_slug}",
                    "relation": "concept_related_to",
                    "decided_by": f"backend_{reason}",
                }
            )
        for resolution in normalized.get("concept_resolutions", []):
            source_slug = resolution.get("canonical_slug") or resolution.get("incoming_slug")
            for target_slug in resolution.get("link_targets", []):
                if source_slug and target_slug and source_slug != target_slug:
                    links.append(
                        {
                            "source": f"concept:{source_slug}",
                            "target": f"concept:{target_slug}",
                            "relation": "concept_related_to",
                            "decided_by": "llm_concept_resolution",
                            "confidence": resolution.get("confidence"),
                            "label": resolution.get("reason"),
                        }
                    )
        for hint_resolution in normalized.get("hint_resolutions", []):
            source_slug = hint_resolution.get("canonical_slug")
            for target_slug in hint_resolution.get("link_targets", []):
                if source_slug and target_slug and source_slug != target_slug:
                    links.append(
                        {
                            "source": f"concept:{source_slug}",
                            "target": f"concept:{target_slug}",
                            "relation": "concept_related_to",
                            "decided_by": "llm_hint_resolution",
                            "confidence": hint_resolution.get("confidence"),
                            "label": hint_resolution.get("reason"),
                        }
                    )
        if generated_concept_pages:
            for page in generated_concept_pages:
                for hint in page.get("related_concept_hints", []):
                    target_slug = slugify(str(hint))
                    if target_slug and target_slug != page["slug"]:
                        links.append(
                            {
                                "source": f"concept:{page['slug']}",
                                "target": f"concept:{target_slug}",
                                "relation": "concept_related_to",
                                "decided_by": "llm_hint_backend_normalized",
                            }
                        )
        return links


class ReviewReport:
    def write(self, normalized: dict[str, Any], out_dir: str | Path, generated_concept_pages: list[dict[str, Any]] | None = None) -> str:
        warnings = normalized.get("warnings", [])
        ledger = normalized.get("concept_ledger", [])
        ev = normalized.get("evidence_units", [])
        lines = [
            "# Fruition v0.9 Pipeline Review",
            "",
            "## Summary",
            f"- concepts: {len(ledger)}",
            f"- evidence claims: {len(ev)}",
            f"- generated concept pages: {len(generated_concept_pages or [])}",
            f"- hint resolutions: {len(normalized.get('hint_resolutions', []))}",
            f"- unresolved related hints: {len(normalized.get('unresolved_related_concept_hints', []))}",
            f"- warnings: {len(warnings)}",
            "",
            "## Top Concepts",
        ]
        for c in ledger[:10]:
            lines.append(f"- {c['title']} (`{c['slug']}`): mention_count={c.get('mention_count')}, score={round(c.get('importance_score', 0), 2)}")
        if generated_concept_pages:
            lines += ["", "## LLM Generated Concept Pages"]
            for p in generated_concept_pages:
                lines.append(f"- {p.get('title')} (`{p.get('slug')}`), confidence={p.get('confidence')}")
        if normalized.get("hint_resolutions"):
            lines += ["", "## Hint Resolutions"]
            for item in normalized.get("hint_resolutions", []):
                lines.append(
                    f"- {item.get('hint_slug')} -> {item.get('canonical_slug') or '-'} "
                    f"({item.get('decision')}), confidence={item.get('confidence')}"
                )
        if normalized.get("unresolved_related_concept_hints"):
            lines += ["", "## Unresolved Related Hints"]
            for item in normalized.get("unresolved_related_concept_hints", []):
                lines.append(f"- {item.get('slug')}: evidence={', '.join(item.get('evidence_ids', []))}")
        lines += ["", "## Warnings"]
        lines.extend([f"- {w}" for w in warnings] or ["- none"])
        path = Path(out_dir) / "review_report.md"
        write_text(path, "\n".join(lines) + "\n")
        return str(path)
