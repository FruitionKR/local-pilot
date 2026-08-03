import unittest

from app.modules.agent_run.infrastructure.chat_completions_plan_generator import normalize_plan_candidate


class PlanGeneratorTest(unittest.TestCase):
    def test_normalizes_plan_with_stable_operation_ids(self) -> None:
        plan = normalize_plan_candidate(
            run_id="run-1",
            plan_id="plan-1",
            version=1,
            value={
                "summary": "분기 문서를 이동합니다.",
                "operations": [
                    {
                        "tool_name": "move_document",
                        "target_type": "document",
                        "target_id": "document-1",
                        "base_version": 3,
                        "source_parent_id": None,
                        "destination_parent_id": "folder-1",
                        "arguments": {
                            "document_id": "document-1",
                            "folder_id": "folder-1",
                            "position": None,
                            "base_version": 3,
                        },
                        "reason": "분기 문서를 모읍니다.",
                        "depends_on": [],
                    }
                ],
            },
        )

        self.assertEqual(plan.operations[0].id, "plan-1-op-1")
        self.assertEqual(plan.operations[0].sequence, 1)

    def test_rejects_more_than_twenty_operations(self) -> None:
        candidate = {
            "summary": "too many",
            "operations": [
                {
                    "tool_name": "create_folder",
                    "target_type": "folder",
                    "target_id": None,
                    "base_version": None,
                    "source_parent_id": None,
                    "destination_parent_id": None,
                    "arguments": {"name": str(index), "parent_folder_id": None},
                    "reason": "create",
                    "depends_on": [],
                }
                for index in range(21)
            ],
        }

        with self.assertRaisesRegex(ValueError, "20"):
            normalize_plan_candidate("run-1", "plan-1", 1, candidate)


if __name__ == "__main__":
    unittest.main()
