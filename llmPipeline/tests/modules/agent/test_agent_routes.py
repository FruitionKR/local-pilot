import unittest

from app.modules.agent.domain.entities import AgentTurnResult, AgentTurnRoute
from app.modules.agent.interfaces.http.routes import handle_agent_turn
from app.modules.agent.interfaces.http.schemas import AgentTurnRequestBody
from app.modules.markdown_edit.domain.entities import GeneratedMarkdownDocument


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


if __name__ == "__main__":
    unittest.main()
