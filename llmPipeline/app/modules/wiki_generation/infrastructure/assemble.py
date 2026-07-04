from __future__ import annotations

import re
from datetime import date
from hashlib import sha1
from pathlib import Path
from typing import Any

from app.modules.wiki_ingestion.infrastructure.file_io import write_json, write_text
from app.modules.wiki_generation.domain.entities import SourceBlock
from app.modules.wiki_generation.domain.text_utils import slugify, unique_keep_order
from app.modules.wiki_generation.infrastructure.concept_page_sections import (
    collect_source_key_points as _collect_source_key_points,
    concept_evidence as _concept_evidence,
    concept_key_points_from_source as _concept_key_points_from_source,
    concept_related_lines as _concept_related_lines,
    item_refs as _item_refs,
)
from app.modules.wiki_generation.infrastructure.ref_format import (
    cite_global_refs as _cite_global_refs,
    cite_refs,
    display_ref as _display_ref,
    global_refs as _global_refs,
)


class SourcePageAssembler:
    def build(self, normalized: dict[str, Any], polish: dict[str, Any] | None = None) -> dict[str, Any]:
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
                    key_points.append(f"- {text}{cite_refs(kp.get('anchor_reference_ids', []), doc['document_id'])}")
        else:
            for n in notes:
                for kp in n.get("key_points", []):
                    text = kp.get("text", "").strip()
                    if not text or text in seen:
                        continue
                    seen.add(text)
                    key_points.append(f"- {text}{cite_refs(kp.get('anchor_reference_ids', []), doc['document_id'])}")

        concept_lines = []
        for c in ledger:
            concept_lines.append(f"- [[{c['slug']}|{c['title']}]]{cite_refs(c.get('display_reference_ids', []), doc['document_id'])}")

        category_lines = [
            f"- {item.get('name')}"
            for item in normalized.get("categories", [])
            if item.get("name")
        ]
        section_lines = [
            _source_section_line(item, doc["document_id"])
            for item in normalized.get("section_candidates", [])
            if item.get("title") or item.get("slug")
        ]
        mention_lines = [
            _source_mention_line(item, doc["document_id"])
            for item in normalized.get("mentions", [])
            if item.get("name") or item.get("slug")
        ]
        observation_lines = [
            _source_observation_line(item, doc["document_id"])
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
        markdown_path = f"wiki/sources/{filename_slug}.md"
        artifact = _source_extraction_artifact(normalized, title, summary, markdown_path)
        normalized["source_extraction_artifact"] = artifact
        return {
            "slug": filename_slug,
            "title": title,
            "markdown_path": markdown_path,
            "markdown": md,
            "source_extraction_artifact": artifact,
        }

    def assemble(self, normalized: dict[str, Any], out_dir: str | Path, polish: dict[str, Any] | None = None) -> str:
        page = self.build(normalized, polish=polish)
        out_path = _unique_source_path(Path(out_dir) / "wiki" / "sources", page["slug"])
        write_text(out_path, page["markdown"])
        artifact_path = out_path.with_suffix(".json")
        write_json(artifact_path, page["source_extraction_artifact"])
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


def _source_section_line(item: dict[str, Any], document_id: str) -> str:
    title = item.get("title") or item.get("slug") or "section"
    context = item.get("context") or ""
    suffix = f" - {context}" if context else ""
    return f"- {title}{suffix}{cite_refs(item.get('anchor_reference_ids', []), document_id)}"


def _source_observation_line(item: dict[str, Any], document_id: str) -> str:
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
    return f"- {' / '.join(parts)}{cite_refs(item.get('anchor_reference_ids', []), document_id)}"


def _source_mention_line(item: dict[str, Any], document_id: str) -> str:
    name = item.get("name") or item.get("slug") or "mention"
    context = item.get("context") or ""
    suffix = f" - {context}" if context else ""
    return f"- {name}{suffix}{cite_refs(item.get('anchor_reference_ids', []), document_id)}"


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
    def build_top(
        self,
        normalized: dict[str, Any],
        top_n: int | None = 6,
        polish_by_slug: dict[str, Any] | None = None,
        source_key_points: list[dict[str, Any]] | None = None,
    ) -> list[dict[str, Any]]:
        concepts = normalized["concept_ledger"] if top_n is None else normalized["concept_ledger"][:top_n]
        return [
            self._build_page(c, normalized, polish_by_slug or {}, source_key_points)
            for c in concepts
        ]

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
        for page in self.build_top(normalized, top_n=top_n, polish_by_slug=polish_by_slug, source_key_points=source_key_points):
            out_path = Path(out_dir) / page["markdown_path"]
            write_text(out_path, page["markdown"])
            out_paths.append(str(out_path))
        return out_paths

    def _build_page(
        self,
        c: dict[str, Any],
        normalized: dict[str, Any],
        polish_by_slug: dict[str, Any] | None = None,
        source_key_points: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        evidence = normalized["evidence_units"]
        document_id = normalized["document"]["document_id"]
        polish_by_slug = polish_by_slug or {}
        ledger_by_slug = {concept["slug"]: concept for concept in normalized["concept_ledger"]}
        source_key_points = source_key_points or _collect_source_key_points(normalized)
        polish = polish_by_slug.get(c["slug"], {})
        related_evidence = _concept_evidence(c, evidence)
        ev_lines = []
        for ev in related_evidence:
            ev_lines.append(f"- {ev['claim']}{cite_refs(ev.get('anchor_reference_ids', []), ev.get('source_document_id') or document_id)}")
        definition_text = c.get("definition") or "정의 초안 없음."
        definition_refs = c.get("display_reference_ids", [])
        if polish.get("definition", {}).get("text"):
            definition_text = polish["definition"]["text"]
            definition_refs = polish["definition"].get("anchor_reference_ids", definition_refs)
        if not ev_lines and c.get("definition"):
            ev_lines.append(f"- {c['definition']}{cite_refs(definition_refs, document_id)}")
        key_point_lines = []
        for item in polish.get("key_points", {}).get("items", []):
            text = item.get("text", "").strip()
            if text:
                key_point_lines.append(f"- {text}{cite_refs(item.get('anchor_reference_ids', []), document_id)}")
        if not key_point_lines:
            key_point_lines = _concept_key_points_from_source(c, related_evidence, source_key_points, document_id)
        if not key_point_lines and c.get("definition"):
            key_point_lines.append(f"- {c['definition']}{cite_refs(definition_refs, document_id)}")
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
{definition_text}{cite_refs(definition_refs, document_id)}

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
- display refs: {', '.join(_display_ref(r) for r in _global_refs(document_id, c.get('display_reference_ids', []))) or '-'}
- mention_count: {c.get('mention_count', 0)}
"""
        return {
            "slug": c["slug"],
            "title": c.get("title") or c["slug"],
            "markdown_path": f"wiki/concepts/{c['slug']}.md",
            "markdown": md,
        }


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

    def build_pages(self, pages: list[dict[str, Any]]) -> list[dict[str, Any]]:
        built_pages = []
        for page in pages:
            document_id = next(iter(page.get("source_document_ids", []) or []), None)
            definition = page.get("definition", {})
            kp_lines = [
                f"- {kp.get('text', '')}{cite_refs(kp.get('anchor_reference_ids', []), document_id)}"
                for kp in page.get("key_points", [])
                if kp.get("text")
            ]
            ev_lines = [
                f"- {ev.get('text', '')}{cite_refs(ev.get('anchor_reference_ids', []), document_id)}"
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
{definition.get('text') or '정의 없음.'}{cite_refs(definition.get('anchor_reference_ids', []), document_id)}

## Key Points
{chr(10).join(kp_lines) if kp_lines else '- 핵심 포인트 없음'}

## Aliases
{', '.join(page.get('aliases', [])) or '-'}

## Evidence
{chr(10).join(ev_lines) if ev_lines else '- 근거 없음'}

## Related Concepts
{chr(10).join(rel_lines) if rel_lines else '- 관련 개념 없음'}

## Reference Summary
- display refs: {', '.join(_display_ref(r) for r in _global_refs(document_id, page.get('display_reference_ids', []))) or '-'}
- mention_count: {page.get('mention_count', 0)}
"""
            built_pages.append(
                {
                    "slug": page["slug"],
                    "title": page.get("title") or page["slug"],
                    "markdown_path": f"wiki/concepts/{page['slug']}.md",
                    "markdown": md,
                }
            )
        return built_pages

    def assemble_pages(self, pages: list[dict[str, Any]], out_dir: str | Path) -> list[str]:
        out_paths = []
        for page in self.build_pages(pages):
            out_path = Path(out_dir) / page["markdown_path"]
            write_text(out_path, page["markdown"])
            out_paths.append(str(out_path))
        return out_paths


    def assemble_generated(self, normalized: dict[str, Any], concept_page_outputs: list[dict[str, Any]], out_dir: str | Path) -> list[str]:
        """Assemble Markdown from actual ConceptPageGeneration JSON outputs."""
        ledger_by_slug = {c.get("slug"): c for c in normalized.get("concept_ledger", [])}
        out_paths = []

        def cite(refs: list[str]) -> str:
            labels = ", ".join(_display_ref(r) for r in _global_refs(document_id, refs or []))
            return f" [{labels}]" if labels else ""

        for draft in concept_page_outputs:
            slug = draft.get("slug")
            ledger = ledger_by_slug.get(slug, {})
            document_id = next(iter(ledger.get("source_document_ids", []) or []), None)
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
            display_refs = ", ".join(_display_ref(r) for r in _global_refs(document_id, ledger.get("display_reference_ids", [])))
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


class MeaningClusterArtifactAssembler:
    """Build active cluster and ingest log markdown artifacts from normalized output."""

    def build(
        self,
        normalized: dict[str, Any],
        user_id: str,
        workspace_id: str,
        cluster_decisions: list[dict[str, Any]] | None = None,
        concept_update_decisions: list[dict[str, Any]] | None = None,
        core_relation_decisions: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        concept_update_decisions = concept_update_decisions or []
        core_relation_decisions = core_relation_decisions or []
        clusters = self._clusters(normalized, cluster_decisions or [], concept_update_decisions, core_relation_decisions)
        invalid_candidates = self.invalid_candidate_claims(normalized)
        active_markdown = self._active_markdown(clusters)
        maintenance_summary = self._maintenance_summary(clusters, invalid_candidates)
        log_markdown = self._log_markdown(normalized, clusters, user_id, workspace_id, concept_update_decisions, invalid_candidates)
        return {
            "active_path": f"wiki/{user_id}/{workspace_id}/clusters/active.md",
            "log_path": f"wiki/{user_id}/{workspace_id}/logs/{date.today().isoformat()}.md",
            "active_markdown": active_markdown,
            "log_markdown": log_markdown,
            "clusters": clusters,
            "concept_update_decisions": concept_update_decisions,
            "core_relation_decisions": core_relation_decisions,
            "invalid_candidates": invalid_candidates,
            "maintenance_summary": maintenance_summary,
        }

    def assemble(
        self,
        normalized: dict[str, Any],
        out_dir: str | Path,
        user_id: str,
        workspace_id: str,
        cluster_decisions: list[dict[str, Any]] | None = None,
        concept_update_decisions: list[dict[str, Any]] | None = None,
        core_relation_decisions: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        artifact = self.build(
            normalized,
            user_id=user_id,
            workspace_id=workspace_id,
            cluster_decisions=cluster_decisions,
            concept_update_decisions=concept_update_decisions,
            core_relation_decisions=core_relation_decisions,
        )
        write_text(Path(out_dir) / artifact["active_path"], artifact["active_markdown"])
        write_text(Path(out_dir) / artifact["log_path"], artifact["log_markdown"])
        return artifact

    def candidate_claims(self, normalized: dict[str, Any]) -> list[dict[str, Any]]:
        return [candidate for candidate in self._candidate_claims(normalized) if candidate.get("refs")]

    def invalid_candidate_claims(self, normalized: dict[str, Any]) -> list[dict[str, Any]]:
        invalid = []
        for candidate in self._candidate_claims(normalized):
            if candidate.get("refs"):
                continue
            invalid.append(
                {
                    "candidate_id": candidate["candidate_id"],
                    "claim_id": candidate["claim_id"],
                    "term": candidate["term"],
                    "slug": candidate["slug"],
                    "claim": candidate["claim"],
                    "candidate_type": candidate["candidate_type"],
                    "reason": "missing source refs",
                }
            )
        return invalid

    def _candidate_claims(self, normalized: dict[str, Any]) -> list[dict[str, Any]]:
        candidates: list[dict[str, Any]] = []
        evidence_by_id = {item.get("evidence_id"): item for item in normalized.get("evidence_units", [])}
        for item in normalized.get("section_candidates", []):
            candidate = self._source_candidate(normalized, item, "section", len(candidates) + 1)
            if candidate:
                candidates.append(candidate)
        for item in normalized.get("mentions", []):
            candidate = self._source_candidate(normalized, item, "mention", len(candidates) + 1)
            if candidate:
                candidates.append(candidate)
        for hint in normalized.get("unresolved_related_concept_hints", []):
            for evidence_id in hint.get("evidence_ids", []):
                evidence = evidence_by_id.get(evidence_id)
                candidate = self._evidence_candidate(hint, evidence, len(candidates) + 1)
                if candidate:
                    candidates.append(candidate)
        return candidates

    def _maintenance_summary(self, clusters: list[dict[str, Any]], invalid_candidates: list[dict[str, Any]]) -> dict[str, Any]:
        promotion_candidates = []
        invalid_promotions = []
        relation_candidates = []
        for cluster in clusters:
            promotion = cluster.get("promotion")
            if promotion and promotion.get("status") == "candidate" and promotion.get("source_refs"):
                promotion_candidates.append(
                    {
                        "cluster_id": cluster["id"],
                        "representative": cluster.get("representative"),
                        "source_refs": promotion.get("source_refs", []),
                        "reason": promotion.get("reason") or "",
                    }
                )
            elif promotion and promotion.get("status") == "candidate":
                invalid_promotions.append(
                    {
                        "cluster_id": cluster["id"],
                        "representative": cluster.get("representative"),
                        "reason": "promotion candidate has no source_refs",
                    }
                )
            for relation in cluster.get("core_relation_candidates", []):
                relation_candidates.append(
                    {
                        "cluster_id": cluster["id"],
                        "target": relation.get("target"),
                        "relation": relation.get("relation"),
                        "evidence": relation.get("evidence", []),
                    }
                )
        return {
            "promotion_candidate_count": len(promotion_candidates),
            "promotion_candidates": promotion_candidates,
            "invalid_candidate_count": len(invalid_candidates),
            "invalid_candidates": invalid_candidates,
            "invalid_promotion_count": len(invalid_promotions),
            "invalid_promotions": invalid_promotions,
            "relation_candidate_count": len(relation_candidates),
            "relation_candidates": relation_candidates,
            "lint_action_available": bool(promotion_candidates or relation_candidates or invalid_candidates or invalid_promotions),
        }

    def _clusters(
        self,
        normalized: dict[str, Any],
        cluster_decisions: list[dict[str, Any]],
        concept_update_decisions: list[dict[str, Any]],
        core_relation_decisions: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        clusters: dict[str, dict[str, Any]] = {}
        decisions_by_candidate = {
            str(item.get("candidate_id")): item
            for item in cluster_decisions
            if item.get("candidate_id")
        }
        concept_update_candidate_ids = {
            str(item.get("candidate_id"))
            for item in concept_update_decisions
            if item.get("decision") == "same_concept" and item.get("candidate_id")
        }
        relation_by_candidate = {
            str(item.get("candidate_id")): item
            for item in core_relation_decisions
            if item.get("candidate_id")
        }

        for candidate in self.candidate_claims(normalized):
            if candidate["candidate_id"] in concept_update_candidate_ids:
                continue
            decision = decisions_by_candidate.get(candidate["candidate_id"], {})
            self._add_candidate_claim(clusters, candidate, decision, relation_by_candidate.get(candidate["candidate_id"]))

        return sorted(clusters.values(), key=lambda item: item["id"])

    def _source_candidate(
        self,
        normalized: dict[str, Any],
        item: dict[str, Any],
        candidate_type: str,
        index: int,
    ) -> dict[str, Any] | None:
        term = str(item.get("term") or item.get("title") or item.get("name") or item.get("slug") or "").strip()
        slug = slugify(str(item.get("slug") or term))
        if not term or slug == "untitled":
            return None
        context = str(item.get("context") or "").strip()
        if not context:
            return None
        source_document_id = normalized.get("document", {}).get("document_id")
        refs = _global_refs(source_document_id, item.get("anchor_reference_ids", []))
        return {
            "candidate_id": f"cand_{index:03d}",
            "claim_id": f"claim_{_id_fragment(str(source_document_id or 'doc'))}_{index:03d}",
            "term": term,
            "slug": slug,
            "claim": f"{term} - {context}",
            "refs": refs,
            "candidate_type": candidate_type,
            "suggested_promotion_status": "none",
            "suggested_promotion_reason": "",
        }

    def _evidence_candidate(self, hint: dict[str, Any], evidence: dict[str, Any] | None, index: int) -> dict[str, Any] | None:
        if not evidence or not evidence.get("claim"):
            return None
        slug = slugify(str(hint.get("canonical_slug") or hint.get("hint_slug") or "unresolved"))
        if not slug or slug == "untitled":
            return None
        return {
            "candidate_id": f"cand_{index:03d}",
            "claim_id": str(evidence.get("evidence_id") or f"ev_{index:04d}"),
            "term": _title_from_slug(slug),
            "slug": slug,
            "claim": evidence.get("claim") or "",
            "refs": _global_refs(evidence.get("source_document_id"), evidence.get("anchor_reference_ids", [])),
            "candidate_type": "evidence",
            "suggested_promotion_status": "none",
            "suggested_promotion_reason": "",
        }

    def _add_candidate_claim(
        self,
        clusters: dict[str, dict[str, Any]],
        candidate: dict[str, Any],
        decision: dict[str, Any],
        relation_decision: dict[str, Any] | None = None,
    ) -> None:
        decision_type = str(decision.get("decision") or "new_cluster")
        target_cluster_id = slugify(str(decision.get("target_cluster_id") or candidate["slug"]))
        if decision_type not in {"same_cluster", "new_cluster", "needs_review"}:
            decision_type = "new_cluster"
        if not target_cluster_id or target_cluster_id == "untitled" or re.fullmatch(r"cluster-\d+", target_cluster_id):
            target_cluster_id = candidate["slug"]
        representative = str(decision.get("representative") or candidate["term"]).strip()
        cluster = self._cluster(clusters, target_cluster_id, representative)
        cluster["evidence_claims"].append(
            {
                "id": candidate["claim_id"],
                "claim": candidate["claim"],
                "refs": candidate["refs"],
                "candidate_type": candidate["candidate_type"],
                "cluster_decision": decision_type,
                "decision_reason": decision.get("reason") or "",
            }
        )
        if relation_decision:
            relation = str(relation_decision.get("relation") or "")
            concept_slug = str(relation_decision.get("concept_slug") or "")
            if relation and concept_slug:
                cluster["core_relation_candidates"].append(
                    {
                        "target": f"concept:{concept_slug}",
                        "relation": relation,
                        "evidence": [candidate["claim_id"]],
                        "reason": relation_decision.get("reason") or "",
                    }
                )
        promotion_status = str(decision.get("promotion_status") or candidate.get("suggested_promotion_status") or "none")
        if promotion_status in {"candidate", "needs_review"}:
            cluster["promotion"] = {
                "status": promotion_status,
                "source_refs": self._source_refs(cluster),
                "reason": decision.get("reason") or candidate.get("suggested_promotion_reason") or "LLM cluster judge 판단",
            }

    def _cluster(self, clusters: dict[str, dict[str, Any]], slug: str, representative: str) -> dict[str, Any]:
        cluster = clusters.get(slug)
        if cluster is None:
            cluster = {
                "id": slug,
                "type": "term_cluster",
                "representative": representative,
                "evidence_claims": [],
                "core_relation_candidates": [],
                "promotion": None,
            }
            clusters[slug] = cluster
        return cluster

    def _source_refs(self, cluster: dict[str, Any]) -> list[str]:
        refs = []
        for claim in cluster.get("evidence_claims", []):
            for ref in claim.get("refs", []):
                document_id, _sep, _block_id = str(ref).partition(":")
                if document_id:
                    refs.append(document_id)
        return unique_keep_order(refs)

    def _active_markdown(self, clusters: list[dict[str, Any]]) -> str:
        if not clusters:
            return "# Active Meaning Clusters\n\n- active cluster 없음\n"
        sections = ["# Active Meaning Clusters"]
        for cluster in clusters:
            sections.append(self._cluster_section(cluster))
        return "\n\n".join(sections) + "\n"

    def _cluster_section(self, cluster: dict[str, Any]) -> str:
        lines = [
            f"## cluster: {cluster['id']}",
            "",
            f"type: {cluster['type']}",
            f"representative: {cluster['representative']}",
            "",
            "### Evidence Claims",
        ]
        lines.extend(
            f"- {claim['id']}: {claim['claim']}{_cite_global_refs(claim.get('refs', []))}"
            for claim in cluster.get("evidence_claims", [])
        )
        if not cluster.get("evidence_claims"):
            lines.append("- evidence claim 없음")

        if cluster.get("core_relation_candidates"):
            lines.extend(["", "### Core Relation Candidates"])
            for relation in cluster["core_relation_candidates"]:
                lines.extend(
                    [
                        f"- target: {relation.get('target')}",
                        f"  relation: {relation.get('relation')}",
                        f"  evidence: [{', '.join(relation.get('evidence', []))}]",
                        f"  reason: {relation.get('reason') or '-'}",
                    ]
                )

        promotion = cluster.get("promotion")
        if promotion:
            lines.extend(
                [
                    "",
                    "### Promotion",
                    f"status: {promotion.get('status')}",
                    f"source_refs: [{', '.join(promotion.get('source_refs', []))}]",
                    f"reason: {promotion.get('reason') or '-'}",
                ]
            )
        return "\n".join(lines)

    def _log_markdown(
        self,
        normalized: dict[str, Any],
        clusters: list[dict[str, Any]],
        user_id: str,
        workspace_id: str,
        concept_update_decisions: list[dict[str, Any]],
        invalid_candidates: list[dict[str, Any]],
    ) -> str:
        doc = normalized.get("document", {})
        document_id = doc.get("document_id") or "unknown"
        lines = [
            f"## {date.today().isoformat()} ingest: {document_id}",
            "",
            f"user: {user_id}",
            f"workspace: {workspace_id}",
            f"input: {doc.get('source_path') or document_id}",
            f"created_source_page: source:{document_id}",
            "",
            "### Extracted Claims",
        ]
        claim_rows = [
            claim
            for cluster in clusters
            for claim in cluster.get("evidence_claims", [])
        ]
        if claim_rows:
            lines.extend(f"- {claim['id']}: {claim['claim']}{_cite_global_refs(claim.get('refs', []))}" for claim in claim_rows)
        else:
            lines.append("- extracted claim 없음")

        lines.extend(["", "### Invalid Candidates"])
        if invalid_candidates:
            for item in invalid_candidates:
                lines.append(f"- {item.get('claim_id')} ({item.get('candidate_type')}): {item.get('claim')}")
                lines.append(f"  reason: {item.get('reason')}")
        else:
            lines.append("- invalid candidate 없음")

        lines.extend(["", "### Concept Update Decisions"])
        if concept_update_decisions:
            for item in concept_update_decisions:
                lines.append(f"- {item.get('claim_id') or item.get('candidate_id')} -> concept:{item.get('concept_slug')}")
                lines.append("  decision: same_concept")
                lines.append(f"  reason: {item.get('reason') or '-'}")
        else:
            lines.append("- concept update decision 없음")

        lines.extend(["", "### Cluster Decisions"])
        if claim_rows:
            for cluster in clusters:
                for claim in cluster.get("evidence_claims", []):
                    lines.append(f"- {claim['id']} -> cluster:{cluster['id']}")
                    lines.append(f"  decision: {claim.get('cluster_decision') or 'new_cluster'}")
                    lines.append(f"  reason: {claim.get('decision_reason') or '-'}")
        else:
            lines.append("- cluster decision 없음")

        lines.extend(["", "### Relation Candidates"])
        relation_rows = [
            (cluster, relation)
            for cluster in clusters
            for relation in cluster.get("core_relation_candidates", [])
        ]
        if relation_rows:
            for cluster, relation in relation_rows:
                lines.append(f"- cluster:{cluster['id']} -> {relation.get('target')}")
                lines.append(f"  relation: {relation.get('relation')}")
                lines.append(f"  evidence: [{', '.join(relation.get('evidence', []))}]")
                lines.append(f"  reason: {relation.get('reason') or '-'}")
        else:
            lines.append("- relation candidate 없음")

        lines.extend(["", "### Promotion Decisions"])
        promotions = [cluster for cluster in clusters if cluster.get("promotion")]
        if promotions:
            for cluster in promotions:
                promotion = cluster["promotion"]
                lines.append(f"- cluster:{cluster['id']}")
                lines.append(f"  status: {promotion.get('status')}")
                lines.append(f"  source_refs: [{', '.join(promotion.get('source_refs', []))}]")
                lines.append(f"  reason: {promotion.get('reason') or '-'}")
        else:
            lines.append("- promotion decision 없음")

        lines.extend(["", "### Materialized Changes", "- updated: clusters/active.md", "- updated: logs/{yyyy-mm-dd}.md"])
        return "\n".join(lines) + "\n"


def _title_from_slug(slug: str) -> str:
    return " ".join(part for part in slug.split("-") if part) or slug


def _id_fragment(value: str) -> str:
    return sha1(value.encode("utf-8")).hexdigest()[:8]


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
