import unittest

from app.modules.wiki_generation.application.evaluation_guards import (
    apply_generation_evaluation_guards,
    repair_normalized_from_evaluation,
)


class EvaluationGuardsTest(unittest.TestCase):
    def test_marks_medium_generation_issues_as_retryable(self) -> None:
        evaluation = {
            "passed": True,
            "retry_recommended": False,
            "scores": {"overall": 0.95},
            "issues": [],
            "retry_feedback": "",
        }
        normalized = {
            "concept_ledger": [
                {"slug": "citation-marker"},
                {"slug": "retrieval-rank"},
                {"slug": "source-block"},
            ],
            "observations": [],
        }

        apply_generation_evaluation_guards(evaluation, normalized)

        self.assertFalse(evaluation["passed"])
        self.assertTrue(evaluation["retry_recommended"])
        self.assertEqual(evaluation["scores"]["overall"], 0.74)
        self.assertEqual(evaluation["issues"][0]["type"], "over_fragmented_concept")
        self.assertIn("citation marker", evaluation["retry_feedback"])

    def test_repairs_broken_and_duplicate_observations(self) -> None:
        normalized = {
            "observations": [
                {
                    "observation_id": "O001",
                    "type": "qa_episode",
                    "title": "짧은 제목",
                    "query_text": "Back EMF 영향?",
                    "summary": "Back EMF는 제조 공차의 영향을 받는다.",
                    "claims": ["Back EMF는 공차 영향을 받는다."],
                    "anchor_reference_ids": ["B0001"],
                    "related_concept_hints": ["back-emf"],
                },
                {
                    "observation_id": "O002",
                    "type": "qa_episode",
                    "title": "더 긴 제목",
                    "query_text": "Back EMF 영향?",
                    "summary": "Back EMF는 제조 공차와 온도 조건의 영향을 받는다.",
                    "claims": ["Back EMF는 온도 조건 영향을 받는다."],
                    "anchor_reference_ids": ["B0002"],
                    "related_concept_hints": ["temperature"],
                },
                {
                    "observation_id": "O003",
                    "type": "qa_episode",
                    "title": "깨진 observation",
                    "query_text": "",
                    "summary": "-",
                    "claims": [],
                    "anchor_reference_ids": [],
                    "related_concept_hints": [],
                },
            ],
            "semantic_notes": [
                {
                    "observations": [
                        {
                            "type": "qa_episode",
                            "title": "짧은 제목",
                            "query_text": "Back EMF 영향?",
                            "summary": "Back EMF는 제조 공차의 영향을 받는다.",
                        },
                        {
                            "type": "qa_episode",
                            "title": "깨진 observation",
                            "query_text": "",
                            "summary": "-",
                        },
                    ]
                }
            ],
        }
        evaluation = {
            "issues": [
                {"type": "duplicate_observation", "target": ["O001", "O002"]},
                {"type": "broken_observation", "target": ["O003"]},
            ]
        }

        repaired, operations = repair_normalized_from_evaluation(normalized, evaluation)

        self.assertEqual([item["observation_id"] for item in repaired["observations"]], ["O001"])
        self.assertEqual(repaired["observations"][0]["anchor_reference_ids"], ["B0002", "B0001"])
        self.assertEqual(
            repaired["observations"][0]["claims"],
            ["Back EMF는 온도 조건 영향을 받는다.", "Back EMF는 공차 영향을 받는다."],
        )
        self.assertEqual(repaired["observations"][0]["related_concept_hints"], ["temperature", "back-emf"])
        self.assertEqual(len(repaired["semantic_notes"][0]["observations"]), 0)
        self.assertEqual(len(operations), 2)


if __name__ == "__main__":
    unittest.main()
