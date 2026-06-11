from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from .io_utils import write_text
from .models import SourceBlock
from .text_utils import slugify, unique_keep_order


def ref_label(ref_id: str) -> str:
    m = re.search(r"_b(\d{4})$", ref_id)
    if m:
        return f"B{m.group(1)}"
    return ref_id


def cite_refs(refs: list[str]) -> str:
    refs = unique_keep_order([r for r in refs if r])
    return f" [{', '.join(ref_label(r) for r in refs)}]" if refs else ""


class SourcePageAssembler:
    def assemble(self, normalized: dict[str, Any], out_dir: str | Path) -> str:
        doc = normalized["document"]
        notes = normalized["semantic_notes"]
        ledger = normalized["concept_ledger"]
        title = doc["title"]

        summary_parts = [n.get("semantic_summary", "") for n in notes if n.get("semantic_summary")]
        summary = "\n\n".join(summary_parts[:4]) or "요약 없음."

        key_points = []
        seen = set()
        for n in notes:
            for kp in n.get("key_points", []):
                text = kp.get("text", "").strip()
                if not text or text in seen:
                    continue
                seen.add(text)
                key_points.append(f"- {text}{cite_refs(kp.get('anchor_reference_ids', []))}")

        concept_lines = []
        for c in ledger[:12]:
            concept_lines.append(f"- [[{c['slug']}|{c['title']}]]{cite_refs(c.get('display_reference_ids', []))}")

        md = f"""---
type: source
document_id: {doc['document_id']}
source_file: {doc['source_path']}
---

# {title}

## Summary
{summary}

## Key Points
{chr(10).join(key_points) if key_points else '- 핵심 포인트 없음'}

## Extracted Concepts
{chr(10).join(concept_lines) if concept_lines else '- 추출된 concept 없음'}
"""
        out_path = Path(out_dir) / "wiki" / "sources" / f"{doc['document_id']}.md"
        write_text(out_path, md)
        return str(out_path)


class ConceptPageAssembler:
    def assemble_top(self, normalized: dict[str, Any], out_dir: str | Path, top_n: int = 6) -> list[str]:
        """Deterministic skeleton concept pages.

        This is useful when concept page LLM generation is disabled.
        """
        out_paths = []
        evidence = normalized["evidence_units"]
        for c in normalized["concept_ledger"][:top_n]:
            related_evidence = [ev for ev in evidence if c["slug"] in ev.get("related_concept_slugs", [])]
            ev_lines = []
            for ev in related_evidence[:8]:
                ev_lines.append(f"- {ev['claim']}{cite_refs(ev.get('anchor_reference_ids', []))}")
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
{c.get('definition') or '정의 초안 없음.'}{cite_refs(c.get('display_reference_ids', []))}

## Why It Matters
{c.get('why_page_worthy') or '중요도 설명 없음.'}

## Aliases
{aliases or '-'}

## Evidence
{chr(10).join(ev_lines) if ev_lines else '- 아직 연결된 evidence claim 없음'}

## Reference Summary
- display refs: {', '.join(ref_label(r) for r in c.get('display_reference_ids', [])) or '-'}
- mention_count: {c.get('mention_count', 0)}
"""
            out_path = Path(out_dir) / "wiki" / "concepts" / f"{c['slug']}.md"
            write_text(out_path, md)
            out_paths.append(str(out_path))
        return out_paths


class GeneratedConceptPageAssembler:
    """Assemble concept pages from ConceptPageGeneration API outputs."""

    def _map_block_ids(self, anchor_block_ids: list[str], source_blocks: list[SourceBlock], warnings: list[str], context: str) -> list[str]:
        by_bid = {b.block_id: b.source_reference_id for b in source_blocks}
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
            for hint in page.get("related_concept_hints", [])[:12]:
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

## Evidence
{chr(10).join(ev_lines) if ev_lines else '- 근거 없음'}

## Related Concepts
{chr(10).join(rel_lines) if rel_lines else '- 관련 개념 없음'}
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
        slugs = {c["slug"] for c in ledger}
        if "source-page" in slugs and "concept-page" in slugs:
            links.append(
                {
                    "source": "concept:source-page",
                    "target": "concept:concept-page",
                    "relation": "concept_related_to",
                    "decided_by": "backend_rule",
                }
            )
        if "wiki-graph" in slugs:
            for s in ["source-page", "concept-page"]:
                if s in slugs:
                    links.append(
                        {
                            "source": "concept:wiki-graph",
                            "target": f"concept:{s}",
                            "relation": "concept_related_to",
                            "decided_by": "backend_rule",
                        }
                    )
        if generated_concept_pages:
            for page in generated_concept_pages:
                for hint in page.get("related_concept_hints", [])[:12]:
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
        lines += ["", "## Warnings"]
        lines.extend([f"- {w}" for w in warnings] or ["- none"])
        path = Path(out_dir) / "review_report.md"
        write_text(path, "\n".join(lines) + "\n")
        return str(path)
