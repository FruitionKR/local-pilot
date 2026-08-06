import unittest

from app.modules.query.application.evidence_selector import EvidenceSelector
from app.modules.query.domain.entities import RetrievedPage, WikiPage


class EmptySearch:
    def score(self, query: str, documents: list[str]) -> list[float]:
        return [0.0 for _ in documents]


class QueryContainsSearch:
    def score(self, query: str, documents: list[str]) -> list[float]:
        query_terms = [term.lower() for term in query.split() if term.strip()]
        scores = []
        for document in documents:
            lowered = document.lower()
            scores.append(1.0 if any(term in lowered for term in query_terms) else 0.0)
        return scores


class EvidenceSelectorTest(unittest.TestCase):
    def test_selects_only_text_with_source_block_refs(self) -> None:
        page = WikiPage(
            id="source:seed",
            page_type="source",
            title="Seed Source",
            slug="seed-source",
            summary="Seed summary",
            markdown=(
                "---\ndocument_id: doc_seed\n---\n\n"
                "블록 참조가 없는 설명입니다.\n\n"
                "원본에 연결된 근거 문장입니다. [B0005]"
            ),
        )
        selector = EvidenceSelector(
            embedding_search=QueryContainsSearch(),
            text_search=EmptySearch(),
        )

        snippets = selector.select(
            question="근거 문장",
            related_pages=[
                RetrievedPage(
                    page=page,
                    score=0.9,
                    role="seed_source",
                )
            ],
            embedding_units_by_page_id={},
        )

        self.assertEqual(len(snippets), 1)
        self.assertEqual(snippets[0].source_document_id, "doc_seed")
        self.assertEqual(snippets[0].source_block_ids, ["B0005"])
        self.assertEqual(snippets[0].source_refs[0].source_document_id, "doc_seed")
        self.assertEqual(snippets[0].source_refs[0].source_block_id, "B0005")
        self.assertEqual(snippets[0].text, "원본에 연결된 근거 문장입니다.")

    def test_maps_global_source_refs_to_structured_refs(self) -> None:
        page = WikiPage(
            id="concept:shared",
            page_type="concept",
            title="Shared Concept",
            slug="shared-concept",
            summary="Shared summary",
            markdown=(
                "---\nsources: doc_a, doc_b\n---\n\n"
                "- 여러 원문을 함께 참조하는 근거입니다. [doc_a:B0001, doc_b:B0008]"
            ),
        )
        selector = EvidenceSelector(
            embedding_search=QueryContainsSearch(),
            text_search=EmptySearch(),
        )

        snippets = selector.select(
            question="여러 원문 근거",
            related_pages=[
                RetrievedPage(
                    page=page,
                    score=0.9,
                    role="focus_concept",
                )
            ],
            embedding_units_by_page_id={},
        )

        self.assertEqual(len(snippets), 1)
        self.assertEqual(snippets[0].source_document_id, "doc_a")
        self.assertEqual(snippets[0].source_block_ids, ["B0001"])
        self.assertEqual(
            [(ref.source_document_id, ref.source_block_id) for ref in snippets[0].source_refs],
            [("doc_a", "B0001"), ("doc_b", "B0008")],
        )
        self.assertEqual(snippets[0].text, "여러 원문을 함께 참조하는 근거입니다.")


if __name__ == "__main__":
    unittest.main()
