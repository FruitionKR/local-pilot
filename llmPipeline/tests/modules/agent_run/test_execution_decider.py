import json
import unittest

from app.modules.agent_run.domain.plan import AgentPlan, AgentPlanOperation
from app.modules.agent_run.infrastructure.chat_completions_execution_decider import (
    ChatCompletionsExecutionDecider,
    normalize_execution_decision,
)


class CapturingClient:
    def __init__(self, response: dict[str, object]) -> None:
        self.response = response
        self.payload: dict[str, object] = {}

    def complete_json(self, system_prompt: str, user_prompt: str) -> dict[str, object]:
        self.payload = json.loads(user_prompt)
        return self.response


class ExecutionDeciderTest(unittest.TestCase):
    def test_passes_only_ready_operations_and_allowed_read_tools(self) -> None:
        client = CapturingClient(
            {"action": "execute_operation", "operation_id": "plan-1-op-1", "reason": "실행합니다."}
        )
        decider = ChatCompletionsExecutionDecider(client, "system")  # type: ignore[arg-type]
        plan = _plan()

        decision = decider.decide(
            instruction="문서를 정리해줘",
            plan=plan,
            ready_operations=(plan.operations[0],),
            observations=({"action": "read", "result": {"items": []}},),
            allowed_read_tools=("list_root_items",),
        )

        self.assertEqual(decision.operation_id, "plan-1-op-1")
        self.assertEqual(client.payload["allowed_read_tools"], ["list_root_items"])
        ready = client.payload["ready_operations"]
        self.assertIsInstance(ready, list)
        self.assertEqual(ready[0]["id"], "plan-1-op-1")  # type: ignore[index]

    def test_discards_model_mutation_tool_and_arguments(self) -> None:
        decision = normalize_execution_decision(
            {
                "action": "execute_operation",
                "operation_id": "plan-1-op-1",
                "tool_name": "move_document",
                "arguments": {"document_id": "unapproved-document"},
            }
        )

        self.assertIsNone(decision.tool_name)
        self.assertIsNone(decision.arguments)

    def test_read_decision_requires_arguments_object(self) -> None:
        with self.assertRaisesRegex(ValueError, "requires a tool"):
            normalize_execution_decision(
                {"action": "read", "tool_name": "list_folder_children", "arguments": None}
            )


def _plan() -> AgentPlan:
    return AgentPlan(
        id="plan-1",
        run_id="run-1",
        version=1,
        summary="문서를 이동합니다.",
        operation_hash="approved-hash",
        status="approved",
        operations=(
            AgentPlanOperation(
                id="plan-1-op-1",
                sequence=1,
                tool_name="move_document",
                target_type="document",
                target_id="document-1",
                base_version=3,
                source_parent_id=None,
                destination_parent_id="folder-1",
                arguments={
                    "document_id": "document-1",
                    "folder_id": "folder-1",
                    "position": None,
                    "base_version": 3,
                },
                reason="관련 문서를 모읍니다.",
            ),
        ),
    )


if __name__ == "__main__":
    unittest.main()
