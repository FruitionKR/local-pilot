from app.modules.query.application.build_query_context import BuildQueryContextUseCase
from app.modules.query.application.ports import AnswerGeneratorPort, EmbeddingSearchPort, TextSearchPort, WikiRepositoryPort
from app.modules.query.application.traverse_wiki_graph import TraverseWikiGraphUseCase
from app.modules.query.domain.entities import QueryAnswer, RetrievalSummary, WikiPage
from app.modules.query.domain.scoring import hybrid_score
from app.modules.query.domain.value_objects import Question


class AnswerQueryUseCase:
    def __init__(
        self,
        wiki_repository: WikiRepositoryPort,
        embedding_search: EmbeddingSearchPort,
        text_search: TextSearchPort,
        answer_generator: AnswerGeneratorPort,
        traverse_wiki_graph: TraverseWikiGraphUseCase | None = None,
        build_query_context: BuildQueryContextUseCase | None = None,
        source_candidate_limit: int = 15,
        concept_candidate_limit: int = 10,
        focus_concept_threshold: float = 0.60,
    ) -> None:
        self._wiki_repository = wiki_repository
        self._embedding_search = embedding_search
        self._text_search = text_search
        self._answer_generator = answer_generator
        self._traverse_wiki_graph = traverse_wiki_graph or TraverseWikiGraphUseCase()
        self._build_query_context = build_query_context or BuildQueryContextUseCase()
        self._source_candidate_limit = source_candidate_limit
        self._concept_candidate_limit = concept_candidate_limit
        self._focus_concept_threshold = focus_concept_threshold

    def execute(self, question: str) -> QueryAnswer:
        query = Question(question)
        pages = self._wiki_repository.list_active_pages()
        links = self._wiki_repository.list_active_links()
        pages_by_id = {page.id: page for page in pages}
        source_pages = [page for page in pages if page.is_source]
        concept_pages = [page for page in pages if page.is_concept]

        source_scores = self._score_pages(query.normalized, source_pages, embedding_weight=0.8)
        concept_scores = self._score_pages(query.normalized, concept_pages, embedding_weight=0.8)
        node_scores = {**source_scores, **concept_scores}

        seed_source_ids = self._select_seed_sources(source_pages, source_scores)
        focus_concept_ids = self._select_focus_concepts(concept_pages, concept_scores)
        seed_source_ids = self._add_sources_connected_to_focus_concepts(seed_source_ids, focus_concept_ids, links)

        graph_context, traversal_paths, stop_reason = self._traverse_wiki_graph.execute(
            pages_by_id=pages_by_id,
            links=links,
            seed_source_ids=seed_source_ids,
            node_scores=node_scores,
        )
        related_pages = graph_context.nodes
        query_context = self._build_query_context.execute(
            question=query.normalized,
            related_pages=related_pages,
            graph_context=graph_context,
            traversal_paths=traversal_paths,
        )
        answer = self._answer_generator.generate_answer(query_context)

        used_source_count = len([item for item in related_pages if item.page.is_source])
        used_concept_count = len([item for item in related_pages if item.page.is_concept])
        summary = RetrievalSummary(
            source_candidate_count=min(len(source_pages), self._source_candidate_limit),
            concept_candidate_count=min(len(concept_pages), self._concept_candidate_limit),
            visited_node_count=len(related_pages),
            returned_node_count=len(related_pages),
            used_source_count=used_source_count,
            used_concept_count=used_concept_count,
            max_depth=3,
            stop_reason=stop_reason,
        )
        return QueryAnswer(
            answer=answer,
            related_pages=related_pages,
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

    def _representation(self, page: WikiPage) -> str:
        markdown = page.markdown or ""
        return "\n".join([page.title, page.summary, markdown])

    def _select_seed_sources(self, source_pages: list[WikiPage], source_scores: dict[str, float]) -> list[str]:
        ranked = sorted(source_pages, key=lambda page: source_scores.get(page.id, 0.0), reverse=True)
        return [page.id for page in ranked[: self._source_candidate_limit]]

    def _select_focus_concepts(self, concept_pages: list[WikiPage], concept_scores: dict[str, float]) -> list[str]:
        ranked = sorted(concept_pages, key=lambda page: concept_scores.get(page.id, 0.0), reverse=True)
        focus = [page.id for page in ranked if concept_scores.get(page.id, 0.0) >= self._focus_concept_threshold]
        return focus[: self._concept_candidate_limit]

    def _add_sources_connected_to_focus_concepts(
        self,
        seed_source_ids: list[str],
        focus_concept_ids: list[str],
        links: list,
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

