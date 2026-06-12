import unittest

from app.modules.query.application.answer_query import AnswerQueryUseCase
from app.modules.query.application.traverse_wiki_graph import TraverseWikiGraphUseCase
from app.modules.query.domain.entities import GeneratedAnswer, QueryContext, WikiPage, WikiPageLink
from app.modules.query.domain.exceptions import InvalidQuestionError
from app.modules.query.infrastructure.in_memory_wiki_repository import InMemoryWikiRepository


class ScoreSearch:
    def __init__(self, scores_by_title: dict[str, float]) -> None:
        self._scores_by_title = scores_by_title

    def score(self, query: str, documents: list[str]) -> list[float]:
        return [self._scores_by_title.get(document.splitlines()[0], 0.0) for document in documents]


class EmptyTextSearch:
    def score(self, query: str, documents: list[str]) -> list[float]:
        return [0.0 for _ in documents]


class RecordingAnswerGenerator:
    def __init__(self) -> None:
        self.last_context: QueryContext | None = None

    def generate_answer(self, context: QueryContext) -> GeneratedAnswer:
        self.last_context = context
        return GeneratedAnswer(content="테스트 답변")


def source_page(page_id: str, title: str) -> WikiPage:
    return WikiPage(
        id=page_id,
        page_type="source",
        title=title,
        slug=title.lower().replace(" ", "-"),
        summary=f"{title} 요약",
    )


def concept_page(page_id: str, title: str) -> WikiPage:
    return WikiPage(
        id=page_id,
        page_type="concept",
        title=title,
        slug=title.lower().replace(" ", "-"),
        summary=f"{title} 정의",
    )


class AnswerQueryUseCaseTest(unittest.TestCase):
    def test_adds_source_seed_from_strong_concept_hint(self) -> None:
        pages = [
            source_page("source:attention", "Lecture Attention"),
            source_page("source:unrelated", "Unrelated Source"),
            concept_page("concept:self-attention", "Self Attention"),
        ]
        links = [
            WikiPageLink(
                from_page_id="source:attention",
                to_page_id="concept:self-attention",
                link_type="source_mentions_concept",
                confidence=0.95,
            )
        ]
        answer_generator = RecordingAnswerGenerator()
        use_case = AnswerQueryUseCase(
            wiki_repository=InMemoryWikiRepository(pages, links),
            embedding_search=ScoreSearch(
                {
                    "Lecture Attention": 0.10,
                    "Unrelated Source": 0.90,
                    "Self Attention": 0.95,
                }
            ),
            text_search=EmptyTextSearch(),
            answer_generator=answer_generator,
            source_candidate_limit=1,
            traverse_wiki_graph=TraverseWikiGraphUseCase(min_node_score=0.10),
        )

        result = use_case.execute("토큰끼리 서로 보는 구조가 뭐야?")

        related_ids = {item.page.id for item in result.related_pages}
        self.assertIn("source:attention", related_ids)
        self.assertIn("source:unrelated", related_ids)
        self.assertIn("concept:self-attention", related_ids)
        self.assertTrue(
            any("source:attention" in path.nodes and "concept:self-attention" in path.nodes for path in result.traversal_paths)
        )
        self.assertIsNotNone(answer_generator.last_context)

    def test_traverses_source_related_to_edges(self) -> None:
        pages = [
            source_page("source:a", "RAG Overview"),
            source_page("source:b", "Wiki Graph Notes"),
            concept_page("concept:rag", "RAG"),
        ]
        links = [
            WikiPageLink(
                from_page_id="source:a",
                to_page_id="source:b",
                link_type="source_related_to",
                confidence=0.92,
            ),
            WikiPageLink(
                from_page_id="source:b",
                to_page_id="concept:rag",
                link_type="source_mentions_concept",
                confidence=0.91,
            ),
        ]
        use_case = AnswerQueryUseCase(
            wiki_repository=InMemoryWikiRepository(pages, links),
            embedding_search=ScoreSearch(
                {
                    "RAG Overview": 0.95,
                    "Wiki Graph Notes": 0.60,
                    "RAG": 0.20,
                }
            ),
            text_search=EmptyTextSearch(),
            answer_generator=RecordingAnswerGenerator(),
            source_candidate_limit=1,
            traverse_wiki_graph=TraverseWikiGraphUseCase(min_node_score=0.10),
        )

        result = use_case.execute("RAG랑 위키 그래프가 어떻게 이어져?")

        related_ids = {item.page.id for item in result.related_pages}
        edge_types = {edge.link_type for edge in result.graph_context.edges}
        self.assertIn("source:b", related_ids)
        self.assertIn("source_related_to", edge_types)

    def test_stops_at_configured_max_depth(self) -> None:
        pages = [
            source_page("source:seed", "Seed Source"),
            concept_page("concept:one", "Concept One"),
            concept_page("concept:two", "Concept Two"),
            concept_page("concept:three", "Concept Three"),
            concept_page("concept:four", "Concept Four"),
        ]
        links = [
            WikiPageLink("source:seed", "concept:one", "source_mentions_concept", confidence=0.99),
            WikiPageLink("concept:one", "concept:two", "concept_related_to", confidence=0.99),
            WikiPageLink("concept:two", "concept:three", "concept_related_to", confidence=0.99),
            WikiPageLink("concept:three", "concept:four", "concept_related_to", confidence=0.99),
        ]
        use_case = AnswerQueryUseCase(
            wiki_repository=InMemoryWikiRepository(pages, links),
            embedding_search=ScoreSearch(
                {
                    "Seed Source": 0.95,
                    "Concept One": 0.95,
                    "Concept Two": 0.95,
                    "Concept Three": 0.95,
                    "Concept Four": 0.95,
                }
            ),
            text_search=EmptyTextSearch(),
            answer_generator=RecordingAnswerGenerator(),
            source_candidate_limit=1,
            traverse_wiki_graph=TraverseWikiGraphUseCase(max_depth=2, min_node_score=0.10),
        )

        result = use_case.execute("깊이 제한 확인")

        related_ids = {item.page.id for item in result.related_pages}
        self.assertIn("concept:two", related_ids)
        self.assertNotIn("concept:three", related_ids)
        self.assertNotIn("concept:four", related_ids)

    def test_rejects_blank_question(self) -> None:
        use_case = AnswerQueryUseCase(
            wiki_repository=InMemoryWikiRepository([], []),
            embedding_search=ScoreSearch({}),
            text_search=EmptyTextSearch(),
            answer_generator=RecordingAnswerGenerator(),
        )

        with self.assertRaises(InvalidQuestionError):
            use_case.execute("   ")


if __name__ == "__main__":
    unittest.main()
