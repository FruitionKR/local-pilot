from __future__ import annotations

import json
import unittest

from app.modules.wiki_generation.application.judge_candidates import (
    judge_concept_update_candidates,
    judge_meaning_cluster_candidates,
)


class FakeCompletion:
    def __init__(self, response: dict[str, object]) -> None:
        self.response = response
        self.system_prompt = ""
        self.payload: dict[str, object] = {}

    def complete_json(self, system_prompt: str, user_prompt: str) -> dict[str, object]:
        self.system_prompt = system_prompt
        self.payload = json.loads(user_prompt)
        return self.response


def candidate() -> dict[str, object]:
    return {
        "candidate_id": "cand_001",
        "term": "Back EMF",
        "slug": "back-emf",
        "claim": "역기전력은 속도에 비례한다.",
        "refs": ["B0001"],
        "candidate_type": "section_candidate",
    }


class JudgeCandidatesTest(unittest.TestCase):
    def test_normalizes_meaning_cluster_decision(self) -> None:
        completion = FakeCompletion(
            {
                "decisions": [
                    {
                        "candidate_id": "cand_001",
                        "decision": "same_cluster",
                        "target_cluster_id": "back-emf",
                        "representative": "Back EMF",
                        "promotion_status": "candidate",
                        "reason": "기존 근거가 충분함",
                    },
                    {"candidate_id": "unknown", "decision": "new_cluster"},
                ]
            }
        )

        result = judge_meaning_cluster_candidates(
            completion=completion,
            existing_active_markdown="# Active",
            candidates=[candidate()],
        )

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["decision"], "same_cluster")
        self.assertEqual(result[0]["promotion_status"], "candidate")
        self.assertEqual(completion.payload["existing_active_clusters"], "# Active")
        self.assertIn("MeaningClusterJudge", completion.system_prompt)

    def test_rejects_relation_to_unknown_concept(self) -> None:
        completion = FakeCompletion(
            {
                "decisions": [
                    {
                        "candidate_id": "cand_001",
                        "decision": "relation_candidate",
                        "concept_slug": "missing-concept",
                        "relation": "part_of",
                    }
                ]
            }
        )

        result = judge_concept_update_candidates(
            completion=completion,
            concepts=[{"slug": "motor", "title": "Motor"}],
            candidates=[candidate()],
        )

        self.assertEqual(
            result[0],
            {
                "candidate_id": "cand_001",
                "decision": "not_same_concept",
                "concept_slug": "",
                "relation": "",
                "reason": None,
            },
        )

    def test_skips_completion_when_inputs_are_empty(self) -> None:
        completion = FakeCompletion({"decisions": []})

        result = judge_concept_update_candidates(
            completion=completion,
            concepts=[],
            candidates=[candidate()],
        )

        self.assertEqual(result, [])
        self.assertEqual(completion.system_prompt, "")
