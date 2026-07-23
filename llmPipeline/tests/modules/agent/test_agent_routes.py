import unittest

from fastapi import HTTPException

from app.modules.agent.domain.entities import AgentTurnResult, AgentTurnRoute
from app.modules.agent.domain.exceptions import AgentTurnRouteContractError
from app.modules.agent.interfaces.http.routes import handle_agent_turn
from app.modules.agent.interfaces.http.schemas import AgentTurnRequestBody
from app.modules.markdown_edit.domain.entities import (
    GeneratedMarkdownDocument,
    MarkdownEditOperation,
    MarkdownEditTarget,
)
from app.modules.markdown_edit.domain.markdown_output_contract import (
    MarkdownCreateOutputContractError,
    MarkdownOutputContractError,
)
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


class FixedInsertAfterUseCase:
    def execute(self, request: object) -> AgentTurnResult:
        target = MarkdownEditTarget(type="current_section", start_line=1, end_line=3)
        return AgentTurnResult(
            action="markdown_edit",
            route=AgentTurnRoute(
                action="markdown_edit",
                confidence=1.0,
                reason="insert after request",
                edit_goal="insert_after",
            ),
            edit=MarkdownEditOperation(
                operation="insert_after",
                target=target,
                summary="문제 해결 절을 추가했습니다.",
                replacement_markdown="## 문제 해결\n\n로그를 확인합니다.",
            ),
        )


class FixedExpandedTargetUseCase:
    def execute(self, request: object) -> AgentTurnResult:
        return AgentTurnResult(
            action="markdown_edit",
            route=AgentTurnRoute(
                action="markdown_edit",
                confidence=1.0,
                reason="expanded edit request",
            ),
            edit=MarkdownEditOperation(
                operation="replace",
                requested_target=MarkdownEditTarget(
                    type="selection",
                    start_line=2,
                    end_line=2,
                ),
                target=MarkdownEditTarget(
                    type="selection",
                    start_line=1,
                    end_line=3,
                ),
                summary="문맥을 포함해 수정했습니다.",
                replacement_markdown="수정 결과",
            ),
        )


class FailingMarkdownEditUseCase:
    def execute(self, request: object) -> AgentTurnResult:
        raise MarkdownOutputContractError(
            ["protected token count mismatch: secret-internal-detail"],
            "invalid output",
        )


class FailingMarkdownCreateUseCase:
    def execute(self, request: object) -> AgentTurnResult:
        raise MarkdownCreateOutputContractError(["secret-internal-detail"], {"invalid": "output"})


class InvalidMarkdownTargetUseCase:
    def execute(self, request: object) -> AgentTurnResult:
        raise MarkdownTargetBoundaryError("fence", 2, 4)


class UnexpectedFailureUseCase:
    def execute(self, request: object) -> AgentTurnResult:
        raise RuntimeError("secret-internal-detail")


class FailingAgentRouteUseCase:
    def execute(self, request: object) -> AgentTurnResult:
        raise AgentTurnRouteContractError(["secret-internal-detail"])


class AgentRoutesTest(unittest.TestCase):
    def test_agent_turn_returns_insert_after_operation(self) -> None:
        response = handle_agent_turn(
            AgentTurnRequestBody(message="현재 섹션 아래에 문제 해결 절을 추가해줘"),
            use_case=FixedInsertAfterUseCase(),  # type: ignore[arg-type]
        )

        body = response.model_dump()
        self.assertEqual(body["edit"]["operation"], "insert_after")
        self.assertEqual(body["edit"]["requested_target"]["type"], "current_section")
        self.assertEqual(body["edit"]["actual_target"]["type"], "current_section")
        self.assertFalse(body["edit"]["scope_expanded"])

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

    def test_agent_turn_distinguishes_requested_and_expanded_actual_target(self) -> None:
        response = handle_agent_turn(
            AgentTurnRequestBody(message="문맥을 포함해 다듬어줘"),
            use_case=FixedExpandedTargetUseCase(),  # type: ignore[arg-type]
        )

        edit = response.model_dump()["edit"]
        self.assertEqual(edit["requested_target"]["start_line"], 2)
        self.assertEqual(edit["actual_target"]["start_line"], 1)
        self.assertTrue(edit["scope_expanded"])

    def test_agent_turn_maps_markdown_contract_failure_without_internal_details(self) -> None:
        with self.assertRaises(HTTPException) as raised:
            handle_agent_turn(
                AgentTurnRequestBody(message="문서를 다듬어줘"),
                use_case=FailingMarkdownEditUseCase(),  # type: ignore[arg-type]
            )

        self.assertEqual(raised.exception.status_code, 422)
        self.assertEqual(raised.exception.detail["code"], "markdown_output_contract_failed")
        self.assertNotIn("secret-internal-detail", str(raised.exception.detail))

    def test_agent_turn_maps_markdown_create_contract_failure_without_internal_details(self) -> None:
        with self.assertRaises(HTTPException) as raised:
            handle_agent_turn(
                AgentTurnRequestBody(message="대화를 Markdown 문서로 만들어줘"),
                use_case=FailingMarkdownCreateUseCase(),  # type: ignore[arg-type]
            )

        self.assertEqual(raised.exception.status_code, 422)
        self.assertEqual(raised.exception.detail["code"], "markdown_create_output_contract_failed")
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

    def test_agent_turn_maps_route_contract_failure_without_internal_details(self) -> None:
        with self.assertRaises(HTTPException) as raised:
            handle_agent_turn(
                AgentTurnRequestBody(message="문서를 다듬어줘"),
                use_case=FailingAgentRouteUseCase(),  # type: ignore[arg-type]
            )

        self.assertEqual(raised.exception.status_code, 422)
        self.assertEqual(raised.exception.detail["code"], "agent_turn_route_contract_failed")
        self.assertNotIn("secret-internal-detail", str(raised.exception.detail))

    def test_agent_turn_hides_unexpected_failure_details(self) -> None:
        with self.assertLogs("app.modules.agent.interfaces.http.routes", level="ERROR") as captured:
            with self.assertRaises(HTTPException) as raised:
                handle_agent_turn(
                    AgentTurnRequestBody(message="문서를 다듬어줘"),
                    use_case=UnexpectedFailureUseCase(),  # type: ignore[arg-type]
                )

        self.assertEqual(raised.exception.status_code, 500)
        self.assertEqual(raised.exception.detail["code"], "internal_server_error")
        self.assertNotIn("secret-internal-detail", str(raised.exception.detail))
        self.assertNotIn("secret-internal-detail", "\n".join(captured.output))


if __name__ == "__main__":
    unittest.main()
