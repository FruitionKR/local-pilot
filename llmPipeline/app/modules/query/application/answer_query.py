from app.modules.query.application.build_query_context import BuildQueryContextUseCase
from dataclasses import replace

from app.modules.query.application.extract_answer_citations import ExtractAnswerCitationsUseCase
from app.modules.query.application.ports import (
    AnswerGeneratorPort,
    EmbeddingSearchPort,
    QueryEventPublisherPort,
    TextSearchPort,
    WikiMarkdownReaderPort,
    WikiRepositoryPort,
)
from app.modules.query.application.traverse_wiki_graph import TraverseWikiGraphUseCase
from app.modules.query.domain.entities import (
    EvidenceSnippet,
    GeneratedAnswer,
    GraphContext,
    QueryAnswer,
    RetrievedPage,
    RetrievalSummary,
    TraversalPath,
    WikiPage,
    WikiPageLink,
)
from app.modules.query.domain.scoring import hybrid_score
from app.modules.query.domain.value_objects import Question


class AnswerQueryUseCase:
    def __init__(
        self,
        wiki_repository: WikiRepositoryPort,
        embedding_search: EmbeddingSearchPort,
        text_search: TextSearchPort,
        answer_generator: AnswerGeneratorPort,
        markdown_reader: WikiMarkdownReaderPort | None = None,
        event_publisher: QueryEventPublisherPort | None = None,
        traverse_wiki_graph: TraverseWikiGraphUseCase | None = None,
        build_query_context: BuildQueryContextUseCase | None = None,
        extract_answer_citations: ExtractAnswerCitationsUseCase | None = None,
        source_candidate_limit: int = 15,
        concept_candidate_limit: int = 10,
        focus_concept_threshold: float = 0.60,
        returned_path_limit: int = 5,
    ) -> None:
        self._wiki_repository = wiki_repository
        self._embedding_search = embedding_search
        self._text_search = text_search
        self._answer_generator = answer_generator
        self._markdown_reader = markdown_reader
        self._event_publisher = event_publisher
        self._traverse_wiki_graph = traverse_wiki_graph or TraverseWikiGraphUseCase()
        self._build_query_context = build_query_context or BuildQueryContextUseCase()
        self._extract_answer_citations = extract_answer_citations or ExtractAnswerCitationsUseCase()
        self._source_candidate_limit = source_candidate_limit
        self._concept_candidate_limit = concept_candidate_limit
        self._focus_concept_threshold = focus_concept_threshold
        self._returned_path_limit = returned_path_limit

    def execute(self, question: str) -> QueryAnswer:
        query = Question(question)
        self._publish("query_started", "질의 처리를 시작했습니다.", {"question": query.normalized})
        pages = self._wiki_repository.list_active_pages()
        links = self._wiki_repository.list_active_links()
        self._publish("wiki_loaded", "활성 Wiki page/link를 로드했습니다.", {"page_count": len(pages), "link_count": len(links)})
        pages = self._load_markdown_for_scoring(pages)
        self._publish(
            "retrieval_markdown_loaded",
            "검색용 Wiki Markdown 본문을 로드했습니다.",
            {"loaded_markdown_count": len([page for page in pages if page.markdown])},
        )
        pages_by_id = {page.id: page for page in pages}
        source_pages = [page for page in pages if page.is_source]
        concept_pages = [page for page in pages if page.is_concept]

        source_scores = self._score_pages(query.normalized, source_pages, embedding_weight=0.8)
        concept_scores = self._score_pages(query.normalized, concept_pages, embedding_weight=0.8)
        self._publish(
            "retrieval_scored",
            "source/concept 후보 점수를 계산했습니다.",
            {
                "source_count": len(source_scores),
                "concept_count": len(concept_scores),
                "top_source_id": self._top_id(source_scores),
                "top_concept_id": self._top_id(concept_scores),
            },
        )
        node_scores = {**source_scores, **concept_scores}

        seed_source_ids = self._select_seed_sources(source_pages, source_scores)
        focus_concept_ids = self._select_focus_concepts(concept_pages, concept_scores)
        self._publish(
            "seeds_selected",
            "탐색 시작 source와 focus concept hint를 선택했습니다.",
            {"seed_source_ids": seed_source_ids, "focus_concept_ids": focus_concept_ids},
        )

        graph_context, traversal_paths, stop_reason = self._traverse_wiki_graph.execute(
            pages_by_id=pages_by_id,
            links=links,
            seed_source_ids=seed_source_ids,
            node_scores=node_scores,
        )
        self._publish(
            "graph_traversed",
            "Wiki graph traversal을 완료했습니다.",
            {"visited_node_count": len(graph_context.nodes), "path_count": len(traversal_paths), "stop_reason": stop_reason},
        )
        traversal_paths = self._select_answer_paths(traversal_paths)
        related_pages = self._load_markdown_for_related_pages(graph_context.nodes)
        graph_context = GraphContext(nodes=related_pages, edges=graph_context.edges)
        self._publish(
            "markdown_loaded",
            "선택된 Wiki page의 Markdown 본문을 로드했습니다.",
            {"loaded_markdown_count": len([item for item in related_pages if item.page.markdown])},
        )
        query_context = self._build_query_context.execute(
            question=query.normalized,
            related_pages=related_pages,
            graph_context=graph_context,
            traversal_paths=traversal_paths,
        )
        self._publish(
            "context_built",
            "LLM 답변 입력 context를 구성했습니다.",
            {"context_chars": len(query_context.answer_context), "related_page_count": len(related_pages)},
        )
        answer = self._answer_generator.generate_answer(query_context)
        if stop_reason == "no_relevant_seed":
            answer = self._unsupported_answer(query_context.evidence_snippets)
        else:
            answer = GeneratedAnswer(
                content=self._extract_answer_citations.ensure_sentence_citations(
                    answer.content,
                    query_context.evidence_snippets[0].rank if query_context.evidence_snippets else None,
                )
            )
        self._publish("answer_generated", "답변 생성을 완료했습니다.", {"answer_chars": len(answer.content)})

        used_source_count = len([item for item in related_pages if item.page.is_source])
        used_concept_count = len([item for item in related_pages if item.page.is_concept])
        summary = RetrievalSummary(
            source_candidate_count=min(len(source_pages), self._source_candidate_limit),
            concept_candidate_count=min(len(concept_pages), self._concept_candidate_limit),
            visited_node_count=len(related_pages),
            returned_node_count=len(related_pages),
            used_source_count=used_source_count,
            used_concept_count=used_concept_count,
            max_depth=0,
            stop_reason=stop_reason,
        )
        return QueryAnswer(
            answer=answer,
            related_pages=related_pages,
            evidence_snippets=query_context.evidence_snippets,
            graph_context=graph_context,
            traversal_paths=traversal_paths,
            retrieval_summary=summary,
        )

    def _score_pages(self, query: str, pages: list[WikiPage], embedding_weight: float) -> dict[str, float]:
        representations = [self._representation(page) for page in pages]
        embedding_scores = self._embedding_search.score(query, representations)
        text_scores = self._text_search.score(query, representations)
        return {
            page.id: hybrid_score(embedding_score, text_score, embedding_weight=embedding_weight)
            for page, embedding_score, text_score in zip(pages, embedding_scores, text_scores)
        }

    def _top_id(self, scores: dict[str, float]) -> str | None:
        if not scores:
            return None
        return max(scores, key=scores.get)

    def _representation(self, page: WikiPage) -> str:
        markdown = page.markdown or ""
        return "\n".join([page.title, page.summary, markdown])

    def _select_seed_sources(self, source_pages: list[WikiPage], source_scores: dict[str, float]) -> list[str]:
        ranked = sorted(source_pages, key=lambda page: source_scores.get(page.id, 0.0), reverse=True)
        return [ranked[0].id] if ranked else []

    def _select_focus_concepts(self, concept_pages: list[WikiPage], concept_scores: dict[str, float]) -> list[str]:
        ranked = sorted(concept_pages, key=lambda page: concept_scores.get(page.id, 0.0), reverse=True)
        focus = [page.id for page in ranked if concept_scores.get(page.id, 0.0) >= self._focus_concept_threshold]
        return focus[: self._concept_candidate_limit]

    def _add_sources_connected_to_focus_concepts(
        self,
        seed_source_ids: list[str],
        focus_concept_ids: list[str],
        links: list[WikiPageLink],
    ) -> list[str]:
        seeds = list(dict.fromkeys(seed_source_ids))
        focus_set = set(focus_concept_ids)
        for link in links:
            if link.link_type != "source_mentions_concept":
                continue
            if link.to_page_id in focus_set and link.from_page_id not in seeds:
                seeds.append(link.from_page_id)
            elif link.from_page_id in focus_set and link.to_page_id not in seeds:
                seeds.append(link.to_page_id)
        return seeds

    def _select_answer_paths(self, traversal_paths: list[TraversalPath]) -> list[TraversalPath]:
        selected = sorted(traversal_paths, key=lambda path: path.score, reverse=True)[: self._returned_path_limit]
        return [
            replace(path, role="primary_answer_path" if index == 0 else "candidate_path")
            for index, path in enumerate(selected)
        ]

    def _unsupported_answer(self, evidence_snippets: list[EvidenceSnippet]) -> GeneratedAnswer:
        if not evidence_snippets:
            return GeneratedAnswer(content="제공된 근거에서 질문에 직접 답할 내용을 찾지 못했습니다.")
        nearest = evidence_snippets[0]
        return GeneratedAnswer(
            content=(
                "제공된 근거에서 질문에 직접 답할 내용을 찾지 못했습니다. "
                f"가장 가까운 자료는 {nearest.page_title}이지만, 이 자료도 질문 주제를 직접 설명하지 않습니다. [{nearest.rank}]"
            )
        )

    def _load_markdown_for_related_pages(self, related_pages: list[RetrievedPage]) -> list[RetrievedPage]:
        if self._markdown_reader is None:
            return related_pages

        loaded: list[RetrievedPage] = []
        for item in related_pages:
            page = item.page
            if page.markdown or not page.markdown_uri:
                loaded.append(item)
                continue
            try:
                markdown = self._markdown_reader.read_markdown(page.markdown_uri)
            except Exception:
                loaded.append(item)
                continue
            loaded.append(replace(item, page=replace(page, markdown=markdown)))
        return loaded

    def _load_markdown_for_scoring(self, pages: list[WikiPage]) -> list[WikiPage]:
        if self._markdown_reader is None:
            return pages

        loaded: list[WikiPage] = []
        for page in pages:
            if page.markdown or not page.markdown_uri:
                loaded.append(page)
                continue
            try:
                loaded.append(replace(page, markdown=self._markdown_reader.read_markdown(page.markdown_uri)))
            except Exception:
                loaded.append(page)
        return loaded

    def _publish(self, stage: str, message: str, data: dict[str, object] | None = None) -> None:
        if self._event_publisher is None:
            return
        try:
            self._event_publisher.publish(stage, message, data)
        except Exception:
            return

