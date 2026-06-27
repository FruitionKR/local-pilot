import hashlib
import re
from app.modules.query.application.build_query_context import BuildQueryContextUseCase
from dataclasses import replace

from app.modules.query.application.ports import (
    AnswerGeneratorPort,
    EmbeddingSearchPort,
    QueryEventPublisherPort,
    QueryEvaluatorPort,
    QueryRewritePort,
    TextSearchPort,
    WebSearchPort,
    WikiMarkdownReaderPort,
    WikiRepositoryPort,
)
from app.modules.query.application.query_answer_assembler import QueryAnswerAssembler
from app.modules.query.application.traverse_wiki_graph import TraverseWikiGraphUseCase
from app.modules.query.domain.entities import (
    EvidenceSnippet,
    GeneratedAnswer,
    ConversationContext,
    GraphContext,
    QueryAnswer,
    QueryContext,
    QueryEvaluation,
    QueryRewrite,
    RetrievedPage,
    RetrievalSummary,
    TraversalEdge,
    TraversalPath,
    WebSearchResult,
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
        query_rewriter: QueryRewritePort | None = None,
        query_evaluator: QueryEvaluatorPort | None = None,
        web_search: WebSearchPort | None = None,
        traverse_wiki_graph: TraverseWikiGraphUseCase | None = None,
        build_query_context: BuildQueryContextUseCase | None = None,
        query_answer_assembler: QueryAnswerAssembler | None = None,
        source_candidate_limit: int = 15,
        concept_candidate_limit: int = 10,
        focus_concept_threshold: float = 0.60,
        returned_path_limit: int = 5,
        min_internal_relevance_score: float = 0.0,
    ) -> None:
        self._wiki_repository = wiki_repository
        self._embedding_search = embedding_search
        self._text_search = text_search
        self._answer_generator = answer_generator
        self._markdown_reader = markdown_reader
        self._event_publisher = event_publisher
        self._query_rewriter = query_rewriter
        self._query_evaluator = query_evaluator
        self._web_search = web_search
        self._traverse_wiki_graph = traverse_wiki_graph or TraverseWikiGraphUseCase()
        self._build_query_context = build_query_context or BuildQueryContextUseCase(
            embedding_search=embedding_search,
            text_search=text_search,
        )
        self._query_answer_assembler = query_answer_assembler or QueryAnswerAssembler(answer_generator)
        self._source_candidate_limit = source_candidate_limit
        self._concept_candidate_limit = concept_candidate_limit
        self._focus_concept_threshold = focus_concept_threshold
        self._returned_path_limit = returned_path_limit
        self._min_internal_relevance_score = min_internal_relevance_score

    def execute(
        self,
        question: str,
        event_publisher: QueryEventPublisherPort | None = None,
        conversation_context: ConversationContext | None = None,
    ) -> QueryAnswer:
        event_publisher = event_publisher or self._event_publisher
        query = Question(question)
        self._publish(event_publisher, "query_started", "질의 처리를 시작했습니다.", {"question": query.normalized})
        contextual_question = self._contextualize_question(query.normalized, conversation_context)
        if contextual_question != query.normalized:
            self._publish(
                event_publisher,
                "query_contextualized",
                "대화 요약과 참조 메모리로 검색용 질문을 보강했습니다.",
                {"contextual_question": contextual_question},
            )
        query_rewrite = self._rewrite_query(contextual_question)
        if query_rewrite.retrieval_query != query.normalized:
            self._publish(
                event_publisher,
                "query_rewritten",
                "검색용 질의를 정제했습니다.",
                {"retrieval_query": query_rewrite.retrieval_query, "keywords": query_rewrite.keywords},
            )
        pages = self._wiki_repository.list_active_pages()
        links = self._wiki_repository.list_active_links()
        self._publish(event_publisher, "wiki_loaded", "활성 Wiki page/link를 로드했습니다.", {"page_count": len(pages), "link_count": len(links)})
        pages = self._load_markdown_for_scoring(pages)
        self._publish(
            event_publisher,
            "retrieval_markdown_loaded",
            "검색용 Wiki Markdown 본문을 로드했습니다.",
            {"loaded_markdown_count": len([page for page in pages if page.markdown])},
        )
        pages_by_id = {page.id: page for page in pages}
        source_pages = [page for page in pages if page.is_source]
        concept_pages = [page for page in pages if page.is_concept]

        source_scores = self._score_pages(query_rewrite, source_pages, embedding_weight=0.8)
        concept_scores = self._score_pages(query_rewrite, concept_pages, embedding_weight=0.8)
        self._publish(
            event_publisher,
            "retrieval_scored",
            "source/concept 후보 점수를 계산했습니다.",
            {
                "source_count": len(source_scores),
                "concept_count": len(concept_scores),
                "top_source_id": self._top_id(source_scores),
                "top_concept_id": self._top_id(concept_scores),
                "top_sources": self._top_scores(source_scores),
                "top_concepts": self._top_scores(concept_scores),
            },
        )
        node_scores = {**source_scores, **concept_scores}
        direct_concept_ids = self._select_direct_match_concepts(query_rewrite, concept_pages)

        if self._query_evaluator is None and self._should_use_web_fallback(source_scores, concept_scores):
            fallback_answer = self._answer_from_web_search(query.normalized, query_rewrite, event_publisher)
            if fallback_answer is not None:
                return fallback_answer

        seed_source_ids = self._select_seed_sources(source_pages, source_scores)
        focus_concept_ids = self._select_focus_concepts(concept_pages, concept_scores)
        if direct_concept_ids and max(source_scores.values(), default=0.0) < self._focus_concept_threshold:
            seed_source_ids = self._add_sources_connected_to_focus_concepts(seed_source_ids, direct_concept_ids, links)
        seed_page_ids = list(dict.fromkeys([*seed_source_ids, *focus_concept_ids, *direct_concept_ids]))
        self._publish(
            event_publisher,
            "seeds_selected",
            "탐색 시작 source와 focus concept hint를 선택했습니다.",
            {
                "seed_page_ids": seed_page_ids,
                "seed_source_ids": seed_source_ids,
                "focus_concept_ids": focus_concept_ids,
                "direct_concept_ids": direct_concept_ids,
            },
        )

        graph_context, traversal_paths, stop_reason = self._traverse_wiki_graph.execute(
            pages_by_id=pages_by_id,
            links=links,
            seed_page_ids=seed_page_ids,
            node_scores=node_scores,
        )
        self._publish(
            event_publisher,
            "graph_traversed",
            "Wiki graph traversal을 완료했습니다.",
            {"visited_node_count": len(graph_context.nodes), "path_count": len(traversal_paths), "stop_reason": stop_reason},
        )
        traversal_paths = self._select_answer_paths(traversal_paths)
        related_pages = self._add_focus_concepts_to_related_pages(graph_context.nodes, direct_concept_ids, pages_by_id, concept_scores)
        if direct_concept_ids and {item.page.id for item in related_pages} & set(direct_concept_ids):
            stop_reason = "concept_direct_match"
        graph_context, traversal_paths = self._backfill_direct_concept_paths(
            graph_context=GraphContext(nodes=related_pages, edges=graph_context.edges),
            traversal_paths=traversal_paths,
            links=links,
            direct_concept_ids=direct_concept_ids,
            source_scores=source_scores,
            concept_scores=concept_scores,
        )
        if stop_reason == "concept_direct_match":
            traversal_paths = [
                replace(path, stop_reason="concept_direct_match")
                if set(path.nodes) & set(direct_concept_ids)
                else path
                for path in traversal_paths
            ]
        related_pages = self._load_markdown_for_related_pages(related_pages)
        graph_context = GraphContext(nodes=related_pages, edges=graph_context.edges)
        evidence_question = self._evidence_question(query.normalized, conversation_context, contextual_question)
        self._publish(
            event_publisher,
            "markdown_loaded",
            "선택된 Wiki page의 Markdown 본문을 로드했습니다.",
            {"loaded_markdown_count": len([item for item in related_pages if item.page.markdown])},
        )
        embedding_units_by_page_id = self._load_embedding_units_for_related_pages(related_pages)
        query_context = self._build_query_context.execute(
            question=contextual_question,
            related_pages=related_pages,
            graph_context=graph_context,
            traversal_paths=traversal_paths,
            original_question=query.normalized,
            evidence_question=evidence_question,
            embedding_units_by_page_id=embedding_units_by_page_id,
        )
        self._publish(
            event_publisher,
            "context_built",
            "LLM 답변 입력 context를 구성했습니다.",
            {"context_chars": len(query_context.answer_context), "related_page_count": len(related_pages)},
        )
        answer, evidence_snippets = self._query_answer_assembler.generate_supported_answer(query_context)
        self._publish(event_publisher, "answer_generated", "답변 생성을 완료했습니다.", {"answer_chars": len(answer.content)})

        evaluated_context = replace(query_context, evidence_snippets=evidence_snippets)
        query_evaluation = self._evaluate_query(query.normalized, evaluated_context, answer, stop_reason, event_publisher)
        if query_evaluation is not None:
            if query_evaluation.route == "web_fallback":
                fallback_answer = self._answer_from_web_search(
                    query.normalized,
                    self._query_rewrite_for_web(query_rewrite, query_evaluation),
                    event_publisher,
                )
                if fallback_answer is not None:
                    return fallback_answer
            if query_evaluation.route == "internal_web_augmented":
                augmented_answer = self._answer_from_internal_web_augmented(
                    question=query.normalized,
                    query_rewrite=self._query_rewrite_for_web(query_rewrite, query_evaluation),
                    query_context=query_context,
                    graph_context=graph_context,
                    traversal_paths=traversal_paths,
                    stop_reason="internal_web_augmented",
                    event_publisher=event_publisher,
                )
                if augmented_answer is not None:
                    return augmented_answer
            if query_evaluation.route == "unsupported":
                stop_reason = "query_evaluator_unsupported"
                answer = self._unsupported_answer(evidence_snippets)
                answer, evidence_snippets = self._query_answer_assembler.renumber_used_evidence(answer, evidence_snippets)
            elif query_evaluation.route == "internal_supported" and stop_reason == "no_relevant_seed":
                stop_reason = "query_evaluator_internal_supported"
        elif stop_reason == "no_relevant_seed":
            answer = self._unsupported_answer(evidence_snippets)
            answer, evidence_snippets = self._query_answer_assembler.renumber_used_evidence(answer, evidence_snippets)

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
            evidence_snippets=evidence_snippets,
            graph_context=graph_context,
            traversal_paths=traversal_paths,
            retrieval_summary=summary,
        )

    def _rewrite_query(self, question: str) -> QueryRewrite:
        if self._query_rewriter is None:
            return QueryRewrite(original_question=question, retrieval_query=question)
        try:
            rewritten = self._query_rewriter.rewrite(question)
        except Exception:
            return QueryRewrite(original_question=question, retrieval_query=question)
        if not rewritten.retrieval_query.strip():
            return QueryRewrite(original_question=question, retrieval_query=question)
        return rewritten

    def _evaluate_query(
        self,
        question: str,
        query_context: QueryContext,
        answer: GeneratedAnswer,
        stop_reason: str,
        event_publisher: QueryEventPublisherPort | None,
    ) -> QueryEvaluation | None:
        if self._query_evaluator is None:
            return None
        try:
            evaluation = self._query_evaluator.evaluate(
                question,
                query_context,
                answer,
                stop_reason,
                web_search_available=self._web_search is not None,
            )
        except Exception as exc:
            self._publish(event_publisher, "query_evaluation_failed", "Query evaluator 실행에 실패했습니다.", {"error": str(exc)})
            return None
        self._publish(
            event_publisher,
            "query_evaluated",
            "검색 근거와 질문의 정합성을 평가했습니다.",
            {
                "route": evaluation.route,
                "evidence_relevance": round(evaluation.evidence_relevance, 4),
                "reason": evaluation.reason,
                "web_query": evaluation.web_query,
            },
        )
        return evaluation

    def _query_rewrite_for_web(self, query_rewrite: QueryRewrite, evaluation: QueryEvaluation) -> QueryRewrite:
        if not evaluation.web_query:
            return query_rewrite
        return QueryRewrite(
            original_question=query_rewrite.original_question,
            retrieval_query=evaluation.web_query,
            keywords=evaluation.web_query.split(),
        )

    def _contextualize_question(self, question: str, conversation_context: ConversationContext | None) -> str:
        if conversation_context is None:
            return question

        sections = []
        matched_referents = self._matching_referent_values(question, conversation_context.reference_context)
        if matched_referents:
            sections.append(" ".join(matched_referents))

        reference_lines = self._reference_context_lines(conversation_context.reference_context, excluded_values=set(matched_referents))
        if reference_lines:
            sections.append(" ".join(reference_lines))

        if conversation_context.recent_conversation_summary:
            sections.append(conversation_context.recent_conversation_summary.strip())

        sections.append(question)

        return "\n".join(section for section in sections if section.strip()).strip()

    def _evidence_question(
        self,
        question: str,
        conversation_context: ConversationContext | None,
        contextual_question: str,
    ) -> str:
        if conversation_context is None:
            return contextual_question
        matched_referents = self._matching_referent_values(question, conversation_context.reference_context)
        if not matched_referents:
            return question
        return " ".join([*matched_referents, question])

    def _matching_referent_values(self, question: str, reference_context: dict[str, object]) -> list[str]:
        referents = reference_context.get("referents")
        if not isinstance(referents, dict):
            return []
        values = []
        for marker, value in referents.items():
            if str(marker) in question and value is not None:
                values.extend(self._reference_values(value))
        return list(dict.fromkeys(value for value in values if value))

    def _reference_context_lines(self, reference_context: dict[str, object], excluded_values: set[str] | None = None) -> list[str]:
        excluded_values = excluded_values or set()
        lines: list[str] = []
        for key, value in reference_context.items():
            if value is None:
                continue
            lines.extend(
                line
                for line in self._format_reference_value(str(key), value)
                if line and line not in excluded_values
            )
        return lines

    def _format_reference_value(self, key: str, value: object) -> list[str]:
        if isinstance(value, dict):
            lines = []
            for _, child_value in value.items():
                if child_value is None:
                    continue
                lines.extend(self._reference_values(child_value))
            return lines
        if isinstance(value, list):
            return [item for value in value if value is not None for item in self._reference_values(value)]
        return [self._reference_scalar(value)]

    def _reference_values(self, value: object) -> list[str]:
        if isinstance(value, dict):
            values: list[str] = []
            for child_value in value.values():
                if child_value is not None:
                    values.extend(self._reference_values(child_value))
            return values
        if isinstance(value, list):
            return [item for child_value in value if child_value is not None for item in self._reference_values(child_value)]
        return [self._reference_scalar(value)]

    def _reference_scalar(self, value: object) -> str:
        if isinstance(value, (dict, list)):
            return str(value)
        return str(value).strip()

    def _should_use_web_fallback(self, source_scores: dict[str, float], concept_scores: dict[str, float]) -> bool:
        if self._web_search is None or self._min_internal_relevance_score <= 0:
            return False
        best_score = max([*source_scores.values(), *concept_scores.values(), 0.0])
        return best_score < self._min_internal_relevance_score

    def _answer_from_web_search(
        self,
        question: str,
        query_rewrite: QueryRewrite,
        event_publisher: QueryEventPublisherPort | None,
    ) -> QueryAnswer | None:
        if self._web_search is None:
            return None
        self._publish(
            event_publisher,
            "web_search_started",
            "내부 Wiki 근거가 부족해 웹 검색 fallback을 시작했습니다.",
            {"retrieval_query": query_rewrite.retrieval_query},
        )
        try:
            web_results = self._web_search.search(query_rewrite.retrieval_query)
        except Exception as exc:
            self._publish(event_publisher, "web_search_failed", "웹 검색 fallback이 실패했습니다.", {"error": str(exc)})
            return None
        related_pages = self._web_results_to_related_pages(web_results)
        if not related_pages:
            self._publish(event_publisher, "web_search_empty", "웹 검색 fallback 결과가 없습니다.", None)
            return None

        graph_context = GraphContext(nodes=related_pages, edges=[])
        query_context = self._build_query_context.execute(
            question=question,
            related_pages=related_pages,
            graph_context=graph_context,
            traversal_paths=[],
            answer_mode="web_fallback",
        )
        answer, evidence_snippets = self._query_answer_assembler.generate_supported_answer(query_context)
        self._publish(
            event_publisher,
            "web_search_answer_generated",
            "웹 검색 근거로 답변 생성을 완료했습니다.",
            {"result_count": len(web_results), "answer_chars": len(answer.content)},
        )
        summary = RetrievalSummary(
            source_candidate_count=0,
            concept_candidate_count=0,
            visited_node_count=len(related_pages),
            returned_node_count=len(related_pages),
            used_source_count=0,
            used_concept_count=0,
            max_depth=0,
            stop_reason="web_search_fallback",
        )
        return QueryAnswer(
            answer=answer,
            related_pages=related_pages,
            evidence_snippets=evidence_snippets,
            graph_context=graph_context,
            traversal_paths=[],
            retrieval_summary=summary,
        )

    def _answer_from_internal_web_augmented(
        self,
        question: str,
        query_rewrite: QueryRewrite,
        query_context: QueryContext,
        graph_context: GraphContext,
        traversal_paths: list[TraversalPath],
        stop_reason: str,
        event_publisher: QueryEventPublisherPort | None,
    ) -> QueryAnswer | None:
        if self._web_search is None:
            return None
        self._publish(
            event_publisher,
            "web_search_started",
            "내부 Wiki 근거에 외부 검색 근거를 보강합니다.",
            {"retrieval_query": query_rewrite.retrieval_query},
        )
        try:
            web_results = self._web_search.search(query_rewrite.retrieval_query)
        except Exception as exc:
            self._publish(event_publisher, "web_search_failed", "웹 검색 보강이 실패했습니다.", {"error": str(exc)})
            return None
        web_related_pages = self._web_results_to_related_pages(web_results)
        if not web_related_pages:
            self._publish(event_publisher, "web_search_empty", "웹 검색 보강 결과가 없습니다.", None)
            return None

        related_pages = self._merge_related_pages(query_context.related_pages, web_related_pages)
        augmented_graph_context = GraphContext(nodes=related_pages, edges=graph_context.edges)
        augmented_context = self._build_query_context.execute(
            question=query_context.question,
            related_pages=related_pages,
            graph_context=augmented_graph_context,
            traversal_paths=traversal_paths,
            original_question=question,
            answer_mode="internal_web_augmented",
            embedding_units_by_page_id=self._load_embedding_units_for_related_pages(related_pages),
        )
        answer, evidence_snippets = self._query_answer_assembler.generate_supported_answer(augmented_context)
        self._publish(
            event_publisher,
            "web_search_answer_generated",
            "내부 Wiki와 웹 검색 근거를 함께 사용해 답변 생성을 완료했습니다.",
            {"result_count": len(web_results), "answer_chars": len(answer.content)},
        )
        used_source_count = len([item for item in related_pages if item.page.is_source])
        used_concept_count = len([item for item in related_pages if item.page.is_concept])
        return QueryAnswer(
            answer=answer,
            related_pages=related_pages,
            evidence_snippets=evidence_snippets,
            graph_context=augmented_graph_context,
            traversal_paths=traversal_paths,
            retrieval_summary=RetrievalSummary(
                source_candidate_count=0,
                concept_candidate_count=0,
                visited_node_count=len(related_pages),
                returned_node_count=len(related_pages),
                used_source_count=used_source_count,
                used_concept_count=used_concept_count,
                max_depth=0,
                stop_reason=stop_reason,
            ),
        )

    def _merge_related_pages(self, internal_pages: list[RetrievedPage], web_pages: list[RetrievedPage]) -> list[RetrievedPage]:
        merged = list(internal_pages)
        seen = {item.page.id for item in merged}
        for item in web_pages:
            if item.page.id in seen:
                continue
            merged.append(item)
            seen.add(item.page.id)
        return merged

    def _web_results_to_related_pages(self, web_results: list[WebSearchResult]) -> list[RetrievedPage]:
        related_pages = []
        for index, result in enumerate(web_results, start=1):
            page = WikiPage(
                id=f"web:{self._hash(result.url)}",
                page_type="web",
                title=result.title,
                slug=self._slug(result.title) or f"web-result-{index}",
                summary=result.snippet,
                markdown_uri=result.url,
                markdown=result.content or result.snippet,
            )
            related_pages.append(
                RetrievedPage(
                    page=page,
                    score=max(0.0, min(1.0, result.score)),
                    role="web_search_result",
                    depth=0,
                )
            )
        return related_pages

    def _score_pages(self, query_rewrite: QueryRewrite, pages: list[WikiPage], embedding_weight: float) -> dict[str, float]:
        query = query_rewrite.retrieval_query
        representations = [self._representation(page) for page in pages]
        embedding_scores = self._embedding_search.score(query, representations)
        text_scores = self._text_search.score(query, representations)
        base_scores = {
            page.id: self._final_retrieval_score(
                hybrid_score(embedding_score, text_score, embedding_weight=embedding_weight),
                self._name_match_score(query_rewrite, page),
            )
            for page, embedding_score, text_score in zip(pages, embedding_scores, text_scores)
        }
        if pages and all(page.is_source for page in pages):
            structure_scores = self._score_source_structures(query, pages, embedding_weight)
            return {
                page.id: min(1.0, base_scores.get(page.id, 0.0) + structure_scores.get(page.id, 0.0))
                for page in pages
            }
        return base_scores

    def _score_source_structures(self, query: str, pages: list[WikiPage], embedding_weight: float) -> dict[str, float]:
        weighted_representations: list[tuple[str, str, float]] = []
        for page in pages:
            for representation, weight in self._source_structure_representations(page):
                weighted_representations.append((page.id, representation, weight))
        if not weighted_representations:
            return {}

        documents = [item[1] for item in weighted_representations]
        embedding_scores = self._embedding_search.score(query, documents)
        text_scores = self._text_search.score(query, documents)
        scores: dict[str, float] = {}
        for (page_id, _, weight), embedding_score, text_score in zip(weighted_representations, embedding_scores, text_scores):
            score = hybrid_score(embedding_score, text_score, embedding_weight=embedding_weight) * weight
            scores[page_id] = max(scores.get(page_id, 0.0), score)
        return scores

    def _source_structure_representations(self, page: WikiPage) -> list[tuple[str, float]]:
        markdown = page.markdown or ""
        sections = self._markdown_sections(markdown)
        weighted_sections = [
            ("Categories", 0.10),
            ("Core Concepts", 0.20),
            ("Section Candidates", 0.25),
            ("Mentions", 0.15),
        ]
        representations = []
        for section_name, weight in weighted_sections:
            body = sections.get(section_name.lower())
            if body:
                representations.append((f"{section_name}\n{body}", weight))
        return representations

    def _markdown_sections(self, markdown: str) -> dict[str, str]:
        sections: dict[str, list[str]] = {}
        current: str | None = None
        for line in markdown.splitlines():
            match = re.match(r"^##\s+(.+?)\s*$", line.strip())
            if match:
                current = match.group(1).strip().lower()
                sections.setdefault(current, [])
                continue
            if current is not None:
                sections[current].append(line)
        return {
            section: "\n".join(lines).strip()
            for section, lines in sections.items()
            if "\n".join(lines).strip()
        }

    def _final_retrieval_score(self, retrieval_score: float, name_match_score: float) -> float:
        if name_match_score >= 1.0:
            return max(retrieval_score, 0.95)
        if name_match_score >= 0.85:
            return max(retrieval_score, 0.88)
        if name_match_score > 0:
            return min(1.0, retrieval_score + 0.20 * name_match_score)
        return retrieval_score

    def _top_id(self, scores: dict[str, float]) -> str | None:
        if not scores:
            return None
        return max(scores, key=scores.get)

    def _top_scores(self, scores: dict[str, float], limit: int = 5) -> list[dict[str, object]]:
        return [
            {"id": page_id, "score": round(score, 4)}
            for page_id, score in sorted(scores.items(), key=lambda item: item[1], reverse=True)[:limit]
        ]

    def _name_match_score(self, query_rewrite: QueryRewrite, page: WikiPage) -> float:
        query_names = self._query_name_variants(query_rewrite)
        page_names = self._page_name_variants(page)
        if not query_names or not page_names:
            return 0.0

        for query_name in query_names:
            if query_name in page_names:
                return 1.0

        if page.is_source:
            return 0.0

        for query_name in query_names:
            for page_name in page_names:
                if len(query_name) >= 2 and len(page_name) >= 2 and (query_name in page_name or page_name in query_name):
                    return 0.85
        return 0.0

    def _query_name_variants(self, query_rewrite: QueryRewrite) -> set[str]:
        values = [query_rewrite.original_question, query_rewrite.retrieval_query, *query_rewrite.keywords]
        variants = set()
        for value in values:
            for token in re.findall(r"[A-Za-z0-9가-힣_.-]+", value.lower()):
                normalized = self._normalize_name(token)
                if normalized and normalized not in self._question_stopwords():
                    variants.add(normalized)
        return variants

    def _page_name_variants(self, page: WikiPage) -> set[str]:
        values = [page.title, page.slug, *self._extract_aliases(page.markdown or "")]
        return {normalized for value in values if (normalized := self._normalize_name(value))}

    def _extract_aliases(self, markdown: str) -> list[str]:
        aliases = []
        in_alias_block = False
        for line in markdown.splitlines():
            stripped = line.strip()
            if re.match(r"^aliases\s*:\s*$", stripped, flags=re.IGNORECASE):
                in_alias_block = True
                continue
            if in_alias_block:
                if stripped.startswith("-"):
                    aliases.append(stripped.lstrip("-").strip().strip('"').strip("'"))
                    continue
                in_alias_block = False
            match = re.match(r"^(aliases|alias|별칭|다른 이름)\s*[:：]\s*(.+)$", stripped, flags=re.IGNORECASE)
            if match:
                aliases.extend(part.strip().strip('"').strip("'") for part in re.split(r"[,/、，]", match.group(2)) if part.strip())
        return aliases

    def _normalize_name(self, value: str) -> str:
        normalized = value.lower().strip()
        normalized = re.sub(r"\([^)]*\)", "", normalized)
        normalized = re.sub(r"[^a-z0-9가-힣]+", "", normalized)
        if re.search(r"[가-힣]", normalized):
            for suffix in ["으로부터", "로부터", "에게서", "한테서", "에게", "한테", "으로", "로", "이랑", "랑", "이나", "나", "은", "는", "이", "가", "을", "를", "에", "의", "도", "만", "와", "과"]:
                if normalized.endswith(suffix) and len(normalized) > len(suffix) + 1:
                    return normalized[: -len(suffix)]
        return normalized

    def _question_stopwords(self) -> set[str]:
        return {
            "뭐",
            "뭐야",
            "무엇",
            "어떻게",
            "왜",
            "차이",
            "설명",
            "알려줘",
            "what",
            "is",
            "are",
            "the",
            "a",
            "an",
        }

    def _representation(self, page: WikiPage) -> str:
        markdown = page.markdown or ""
        return "\n".join([page.title, page.summary, markdown]).strip()

    def _select_seed_sources(self, source_pages: list[WikiPage], source_scores: dict[str, float]) -> list[str]:
        ranked = sorted(source_pages, key=lambda page: source_scores.get(page.id, 0.0), reverse=True)
        if not ranked:
            return []
        top_score = source_scores.get(ranked[0].id, 0.0)
        return [
            page.id
            for page in ranked
            if source_scores.get(page.id, 0.0) >= top_score - 0.02
        ][: self._source_candidate_limit]

    def _select_focus_concepts(self, concept_pages: list[WikiPage], concept_scores: dict[str, float]) -> list[str]:
        ranked = sorted(concept_pages, key=lambda page: concept_scores.get(page.id, 0.0), reverse=True)
        if not ranked:
            return []
        top_score = concept_scores.get(ranked[0].id, 0.0)
        if top_score < self._focus_concept_threshold:
            return []
        focus = [page.id for page in ranked if concept_scores.get(page.id, 0.0) >= top_score - 0.001]
        return focus[: self._concept_candidate_limit]

    def _select_direct_match_concepts(self, query_rewrite: QueryRewrite, concept_pages: list[WikiPage]) -> list[str]:
        direct_matches = [
            page.id
            for page in concept_pages
            if self._name_match_score(query_rewrite, page) >= 1.0
        ]
        return direct_matches[: self._concept_candidate_limit]

    def _add_focus_concepts_to_related_pages(
        self,
        related_pages: list[RetrievedPage],
        focus_concept_ids: list[str],
        pages_by_id: dict[str, WikiPage],
        concept_scores: dict[str, float],
    ) -> list[RetrievedPage]:
        merged = list(related_pages)
        seen = {item.page.id for item in merged}
        for concept_id in focus_concept_ids:
            if concept_id in seen or concept_id not in pages_by_id:
                continue
            merged.append(
                RetrievedPage(
                    page=pages_by_id[concept_id],
                    score=concept_scores.get(concept_id, 0.0),
                    role="focus_concept",
                    depth=0,
                )
            )
            seen.add(concept_id)
        return sorted(merged, key=lambda item: item.score, reverse=True)

    def _backfill_direct_concept_paths(
        self,
        graph_context: GraphContext,
        traversal_paths: list[TraversalPath],
        links: list[WikiPageLink],
        direct_concept_ids: list[str],
        source_scores: dict[str, float],
        concept_scores: dict[str, float],
    ) -> tuple[GraphContext, list[TraversalPath]]:
        if not direct_concept_ids:
            return graph_context, traversal_paths

        related_ids = {item.page.id for item in graph_context.nodes}
        existing_edge_keys = {(edge.from_page_id, edge.to_page_id, edge.link_type) for edge in graph_context.edges}
        existing_path_pairs = {
            (path.nodes[0], path.nodes[-1])
            for path in traversal_paths
            if len(path.nodes) >= 2
        }
        edges = list(graph_context.edges)
        paths = list(traversal_paths)

        for link in links:
            if link.link_type != "source_mentions_concept":
                continue
            if link.to_page_id not in direct_concept_ids:
                continue
            if link.from_page_id not in related_ids or link.to_page_id not in related_ids:
                continue

            edge_key = (link.from_page_id, link.to_page_id, link.link_type)
            score = float(link.confidence or 1.0)
            traversal_edge = TraversalEdge(
                from_page_id=link.from_page_id,
                to_page_id=link.to_page_id,
                link_type=link.link_type,
                role="seed_to_focus",
                score=score,
            )
            if edge_key not in existing_edge_keys:
                edges.append(traversal_edge)
                existing_edge_keys.add(edge_key)

            path_pair = (link.from_page_id, link.to_page_id)
            if path_pair in existing_path_pairs:
                continue
            path_score = max(source_scores.get(link.from_page_id, 0.0), concept_scores.get(link.to_page_id, 0.0))
            paths.append(
                TraversalPath(
                    path_id=f"direct_concept_path_{len(paths) + 1}",
                    role="primary_answer_path" if not paths else "candidate_path",
                    nodes=[link.from_page_id, link.to_page_id],
                    edges=[traversal_edge],
                    score=path_score,
                    used_for_answer=True,
                    stop_reason="concept_direct_match",
                )
            )
            existing_path_pairs.add(path_pair)

        return GraphContext(nodes=graph_context.nodes, edges=edges), paths

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
                f"가장 가까운 근거도 질문 주제를 직접 설명하지 않습니다. [{nearest.rank}]"
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

    def _load_embedding_units_for_related_pages(self, related_pages: list[RetrievedPage]) -> dict[str, list]:
        loader = getattr(self._wiki_repository, "list_embedding_units_by_page_ids", None)
        if loader is None:
            return {}
        page_ids = [item.page.id for item in related_pages if item.page.page_type != "web"]
        if not page_ids:
            return {}
        try:
            return loader(page_ids)
        except Exception:
            return {}

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

    def _hash(self, text: str) -> str:
        return hashlib.sha1(text.encode("utf-8")).hexdigest()[:12]

    def _slug(self, text: str) -> str:
        slug = re.sub(r"[^A-Za-z0-9가-힣_.-]+", "-", text.lower()).strip("-._")
        return slug[:80]

    def _publish(
        self,
        event_publisher: QueryEventPublisherPort | None,
        stage: str,
        message: str,
        data: dict[str, object] | None = None,
    ) -> None:
        if event_publisher is None:
            return
        try:
            event_publisher.publish(stage, message, data)
        except Exception:
            return
