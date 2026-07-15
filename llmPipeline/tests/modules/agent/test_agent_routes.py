import unittest

from fastapi import HTTPException

from app.modules.agent.domain.entities import AgentTurnResult, AgentTurnRoute
from app.modules.agent.interfaces.http.routes import handle_agent_turn
from app.modules.agent.interfaces.http.schemas import AgentTurnRequestBody
from app.modules.markdown_edit.domain.entities import GeneratedMarkdownDocument
from app.modules.markdown_edit.domain.markdown_output_contract import MarkdownOutputContractError
from app.modules.markdown_edit.domain.markdown_target_scope import MarkdownTargetBoundaryError


class FixedAgentUseCase:
    def execute(self, request: object) -> AgentTurnResult:
        return AgentTurnResult(
            action="markdown_create",
            route=AgentTurnRoute(
                action="markdown_create",
                confidence=0.92,
                reason="create markdown from chat",
                edit_goal="create_from_chat",
            ),
            generated_markdown=GeneratedMarkdownDocument(
                title="Agent 설계 메모",
                summary="대화 내용을 Markdown 문서로 정리했습니다.",
                markdown="# Agent 설계 메모\n\n- 편집과 생성을 분리한다.",
            ),
        )


class FailingMarkdownEditUseCase:
    def execute(self, request: object) -> AgentTurnResult:
        raise MarkdownOutputContractError(
            ["protected token count mismatch: secret-internal-detail"],
            "invalid output",
        )


class InvalidMarkdownTargetUseCase:
    def execute(self, request: object) -> AgentTurnResult:
        raise MarkdownTargetBoundaryError("fence", 2, 4)


class AgentRoutesTest(unittest.TestCase):
    def test_agent_turn_returns_generated_markdown(self) -> None:
        response = handle_agent_turn(
            AgentTurnRequestBody(
                message="지금까지 이야기한 내용 md로 만들어줘",
                conversation_context={
                    "recent_conversation_summary": "편집과 생성을 분리하기로 했다.",
                },
            ),
            use_case=FixedAgentUseCase(),  # type: ignore[arg-type]
        )

        body = response.model_dump()
        self.assertEqual(body["action"], "markdown_create")
        self.assertIsNone(body["edit"])
        self.assertEqual(body["route"]["edit_goal"], "create_from_chat")
        self.assertEqual(body["generated_markdown"]["title"], "Agent 설계 메모")
        self.assertIn("# Agent 설계 메모", body["generated_markdown"]["markdown"])

    def test_agent_turn_maps_markdown_contract_failure_without_internal_details(self) -> None:
        with self.assertRaises(HTTPException) as raised:
            handle_agent_turn(
                AgentTurnRequestBody(message="문서를 다듬어줘"),
                use_case=FailingMarkdownEditUseCase(),  # type: ignore[arg-type]
            )

        self.assertEqual(raised.exception.status_code, 422)
        self.assertEqual(raised.exception.detail["code"], "markdown_output_contract_failed")
        self.assertNotIn("secret-internal-detail", str(raised.exception.detail))

    def test_agent_turn_maps_markdown_target_boundary_failure(self) -> None:
        with self.assertRaises(HTTPException) as raised:
            handle_agent_turn(
                AgentTurnRequestBody(message="선택 영역을 다듬어줘"),
                use_case=InvalidMarkdownTargetUseCase(),  # type: ignore[arg-type]
            )

        self.assertEqual(raised.exception.status_code, 422)
        self.assertEqual(raised.exception.detail["code"], "markdown_target_crosses_structure")
        self.assertEqual(raised.exception.detail["start_line"], 2)


if __name__ == "__main__":
    unittest.main()
