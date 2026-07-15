import unittest

from app.modules.agent.domain.entities import ActiveMarkdownContext, AgentTurnRequest
from app.modules.agent.infrastructure.chat_completions_turn_router import _local_guard
from app.modules.markdown_edit.domain.entities import MarkdownEditTarget


class ChatCompletionsTurnRouterTest(unittest.TestCase):
    def test_allows_general_whole_document_edit(self) -> None:
        request = AgentTurnRequest(
            message="전체 문서의 문체를 공식적으로 바꿔줘.",
            active_markdown_context=ActiveMarkdownContext(markdown="# 제목\n\n본문"),
        )

        self.assertIsNone(_local_guard(request))

    def test_allows_structure_preserving_edit(self) -> None:
        request = AgentTurnRequest(
            message="원문 구조는 그대로 유지하고 문장만 정리해줘.",
            active_markdown_context=ActiveMarkdownContext(markdown="# 제목\n\n본문"),
        )

        self.assertIsNone(_local_guard(request))

    def test_defers_explicit_template_transform(self) -> None:
        request = AgentTurnRequest(
            message="회사 템플릿에 맞춰 문서를 재구성해줘.",
            active_markdown_context=ActiveMarkdownContext(markdown="# 제목\n\n본문"),
        )

        route = _local_guard(request)

        self.assertIsNotNone(route)
        self.assertEqual(route.action, "clarify")
        self.assertEqual(route.edit_goal, "template_transform")

    def test_routes_insert_after_for_current_section(self) -> None:
        request = AgentTurnRequest(
            message="이 섹션 아래에 문제 해결 절을 추가해줘.",
            active_markdown_context=ActiveMarkdownContext(
                markdown="# 제목\n\n본문",
                target=MarkdownEditTarget(type="current_section", start_line=1, end_line=3),
            ),
        )

        route = _local_guard(request)

        self.assertIsNotNone(route)
        self.assertEqual(route.action, "markdown_edit")
        self.assertEqual(route.edit_goal, "insert_after")

    def test_asks_for_current_section_before_insert_after(self) -> None:
        request = AgentTurnRequest(
            message="이 섹션 아래에 문제 해결 절을 추가해줘.",
            active_markdown_context=ActiveMarkdownContext(markdown="# 제목\n\n본문"),
        )

        route = _local_guard(request)

        self.assertIsNotNone(route)
        self.assertEqual(route.action, "clarify")
        self.assertEqual(route.edit_goal, "insert_after")

    def test_does_not_treat_below_content_reference_as_insert_after(self) -> None:
        request = AgentTurnRequest(
            message="아래 내용을 표로 작성해줘.",
            active_markdown_context=ActiveMarkdownContext(markdown="# 제목\n\n본문"),
        )

        self.assertIsNone(_local_guard(request))


if __name__ == "__main__":
    unittest.main()
