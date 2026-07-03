import re
from dataclasses import dataclass

from app.modules.query.application.ports import EmbeddingSearchPort, TextSearchPort
from app.modules.query.domain.entities import EvidenceSnippet, RetrievedPage, SourceReference, WikiEmbeddingUnit
from app.modules.query.domain.scoring import hybrid_score


@dataclass(frozen=True)
class _EvidenceCandidate:
    page_id: str
    source_document_id: str
    source_block_ids: list[str]
    source_refs: list[SourceReference]
    text: str
    score: float
    unit_type: str = "evidence"


class EvidenceSelector:
    def __init__(
        self,
        embedding_search: EmbeddingSearchPort | None = None,
        text_search: TextSearchPort | None = None,
        max_related_pages: int = 8,
        max_paragraphs_per_page: int = 4,
        evidence_embedding_weight: float = 0.75,
        min_evidence_score: float = 0.0,
        evidence_relative_score_floor: float = 0.85,
    ) -> None:
        self._embedding_search = embedding_search
        self._text_search = text_search
        self._max_related_pages = max_related_pages
        self._max_paragraphs_per_page = max_paragraphs_per_page
        self._evidence_embedding_weight = evidence_embedding_weight
        self._min_evidence_score = min_evidence_score
        self._evidence_relative_score_floor = evidence_relative_score_floor

    def select(
        self,
        question: str,
        related_pages: list[RetrievedPage],
        embedding_units_by_page_id: dict[str, list[WikiEmbeddingUnit]],
    ) -> list[EvidenceSnippet]:
        candidates: list[_EvidenceCandidate] = []
        for item in related_pages[: self._max_related_pages]:
            source_document_id = self._source_document_id(item)
            if item.page.page_type == "web":
                candidates.extend(self._score_web_evidence(question, item))
                continue
            stored_units = embedding_units_by_page_id.get(item.page.id, [])
            if stored_units:
                candidates.extend(self._score_stored_embedding_units(question, item, stored_units))
                continue
            if not source_document_id and not self._has_global_source_refs(item.page.markdown or item.page.summary):
                continue
            candidates.extend(self._score_evidence_sentences(question, item, source_document_id or ""))

        all_candidates = self._dedupe_evidence(candidates)
        candidates = self._select_evidence_score_band_by_page(all_candidates)
        candidates = self._include_atomic_units_for_selected_refs(all_candidates, candidates)
        candidates.sort(key=lambda snippet: snippet.score, reverse=True)
        return [
            EvidenceSnippet(
                rank=index,
                source_document_id=snippet.source_document_id,
                source_block_ids=snippet.source_block_ids,
                source_refs=snippet.source_refs,
                text=snippet.text,
            )
            for index, snippet in enumerate(candidates, start=1)
        ]

    def _select_evidence_score_band_by_page(self, candidates: list[_EvidenceCandidate]) -> list[_EvidenceCandidate]:
        if not candidates:
            return []
        by_page: dict[str, list[_EvidenceCandidate]] = {}
        for candidate in candidates:
            by_page.setdefault(candidate.page_id, []).append(candidate)
        selected: list[_EvidenceCandidate] = []
        for page_candidates in by_page.values():
            page_candidates.sort(key=lambda candidate: candidate.score, reverse=True)
            top_score = page_candidates[0].score
            if top_score <= 0:
                selected.extend(candidate for candidate in page_candidates if candidate.score >= self._min_evidence_score)
                continue
            floor = max(self._min_evidence_score, top_score * self._evidence_relative_score_floor)
            selected.extend(candidate for candidate in page_candidates if candidate.score >= floor)
        return self._dedupe_evidence(selected)

    def _include_atomic_units_for_selected_refs(
        self,
        all_candidates: list[_EvidenceCandidate],
        selected: list[_EvidenceCandidate],
    ) -> list[_EvidenceCandidate]:
        selected_keys = {self._candidate_key(candidate) for candidate in selected}
        selected_refs_by_page: dict[str, set[str]] = {}
        for candidate in selected:
            if len(candidate.source_block_ids) <= 1:
                continue
            selected_refs_by_page.setdefault(candidate.page_id, set()).update(candidate.source_block_ids)

        expanded = list(selected)
        for candidate in all_candidates:
            if len(candidate.source_block_ids) != 1:
                continue
            if candidate.source_block_ids[0] not in selected_refs_by_page.get(candidate.page_id, set()):
                continue
            key = self._candidate_key(candidate)
            if key in selected_keys:
                continue
            selected_keys.add(key)
            expanded.append(candidate)
        return expanded

    def _score_evidence_sentences(
        self,
        question: str,
        item: RetrievedPage,
        source_document_id: str,
    ) -> list[_EvidenceCandidate]:
        content = item.page.markdown or item.page.summary
        paragraphs = self._split_paragraphs(content)
        if not paragraphs:
            return []

        raw_candidates: list[_EvidenceCandidate] = []
        for paragraph in paragraphs:
            for unit, unit_type, unit_weight in self._split_structured_evidence_units(paragraph):
                source_refs = self._source_references(unit, source_document_id)
                if not source_refs:
                    continue
                legacy_document_id, legacy_block_ids = self._legacy_source_fields(source_refs, source_document_id)
                clean_sentence = self._remove_block_refs(unit)
                raw_candidates.append(
                    _EvidenceCandidate(
                        page_id=item.page.id,
                        source_document_id=legacy_document_id,
                        source_block_ids=legacy_block_ids,
                        source_refs=source_refs,
                        text=clean_sentence,
                        score=item.score * unit_weight,
                        unit_type=unit_type,
                    )
                )

        scored = self._score_structured_candidates(question, raw_candidates, item)
        scored.sort(key=lambda value: -value.score)
        return self._select_evidence_candidates(scored)

    def _score_stored_embedding_units(
        self,
        question: str,
        item: RetrievedPage,
        units: list[WikiEmbeddingUnit],
    ) -> list[_EvidenceCandidate]:
        raw_candidates: list[_EvidenceCandidate] = []
        for unit in units:
            if not unit.source_block_ids or not unit.text:
                continue
            source_refs = self._source_references_from_ids(unit.source_block_ids, unit.source_document_id)
            legacy_document_id, legacy_block_ids = self._legacy_source_fields(source_refs, unit.source_document_id)
            raw_candidates.append(
                _EvidenceCandidate(
                    page_id=unit.page_id,
                    source_document_id=legacy_document_id,
                    source_block_ids=legacy_block_ids,
                    source_refs=source_refs,
                    text=unit.text,
                    score=item.score * unit.weight,
                    unit_type=unit.unit_type,
                )
            )
        return self._score_structured_candidates(question, raw_candidates, item)

    def _select_evidence_candidates(self, candidates: list[_EvidenceCandidate]) -> list[_EvidenceCandidate]:
        ranked = self._dedupe_evidence(candidates)
        selected: list[_EvidenceCandidate] = []
        selected_keys: set[tuple[str, tuple[tuple[str, str], ...]]] = set()

        atomic_by_ref: dict[str, _EvidenceCandidate] = {}
        for candidate in ranked:
            if len(candidate.source_block_ids) != 1:
                continue
            ref = candidate.source_block_ids[0]
            current = atomic_by_ref.get(ref)
            if current is None or candidate.score > current.score:
                atomic_by_ref[ref] = candidate

        priority_atomic: list[_EvidenceCandidate] = []
        priority_keys: set[tuple[str, tuple[str, ...]]] = set()
        for candidate in ranked:
            if len(candidate.source_block_ids) <= 1:
                continue
            for ref in candidate.source_block_ids:
                atomic = atomic_by_ref.get(ref)
                if atomic is None:
                    continue
                key = self._candidate_key(atomic)
                if key in priority_keys:
                    continue
                priority_keys.add(key)
                priority_atomic.append(atomic)

        for candidate in [*priority_atomic, *sorted(atomic_by_ref.values(), key=lambda value: -value.score)]:
            if len(selected) >= self._max_paragraphs_per_page:
                break
            self._append_selected_evidence(selected, selected_keys, candidate)

        for candidate in ranked:
            if len(selected) >= self._max_paragraphs_per_page:
                break
            self._append_selected_evidence(selected, selected_keys, candidate)

        selected.sort(key=lambda value: -value.score)
        return selected

    def _append_selected_evidence(
        self,
        selected: list[_EvidenceCandidate],
        selected_keys: set[tuple[str, tuple[tuple[str, str], ...]]],
        candidate: _EvidenceCandidate,
    ) -> None:
        key = self._candidate_key(candidate)
        if key in selected_keys:
            return
        selected_keys.add(key)
        selected.append(candidate)

    def _score_web_evidence(self, question: str, item: RetrievedPage) -> list[_EvidenceCandidate]:
        text = item.page.markdown or item.page.summary
        if not text:
            return []
        query_terms = set(self._tokens(question))
        units = self._split_sentences(text)
        if not units:
            units = [self._clean_sentence(text)]
        scored: list[_EvidenceCandidate] = []
        for unit in units:
            clean_unit = self._remove_block_refs(unit)
            if not clean_unit:
                continue
            unit_terms = set(self._tokens(clean_unit))
            overlap = len(query_terms & unit_terms)
            score = overlap * 2.0 + item.score
            if overlap == 0:
                score -= 0.50
            scored.append(
                _EvidenceCandidate(
                    page_id=item.page.id,
                    source_document_id=item.page.id,
                    source_block_ids=["web"],
                    source_refs=[],
                    text=clean_unit,
                    score=score,
                )
            )
        scored.sort(key=lambda value: -value.score)
        return self._dedupe_evidence(scored)[: self._max_paragraphs_per_page]

    def _split_evidence_units(self, paragraph: str) -> list[str]:
        bullet_lines = [
            self._clean_sentence(line)
            for line in paragraph.splitlines()
            if re.match(r"^\s*[-*]\s+", line) and self._source_block_ids(line)
        ]
        if bullet_lines:
            return bullet_lines
        return self._split_sentences(paragraph)

    def _split_structured_evidence_units(self, paragraph: str) -> list[tuple[str, str, float]]:
        section = self._section_heading(paragraph) or "paragraph"
        weight = self._section_weight(section)
        units = self._split_evidence_units(paragraph)
        return [(unit, section, weight) for unit in units]

    def _section_weight(self, section: str) -> float:
        normalized = section.strip().lower()
        weights = {
            "key points": 1.35,
            "observations": 1.30,
            "observation": 1.30,
            "core concepts": 1.20,
            "section candidates": 1.15,
            "mentions": 1.05,
            "categories": 0.95,
        }
        return weights.get(normalized, 1.0)

    def _score_structured_candidates(
        self,
        question: str,
        candidates: list[_EvidenceCandidate],
        item: RetrievedPage,
    ) -> list[_EvidenceCandidate]:
        if not candidates:
            return []

        texts = [candidate.text for candidate in candidates]
        if self._embedding_search and self._text_search:
            embedding_scores = self._embedding_search.score(question, texts)
            text_scores = self._text_search.score(question, texts)
            query_terms = set(self._tokens(question))
            scored = []
            for candidate, embedding_score, text_score in zip(candidates, embedding_scores, text_scores):
                evidence_score = hybrid_score(
                    embedding_score,
                    text_score,
                    embedding_weight=self._evidence_embedding_weight,
                )
                overlap = len(query_terms & set(self._tokens(candidate.text)))
                lexical_bonus = overlap * 0.20
                if item.role == "focus_concept":
                    lexical_bonus += 0.25
                elif overlap > 0 and item.role == "seed_source":
                    lexical_bonus += 0.25
                specificity_bonus = self._specificity_bonus(candidate.text)
                scored.append(
                    _EvidenceCandidate(
                        page_id=candidate.page_id,
                        source_document_id=candidate.source_document_id,
                        source_block_ids=candidate.source_block_ids,
                        source_refs=candidate.source_refs,
                        text=candidate.text,
                        score=item.score + candidate.score + evidence_score + lexical_bonus + specificity_bonus,
                        unit_type=candidate.unit_type,
                    )
                )
            return scored

        query_terms = set(self._tokens(question))
        scored = []
        for candidate in candidates:
            overlap = len(query_terms & set(self._tokens(candidate.text)))
            score = candidate.score + item.score + overlap * 2.0
            if item.role == "focus_concept":
                score += 0.5
            elif overlap > 0 and item.role == "seed_source":
                score += 0.5
            if overlap == 0:
                score -= 0.75
            score += self._specificity_bonus(candidate.text)
            scored.append(
                _EvidenceCandidate(
                    page_id=candidate.page_id,
                    source_document_id=candidate.source_document_id,
                    source_block_ids=candidate.source_block_ids,
                    source_refs=candidate.source_refs,
                    text=candidate.text,
                    score=score,
                    unit_type=candidate.unit_type,
                )
            )
        return scored

    def _specificity_bonus(self, text: str) -> float:
        variable_markers = len(re.findall(r"\b[A-Z]\s*[:(]", text))
        numeric_ranges = len(re.findall(r"\d+(?:\.\d+)?\s*[-–]\s*\d+(?:\.\d+)?", text))
        units = len(re.findall(r"\d+(?:\.\d+)?\s*(?:mm|°|%|rpm|kw|v)\b", text, flags=re.IGNORECASE))
        list_separators = len(re.findall(r"[;,]", text))
        bonus = min(0.6, variable_markers * 0.08 + numeric_ranges * 0.08 + units * 0.04 + list_separators * 0.01)
        if re.search(r"\b(example|table|수준|범위|정의|조합)\b", text, flags=re.IGNORECASE):
            bonus += 0.15
        return min(0.8, bonus)

    def _section_heading(self, paragraph: str) -> str | None:
        for line in paragraph.splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            match = re.match(r"^##\s+(.+?)\s*$", stripped)
            if match:
                return match.group(1).strip().lower()
            return None
        return None

    def _dedupe_evidence(self, candidates: list[_EvidenceCandidate]) -> list[_EvidenceCandidate]:
        deduped: list[_EvidenceCandidate] = []
        seen: set[tuple[str, tuple[tuple[str, str], ...]]] = set()
        for candidate in candidates:
            key = self._candidate_key(candidate)
            if key in seen:
                continue
            seen.add(key)
            deduped.append(candidate)
        return deduped

    def _candidate_key(self, candidate: _EvidenceCandidate) -> tuple[str, tuple[tuple[str, str], ...]]:
        if candidate.source_refs:
            return (
                candidate.text,
                tuple((ref.source_document_id, ref.source_block_id) for ref in candidate.source_refs),
            )
        return (
            candidate.text,
            tuple((candidate.source_document_id, block_id) for block_id in candidate.source_block_ids),
        )

    def _split_paragraphs(self, text: str | None) -> list[str]:
        if not text:
            return []
        normalized = "\n".join(line.rstrip() for line in text.strip().splitlines())
        return [
            chunk.strip()
            for chunk in re.split(r"\n\s*\n", normalized)
            if chunk.strip() and not self._is_heading_only(chunk) and not self._is_frontmatter(chunk)
        ]

    def _is_heading_only(self, text: str) -> bool:
        lines = [line.strip() for line in text.strip().splitlines() if line.strip()]
        return bool(lines) and all(line.startswith("#") for line in lines)

    def _is_frontmatter(self, text: str) -> bool:
        lines = [line.strip() for line in text.strip().splitlines() if line.strip()]
        return len(lines) >= 2 and lines[0] == "---" and lines[-1] == "---"

    def _split_sentences(self, paragraph: str) -> list[str]:
        normalized = " ".join(line.strip() for line in paragraph.strip().splitlines() if line.strip())
        if not normalized:
            return []
        raw_chunks = [
            chunk.strip()
            for chunk in re.split(r"(?<=[.!?。！？])\s+|(?<=[다요죠니다까]\.)\s*", normalized)
            if chunk.strip()
        ]
        chunks: list[str] = []
        for chunk in raw_chunks:
            if chunks and self._is_block_ref_only(chunk):
                chunks[-1] = f"{chunks[-1]} {chunk}"
            else:
                chunks.append(chunk)
        sentences = [self._clean_sentence(chunk) for chunk in chunks if self._clean_sentence(chunk)]
        return sentences or [self._clean_sentence(normalized)]

    def _is_block_ref_only(self, text: str) -> bool:
        ref_pattern = r"(?:[A-Za-z0-9_.-]+:)?B\d{4}"
        return bool(re.fullmatch(rf"(?:\[(?:{ref_pattern})(?:\s*,\s*(?:{ref_pattern}))*\]\s*)+", text.strip()))

    def _clean_sentence(self, sentence: str) -> str:
        cleaned = sentence.strip()
        cleaned = re.sub(r"^#+\s*[^-–—:：]*\s*[-–—:：]\s*", "", cleaned)
        cleaned = re.sub(r"^#+\s*", "", cleaned)
        cleaned = re.sub(r"^[-*]\s+", "", cleaned)
        cleaned = re.sub(r"^(Summary|Definition|Why It Matters|Key Points|Evidence)\s+", "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"^(?:\[[A-Za-z0-9_,\s-]+\]\s*)+", "", cleaned)
        cleaned = re.sub(r"^[-*]\s+", "", cleaned)
        return cleaned.strip()

    def _source_document_id(self, item: RetrievedPage) -> str | None:
        if item.page.source_document_id:
            return item.page.source_document_id
        markdown = item.page.markdown or ""
        if item.page.is_source:
            return self._frontmatter_value(markdown, "document_id") or self._source_id_from_page_id(item.page.id)
        if item.page.is_concept:
            sources = self._frontmatter_value(markdown, "sources")
            if not sources:
                return None
            source_ids = [part.strip() for part in re.split(r"[,\s]+", sources) if part.strip()]
            if len(source_ids) == 1:
                return source_ids[0]
        return None

    def _source_id_from_page_id(self, page_id: str) -> str | None:
        if page_id.startswith("source:"):
            return page_id.split(":", 1)[1]
        return None

    def _frontmatter_value(self, markdown: str, key: str) -> str | None:
        if not markdown.startswith("---"):
            return None
        parts = markdown.split("---", 2)
        if len(parts) < 3:
            return None
        for line in parts[1].splitlines():
            match = re.match(rf"^{re.escape(key)}\s*:\s*(.+)$", line.strip(), flags=re.IGNORECASE)
            if match:
                return match.group(1).strip()
        return None

    def _has_global_source_refs(self, text: str | None) -> bool:
        return bool(text and re.search(r"\[[^\]]*[A-Za-z0-9_.-]+:B\d{4}", text))

    def _source_block_ids(self, text: str) -> list[str]:
        block_ids: list[str] = []
        ref_pattern = r"(?:[A-Za-z0-9_.-]+:)?B\d{4}"
        for group in re.findall(rf"\[((?:{ref_pattern})(?:\s*,\s*(?:{ref_pattern}))*)\]", text):
            for raw_ref in group.split(","):
                ref = raw_ref.strip()
                block_ids.append(ref.split(":", 1)[-1])
        return list(dict.fromkeys(block_ids))

    def _source_references(self, text: str, default_document_id: str | None) -> list[SourceReference]:
        refs: list[SourceReference] = []
        ref_pattern = r"(?:[A-Za-z0-9_.-]+:)?B\d{4}"
        for group in re.findall(rf"\[((?:{ref_pattern})(?:\s*,\s*(?:{ref_pattern}))*)\]", text):
            refs.extend(self._source_references_from_ids(group.split(","), default_document_id))
        return self._dedupe_source_refs(refs)

    def _source_references_from_ids(
        self,
        ref_ids: list[str],
        default_document_id: str | None,
    ) -> list[SourceReference]:
        refs: list[SourceReference] = []
        for raw_ref in ref_ids:
            ref = raw_ref.strip()
            if not ref or ref == "web":
                continue
            if ":" in ref:
                document_id, block_id = ref.split(":", 1)
            else:
                document_id, block_id = default_document_id, ref
            if document_id and re.fullmatch(r"B\d{4}", block_id):
                refs.append(SourceReference(source_document_id=document_id, source_block_id=block_id))
        return self._dedupe_source_refs(refs)

    def _legacy_source_fields(
        self,
        refs: list[SourceReference],
        default_document_id: str,
    ) -> tuple[str, list[str]]:
        if not refs:
            return default_document_id, []
        primary_document_id = refs[0].source_document_id
        return primary_document_id, [
            ref.source_block_id
            for ref in refs
            if ref.source_document_id == primary_document_id
        ]

    def _dedupe_source_refs(self, refs: list[SourceReference]) -> list[SourceReference]:
        deduped: list[SourceReference] = []
        seen: set[tuple[str, str]] = set()
        for ref in refs:
            key = (ref.source_document_id, ref.source_block_id)
            if key in seen:
                continue
            seen.add(key)
            deduped.append(ref)
        return deduped

    def _remove_block_refs(self, text: str) -> str:
        ref_pattern = r"(?:[A-Za-z0-9_.-]+:)?B\d{4}"
        cleaned = re.sub(rf"\s*\[(?:{ref_pattern})(?:\s*,\s*(?:{ref_pattern}))*\]", "", text)
        return cleaned.strip()

    def _tokens(self, text: str) -> list[str]:
        return [
            token
            for raw_token in re.findall(r"[A-Za-z0-9가-힣_.-]+", text.lower())
            if (token := self._normalize_token(raw_token))
        ]

    def _normalize_token(self, token: str) -> str:
        for suffix in ["으로부터", "로부터", "에게서", "한테서", "에게", "한테", "으로", "로", "이랑", "랑", "이나", "나", "은", "는", "이", "가", "을", "를", "에", "의", "도", "만", "와", "과"]:
            if token.endswith(suffix) and len(token) > len(suffix) + 1:
                return token[: -len(suffix)]
        return token
