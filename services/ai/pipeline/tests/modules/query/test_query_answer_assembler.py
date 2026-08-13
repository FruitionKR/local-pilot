import re
import unittest

from app.modules.query.application.query_answer_assembler import QueryAnswerAssembler
from app.modules.query.domain.entities import EvidenceSnippet, GeneratedAnswer, GraphContext, QueryContext


class FixedAnswerGenerator:
    def __init__(self, content: str) -> None:
        self._content = content

    def generate_answer(self, context: QueryContext) -> GeneratedAnswer:
        return GeneratedAnswer(content=self._content)


def query_context(evidence_snippets: list[EvidenceSnippet]) -> QueryContext:
    return QueryContext(
        question="질문",
        graph_context=GraphContext(),
        traversal_paths=[],
        related_pages=[],
        evidence_snippets=evidence_snippets,
        answer_context="질문",
    )


class QueryAnswerAssemblerTest(unittest.TestCase):
    def test_empty_evidence_cannot_yield_citations(self) -> None:
        assembler = QueryAnswerAssembler(FixedAnswerGenerator("답변입니다. [1]"))

        answer, evidence_snippets = assembler.generate_supported_answer(query_context([]))

        self.assertNotRegex(answer.content, r"\[\d+(?:\s*,\s*\d+)*\]")
        self.assertEqual(evidence_snippets, [])

    def test_returned_evidence_ranks_match_citations(self) -> None:
        evidence_snippets = [
            EvidenceSnippet(rank=4, source_document_id="doc-a", source_block_ids=["B0004"], text="첫 근거"),
            EvidenceSnippet(rank=7, source_document_id="doc-b", source_block_ids=["B0007"], text="둘째 근거"),
        ]
        assembler = QueryAnswerAssembler(
            FixedAnswerGenerator("첫 답변 [7], 보조 답변 [4, 7], 잘못된 인용 [99]")
        )

        answer, returned = assembler.generate_supported_answer(query_context(evidence_snippets))

        citation_ranks = {
            int(value)
            for marker in re.findall(r"\[((?:\d+)(?:\s*,\s*\d+)*)\]", answer.content)
            for value in marker.split(",")
        }
        self.assertEqual(citation_ranks, {1, 2})
        self.assertEqual([snippet.rank for snippet in returned], [1, 2])
        self.assertNotIn("[99]", answer.content)
        self.assertEqual([snippet.source_document_id for snippet in returned], ["doc-b", "doc-a"])


if __name__ == "__main__":
    unittest.main()
