import unittest
from unittest.mock import patch

from app.modules.query.infrastructure.query_evaluator_studio_graph import graph


class QueryEvaluatorStudioGraphTest(unittest.TestCase):
    def test_studio_graph_invokes_without_configured_evaluator(self) -> None:
        with patch(
            "app.modules.query.infrastructure.query_evaluator_studio_graph.build_query_answer_evaluator",
            return_value=None,
        ) as build_evaluator:
            result = graph.invoke(
                {
                    "question": "LangGraph Studio에서 evaluator graph를 볼 수 있나요?",
                    "answer": "Studio용 query_evaluator graph로 확인할 수 있습니다. [1]",
                    "max_attempts": 1,
                    "evidence_snippets": [
                        {
                            "rank": 1,
                            "source_document_id": "doc",
                            "source_block_ids": ["B0001"],
                            "text": "query_evaluator graph는 langgraph.json에 등록되어 Studio에서 확인할 수 있다.",
                        }
                    ],
                }
            )

        build_evaluator.assert_called_once_with(provider="openai", model="gpt-5-nano")
        self.assertEqual(result["attempt"], 1)
        self.assertEqual(result["evaluation"]["route"], "internal_supported")
        self.assertIn("reason", result["evaluation"])


if __name__ == "__main__":
    unittest.main()
