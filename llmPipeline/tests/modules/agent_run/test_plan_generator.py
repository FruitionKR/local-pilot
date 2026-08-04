import json
import unittest

from app.modules.agent_run.infrastructure.chat_completions_plan_generator import (
    ChatCompletionsPlanGenerator,
    normalize_plan_candidate,
)
from app.modules.agent_run.domain.entities import ContentArtifactReference


class CapturingClient:
    def __init__(self) -> None:
        self.payload: dict[str, object] = {}

    def complete_json(self, system_prompt: str, user_prompt: str) -> dict[str, object]:
        self.payload = json.loads(user_prompt)
        return {
            "summary": "문서를 이동합니다.",
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
                    "reason": "관련 문서를 모읍니다.",
                    "depends_on": [],
                }
            ],
        }


class PlanGeneratorTest(unittest.TestCase):
    def test_passes_only_selected_skill_mutation_tools_to_model(self) -> None:
        client = CapturingClient()
        generator = ChatCompletionsPlanGenerator(client, "system")  # type: ignore[arg-type]

        generator.generate(
            run_id="run-1",
            plan_id="plan-1",
            version=1,
            instruction="문서를 이동해줘",
            hierarchy=[
                {"id": "folder-1", "type": "folder", "current_version": 1},
                {"id": "document-1", "type": "document", "current_version": 3},
            ],
            skill_instructions="문서를 기존 폴더로 이동한다.",
            allowed_tools=("list_root_items", "move_document"),
        )

        self.assertEqual(client.payload["allowed_tools"], ["move_document"])

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

    def test_accepts_document_edit_artifact_contract(self) -> None:
        client = CapturingClient()

        def complete_json(system_prompt: str, user_prompt: str) -> dict[str, object]:
            client.payload = json.loads(user_prompt)
            return {
                "summary": "문서 본문을 반영합니다.",
                "operations": [
                    {
                        "tool_name": "apply_document_edit",
                        "target_type": "document",
                        "target_id": "document-1",
                        "base_version": 3,
                        "source_parent_id": "folder-1",
                        "destination_parent_id": "folder-1",
                        "arguments": {
                            "document_id": "document-1",
                            "base_version": 3,
                            "target": {
                                "type": "whole_document",
                                "start_line": 1,
                                "end_line": 10,
                            },
                            "content_artifact_id": "artifact-1",
                            "content_hash": "sha256:abc",
                        },
                        "reason": "승인된 편집안을 반영합니다.",
                        "depends_on": [],
                    }
                ],
            }

        client.complete_json = complete_json  # type: ignore[method-assign]
        generator = ChatCompletionsPlanGenerator(client, "system")  # type: ignore[arg-type]

        plan = generator.generate(
            run_id="run-1",
            plan_id="plan-1",
            version=1,
            instruction="현재 문서를 다듬어 저장해줘",
            hierarchy=[
                {
                    "id": "document-1",
                    "type": "document",
                    "current_version": 3,
                    "parent_id": "folder-1",
                }
            ],
            skill_instructions=None,
            allowed_tools=("apply_document_edit",),
            content_artifacts=(
                ContentArtifactReference(
                    id="artifact-1",
                    content_hash="sha256:abc",
                    purpose="apply_document_edit",
                    document_id="document-1",
                    base_version=3,
                    target={
                        "type": "whole_document",
                        "start_line": 1,
                        "end_line": 10,
                    },
                ),
            ),
        )

        self.assertEqual(plan.operations[0].tool_name, "apply_document_edit")
        self.assertEqual(client.payload["allowed_tools"], ["apply_document_edit"])
        self.assertEqual(client.payload["content_artifacts"][0]["id"], "artifact-1")  # type: ignore[index]

        with self.assertRaisesRegex(ValueError, "trusted context"):
            generator.generate(
                run_id="run-2",
                plan_id="plan-2",
                version=1,
                instruction="현재 문서를 다듬어 저장해줘",
                hierarchy=[
                    {
                        "id": "document-1",
                        "type": "document",
                        "current_version": 3,
                        "parent_id": "folder-1",
                    }
                ],
                skill_instructions=None,
                allowed_tools=("apply_document_edit",),
            )


if __name__ == "__main__":
    unittest.main()
