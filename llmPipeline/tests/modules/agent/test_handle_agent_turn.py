import unittest

from app.modules.agent.application.handle_agent_turn import HandleAgentTurnUseCase
from app.modules.agent.domain.entities import (
    ActiveMarkdownContext,
    AgentConversationContext,
    AgentTurnRequest,
    AgentTurnRoute,
)
from app.modules.markdown_edit.application.generate_markdown_document import GenerateMarkdownDocumentUseCase
from app.modules.markdown_edit.application.generate_markdown_edit import GenerateMarkdownEditUseCase
from app.modules.markdown_edit.domain.entities import (
    GeneratedMarkdownDocument,
    MarkdownCreateRequest,
    MarkdownCreateResult,
    MarkdownEditOperation,
    MarkdownEditRequest,
    MarkdownEditResult,
    MarkdownEditTarget,
)
from app.modules.query.domain.entities import (
    GeneratedAnswer,
    GraphContext,
    QueryAnswer,
    RetrievalSummary,
)


class FixedRouter:
    def __init__(self, route: AgentTurnRoute) -> None:
        self.next_route = route
        self.requests: list[AgentTurnRequest] = []

    def route(self, request: AgentTurnRequest) -> AgentTurnRoute:
        self.requests.append(request)
        return self.next_route


class FakeQueryUseCase:
    def __init__(self) -> None:
        self.questions: list[str] = []

    def execute(self, question: str, **kwargs: object) -> QueryAnswer:
        self.questions.append(question)
        return QueryAnswer(
            answer=GeneratedAnswer(content="질문 답변입니다."),
            related_pages=[],
            evidence_snippets=[],
            graph_context=GraphContext(),
            traversal_paths=[],
            retrieval_summary=RetrievalSummary(
                source_candidate_count=0,
                concept_candidate_count=0,
                visited_node_count=0,
                returned_node_count=0,
                used_source_count=0,
                used_concept_count=0,
                max_depth=0,
                stop_reason="test",
            ),
        )


class RecordingMarkdownEditor:
    def __init__(self, result: MarkdownEditResult, create_result: MarkdownCreateResult | None = None) -> None:
        self.result = result
        self.create_result = create_result or MarkdownCreateResult(
            document=GeneratedMarkdownDocument(
                title="생성 문서",
                summary="대화 내용을 Markdown으로 정리했습니다.",
                markdown="# 생성 문서\n\n대화 요약입니다.",
            )
        )
        self.requests: list[MarkdownEditRequest] = []
        self.create_requests: list[MarkdownCreateRequest] = []

    def generate_edit(self, request: MarkdownEditRequest) -> MarkdownEditResult:
        self.requests.append(request)
        return self.result

    def generate_markdown(self, request: MarkdownCreateRequest) -> MarkdownCreateResult:
        self.create_requests.append(request)
        return self.create_result


class HandleAgentTurnUseCaseTest(unittest.TestCase):
    def test_executes_markdown_edit_action(self) -> None:
        target = MarkdownEditTarget(type="selection", start_line=3, end_line=5)
        editor = RecordingMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="replace",
                    target=target,
                    summary="선택 영역을 줄였습니다.",
                    replacement_markdown="짧은 문장입니다.",
                )
            )
        )
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(
                AgentTurnRoute(action="markdown_edit", confidence=0.9, reason="edit request", edit_goal="shorten")
            ),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
            markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
        )

        result = use_case.execute(
            AgentTurnRequest(
                message="줄여줘",
                active_markdown_context=ActiveMarkdownContext(
                    markdown="첫 줄\n둘째 줄\n긴 문장입니다.\n반복 문장입니다.\n마지막 문장입니다.",
                    target=target,
                ),
            )
        )

        self.assertEqual(result.action, "markdown_edit")
        self.assertIsNotNone(result.edit)
        self.assertEqual(result.edit.target, target)
        self.assertEqual(editor.requests[0].instruction, "줄여줘")
        self.assertEqual(editor.requests[0].edit_goal, "shorten")

    def test_executes_markdown_create_action(self) -> None:
        target = MarkdownEditTarget(type="selection", start_line=1, end_line=1)
        editor = RecordingMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="replace",
                    target=target,
                    summary="unused",
                    replacement_markdown="unused",
                )
            ),
            create_result=MarkdownCreateResult(
                document=GeneratedMarkdownDocument(
                    title="Agent 설계 메모",
                    summary="대화 내용을 Markdown 문서로 정리했습니다.",
                    markdown="# Agent 설계 메모\n\n- 편집과 생성을 분리한다.",
                )
            ),
        )
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(
                AgentTurnRoute(
                    action="markdown_create",
                    confidence=0.92,
                    reason="create markdown from chat",
                    edit_goal="create_from_chat",
                )
            ),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
            markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
        )

        result = use_case.execute(
            AgentTurnRequest(
                message="지금까지 이야기한 내용 md로 만들어줘",
                conversation_context=AgentConversationContext(
                    recent_conversation_summary="사용자는 편집과 생성을 분리하는 agent 설계를 논의했다."
                ),
            )
        )

        self.assertEqual(result.action, "markdown_create")
        self.assertIsNotNone(result.generated_markdown)
        self.assertEqual(result.generated_markdown.title, "Agent 설계 메모")
        self.assertEqual(editor.create_requests[0].instruction, "지금까지 이야기한 내용 md로 만들어줘")
        self.assertIn("편집과 생성을 분리", editor.create_requests[0].conversation_summary or "")

    def test_uses_whole_document_when_edit_has_markdown_but_no_target(self) -> None:
        target = MarkdownEditTarget(type="whole_document", start_line=1, end_line=3)
        editor = RecordingMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="replace",
                    target=target,
                    summary="문서 전체를 정리했습니다.",
                    replacement_markdown="# 정리된 문서\n\n본문입니다.",
                )
            )
        )
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(AgentTurnRoute(action="markdown_edit", confidence=0.8, reason="edit request")),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
            markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
        )

        result = use_case.execute(
            AgentTurnRequest(
                message="문서 전체를 보기 좋게 정리해줘",
                active_markdown_context=ActiveMarkdownContext(
                    markdown="# 제목\n\n긴 본문입니다.",
                ),
            )
        )

        self.assertEqual(result.action, "markdown_edit")
        self.assertIsNotNone(result.edit)
        self.assertEqual(result.edit.target, target)
        self.assertEqual(editor.requests[0].target, target)

    def test_asks_for_document_when_edit_has_no_markdown(self) -> None:
        editor = RecordingMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="replace",
                    target=MarkdownEditTarget(type="selection", start_line=1, end_line=1),
                    summary="",
                    replacement_markdown="unused",
                )
            )
        )
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(AgentTurnRoute(action="markdown_edit", confidence=0.8, reason="edit request")),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
            markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
        )

        result = use_case.execute(AgentTurnRequest(message="표로 바꿔줘"))

        self.assertEqual(result.action, "clarify")
        self.assertIn("Markdown 문서", result.message or "")

    def test_defers_template_transform(self) -> None:
        editor = RecordingMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="replace",
                    target=MarkdownEditTarget(type="selection", start_line=1, end_line=1),
                    summary="",
                    replacement_markdown="unused",
                )
            )
        )
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(
                AgentTurnRoute(
                    action="clarify",
                    confidence=1.0,
                    reason="template transform is deferred",
                    edit_goal="template_transform",
                )
            ),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
            markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
        )

        result = use_case.execute(AgentTurnRequest(message="회사 template에 맞춰줘"))

        self.assertEqual(result.action, "clarify")
        self.assertIn("template 기반 전체 문서 재구성", result.message or "")
        self.assertIn("문서 전체의 일반 편집", result.message or "")

    def test_asks_for_current_section_before_insert_after(self) -> None:
        editor = RecordingMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="replace",
                    target=MarkdownEditTarget(type="current_section", start_line=1, end_line=1),
                    summary="unused",
                    replacement_markdown="unused",
                )
            )
        )
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(
                AgentTurnRoute(
                    action="clarify",
                    confidence=1.0,
                    reason="insert_after operation is deferred",
                    edit_goal="insert_after",
                )
            ),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
            markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
        )

        result = use_case.execute(AgentTurnRequest(message="이 섹션 아래에 내용을 추가해줘"))

        self.assertEqual(result.action, "clarify")
        self.assertIn("현재 섹션을 선택", result.message or "")

    def test_routes_chat_to_query_use_case(self) -> None:
        query_use_case = FakeQueryUseCase()
        editor = RecordingMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="replace",
                    target=MarkdownEditTarget(type="selection", start_line=1, end_line=1),
                    summary="",
                    replacement_markdown="unused",
                )
            )
        )
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(AgentTurnRoute(action="chat_answer", confidence=0.9, reason="question")),
            query_use_case=query_use_case,  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
            markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
        )

        result = use_case.execute(AgentTurnRequest(message="이 문서는 무엇을 설명해?"))

        self.assertEqual(result.action, "chat_answer")
        self.assertIsNotNone(result.query_answer)
        self.assertEqual(query_use_case.questions, ["이 문서는 무엇을 설명해?"])


if __name__ == "__main__":
    unittest.main()
