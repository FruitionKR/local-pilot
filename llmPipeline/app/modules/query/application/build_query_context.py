import re

from app.modules.query.domain.entities import EvidenceSnippet, GraphContext, QueryContext, RetrievedPage, TraversalPath


class BuildQueryContextUseCase:
    def __init__(
        self,
        max_related_pages: int = 8,
        max_paths: int = 5,
        max_paragraphs_per_page: int = 3,
        max_paragraph_chars: int = 900,
    ) -> None:
        self._max_related_pages = max_related_pages
        self._max_paths = max_paths
        self._max_paragraphs_per_page = max_paragraphs_per_page
        self._max_paragraph_chars = max_paragraph_chars

    def execute(
        self,
        question: str,
        related_pages: list[RetrievedPage],
        graph_context: GraphContext,
        traversal_paths: list[TraversalPath],
    ) -> QueryContext:
        evidence_snippets = self._build_evidence_snippets(question, related_pages)
        return QueryContext(
            question=question,
            graph_context=graph_context,
            traversal_paths=traversal_paths,
            related_pages=related_pages,
            evidence_snippets=evidence_snippets,
            answer_context=self._build_answer_context(question, related_pages, traversal_paths, evidence_snippets),
        )

    def _build_answer_context(
        self,
        question: str,
        related_pages: list[RetrievedPage],
        traversal_paths: list[TraversalPath],
        evidence_snippets: list[EvidenceSnippet],
    ) -> str:
        pages_by_id = {item.page.id: item for item in related_pages}
        lines = [
            "# User Question",
            question,
            "",
            "# Assistant Output Policy",
            "- Answer in Korean.",
            "- Write only the conversational answer body that should be shown to the user.",
            "- Mark the evidence used for each supported sentence with citation markers like [1] or [2].",
            "- Use only the evidence rank numbers listed below as citation markers.",
            "- Every sentence that contains factual content from evidence must end with at least one citation marker.",
            "- Do not write uncited factual sentences.",
            "- Do not expose evidence lists, scores, path ids, page ids, or page URLs in the answer body.",
            "- If the evidence directly answers the question, answer naturally from that evidence.",
            "- If the evidence does not contain a direct definition or explanation, say that the exact answer is not sufficiently supported.",
            "- For unsupported questions, do not explain the answer from general knowledge; mention only that the provided evidence does not support it and, if useful, name the closest related evidence topic.",
            "- Do not create examples, analogies, or fictional cases that are not present in the context.",
            "- If an example is needed, use only entities or cases that appear in the evidence.",
            "- Do not add information from outside the context.",
            "",
            "# Related Pages By Relevance",
        ]
        for item in related_pages[: self._max_related_pages]:
            lines.extend(
                [
                    f"- id: {item.page.id}",
                    f"  type: {item.page.page_type}",
                    f"  title: {item.page.title}",
                    f"  role: {item.role}",
                    f"  score: {item.score:.3f}",
                    f"  depth: {item.depth}",
                    f"  summary: {item.page.summary}",
                ]
            )
        lines.extend(["", "# Evidence Snippets By Relevance"])
        for snippet in evidence_snippets:
            lines.extend(
                [
                    f"## Evidence {snippet.rank}",
                    f"- page: {snippet.page_title}",
                    f"- page_type: {snippet.page_type}",
                    f"- page_role: {snippet.page_role}",
                    f"- evidence_score: {snippet.score:.3f}",
                    "- text:",
                    self._indent(self._excerpt(snippet.text, self._max_paragraph_chars), spaces=2),
                    "",
                ]
            )

        lines.extend(["", "# Traversal Paths"])
        for path in traversal_paths[: self._max_paths]:
            path_titles = [pages_by_id[node_id].page.title if node_id in pages_by_id else node_id for node_id in path.nodes]
            lines.extend(
                [
                    f"- titles: {' -> '.join(path_titles)}",
                ]
            )
        return "\n".join(lines).strip()

    def _build_evidence_snippets(self, question: str, related_pages: list[RetrievedPage]) -> list[EvidenceSnippet]:
        candidates = []
        for item in related_pages[: self._max_related_pages]:
            for sentence_score, sentence, paragraph_index, sentence_index in self._score_evidence_sentences(question, item):
                candidates.append(
                    EvidenceSnippet(
                        page_id=item.page.id,
                        page_type=item.page.page_type,
                        page_title=item.page.title,
                        page_slug=item.page.slug,
                        page_url=self._page_url(item.page),
                        page_role=item.role,
                        text=sentence,
                        score=sentence_score,
                        rank=0,
                        paragraph_index=paragraph_index,
                        sentence_index=sentence_index,
                    )
                )

        candidates.sort(key=lambda snippet: snippet.score, reverse=True)
        return [
            EvidenceSnippet(
                page_id=snippet.page_id,
                page_type=snippet.page_type,
                page_title=snippet.page_title,
                page_slug=snippet.page_slug,
                page_url=snippet.page_url,
                page_role=snippet.page_role,
                text=snippet.text,
                score=snippet.score,
                rank=index,
                paragraph_index=snippet.paragraph_index,
                sentence_index=snippet.sentence_index,
            )
            for index, snippet in enumerate(candidates, start=1)
        ]

    def _select_evidence_paragraphs(self, question: str, item: RetrievedPage) -> list[str]:
        content = item.page.markdown or item.page.summary
        paragraphs = self._split_paragraphs(content)
        if not paragraphs:
            return []

        query_terms = set(self._tokens(question))
        scored = self._score_evidence_paragraphs(question, item)
        selected = sorted(scored[: self._max_paragraphs_per_page], key=lambda value: value[2])
        return [paragraph for _, paragraph, _ in selected]

    def _score_evidence_paragraphs(self, question: str, item: RetrievedPage) -> list[tuple[float, str, int]]:
        content = item.page.markdown or item.page.summary
        paragraphs = self._split_paragraphs(content)
        if not paragraphs:
            return []

        query_terms = set(self._tokens(question))
        scored = []
        for index, paragraph in enumerate(paragraphs):
            paragraph_terms = set(self._tokens(paragraph))
            overlap = len(query_terms & paragraph_terms)
            score = overlap * 2.0 + item.score
            if item.role == "focus_concept":
                score += 0.5
            elif overlap > 0 and item.role == "seed_source":
                score += 0.5
            if overlap > 0 and index == 0:
                score += 0.15
            if overlap == 0:
                score -= 0.75
            scored.append((score, paragraph, index))

        scored.sort(key=lambda value: (-value[0], value[2]))
        return scored[: self._max_paragraphs_per_page]

    def _score_evidence_sentences(self, question: str, item: RetrievedPage) -> list[tuple[float, str, int, int]]:
        content = item.page.markdown or item.page.summary
        paragraphs = self._split_paragraphs(content)
        if not paragraphs:
            return []

        query_terms = set(self._tokens(question))
        scored = []
        for paragraph_index, paragraph in enumerate(paragraphs):
            for sentence_index, sentence in enumerate(self._split_sentences(paragraph)):
                sentence_terms = set(self._tokens(sentence))
                overlap = len(query_terms & sentence_terms)
                score = overlap * 2.0 + item.score
                if item.role == "focus_concept":
                    score += 0.5
                elif overlap > 0 and item.role == "seed_source":
                    score += 0.5
                if overlap > 0 and paragraph_index == 0:
                    score += 0.15
                if overlap == 0:
                    score -= 0.75
                scored.append((score, sentence, paragraph_index, sentence_index))

        scored.sort(key=lambda value: (-value[0], value[2], value[3]))
        return scored[: self._max_paragraphs_per_page]

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
        sentences = [
            self._clean_sentence(chunk)
            for chunk in re.split(r"(?<=[.!?。！？])\s+|(?<=[다요죠니다까])\.\s*", normalized)
            if self._clean_sentence(chunk)
        ]
        return sentences or [self._clean_sentence(normalized)]

    def _clean_sentence(self, sentence: str) -> str:
        cleaned = sentence.strip()
        cleaned = re.sub(r"^#+\s*[^-–—:：]*\s*[-–—:：]\s*", "", cleaned)
        cleaned = re.sub(r"^#+\s*", "", cleaned)
        cleaned = re.sub(r"^[-*]\s+", "", cleaned)
        cleaned = re.sub(r"^(Summary|Definition|Why It Matters|Key Points|Evidence)\s+", "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"^(?:\[[A-Za-z0-9_,\s-]+\]\s*)+", "", cleaned)
        cleaned = re.sub(r"^[-*]\s+", "", cleaned)
        return cleaned.strip()

    def _tokens(self, text: str) -> list[str]:
        return re.findall(r"[A-Za-z0-9가-힣_.-]+", text.lower())

    def _excerpt(self, text: str, limit: int = 500) -> str:
        normalized = "\n".join(line.rstrip() for line in text.strip().splitlines())
        if len(normalized) <= limit:
            return normalized
        return normalized[: limit - 3].rstrip() + "..."

    def _indent(self, text: str, spaces: int = 2) -> str:
        prefix = " " * spaces
        return "\n".join(f"{prefix}{line}" if line else "" for line in text.splitlines())

    def _page_url(self, page) -> str:
        if page.page_type == "web" and page.markdown_uri:
            return page.markdown_uri
        return f"/api/wiki/pages/{page.id}"

