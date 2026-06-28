import unittest

from app.modules.agent.application.handle_agent_turn import HandleAgentTurnUseCase
from app.modules.agent.domain.entities import (
    ActiveMarkdownContext,
    AgentTurnRequest,
    AgentTurnRoute,
)
from app.modules.markdown_edit.application.generate_markdown_edit import GenerateMarkdownEditUseCase
from app.modules.markdown_edit.domain.entities import (
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
    def __init__(self, result: MarkdownEditResult) -> None:
        self.result = result
        self.requests: list[MarkdownEditRequest] = []

    def generate_edit(self, request: MarkdownEditRequest) -> MarkdownEditResult:
        self.requests.append(request)
        return self.result


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
            router=FixedRouter(AgentTurnRoute(action="markdown_edit", confidence=0.9, reason="edit request")),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
        )

        result = use_case.execute(
            AgentTurnRequest(
                message="줄여줘",
                active_markdown_context=ActiveMarkdownContext(
                    markdown="긴 문장입니다.",
                    target=target,
                    document_kind="wiki_page",
                ),
            )
        )

        self.assertEqual(result.action, "markdown_edit")
        self.assertIsNotNone(result.edit)
        self.assertEqual(result.edit.target, target)
        self.assertEqual(editor.requests[0].instruction, "줄여줘")

    def test_asks_for_target_when_edit_has_no_markdown_target(self) -> None:
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(AgentTurnRoute(action="markdown_edit", confidence=0.8, reason="edit request")),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(
                RecordingMarkdownEditor(
                    MarkdownEditResult(
                        edit=MarkdownEditOperation(
                            operation="replace",
                            target=MarkdownEditTarget(type="selection", start_line=1, end_line=1),
                            summary="",
                            replacement_markdown="unused",
                        )
                    )
                )
            ),
        )

        result = use_case.execute(AgentTurnRequest(message="표로 바꿔줘"))

        self.assertEqual(result.action, "clarify")
        self.assertIn("Markdown 범위", result.message or "")

    def test_defers_template_transform(self) -> None:
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
            markdown_edit_use_case=GenerateMarkdownEditUseCase(
                RecordingMarkdownEditor(
                    MarkdownEditResult(
                        edit=MarkdownEditOperation(
                            operation="replace",
                            target=MarkdownEditTarget(type="selection", start_line=1, end_line=1),
                            summary="",
                            replacement_markdown="unused",
                        )
                    )
                )
            ),
        )

        result = use_case.execute(AgentTurnRequest(message="회사 template에 맞춰줘"))

        self.assertEqual(result.action, "clarify")
        self.assertIn("template 기반 전체 문서 재구성", result.message or "")

    def test_routes_chat_to_query_use_case(self) -> None:
        query_use_case = FakeQueryUseCase()
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(AgentTurnRoute(action="chat_answer", confidence=0.9, reason="question")),
            query_use_case=query_use_case,  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(
                RecordingMarkdownEditor(
                    MarkdownEditResult(
                        edit=MarkdownEditOperation(
                            operation="replace",
                            target=MarkdownEditTarget(type="selection", start_line=1, end_line=1),
                            summary="",
                            replacement_markdown="unused",
                        )
                    )
                )
            ),
        )

        result = use_case.execute(AgentTurnRequest(message="이 문서는 무엇을 설명해?"))

        self.assertEqual(result.action, "chat_answer")
        self.assertIsNotNone(result.query_answer)
        self.assertEqual(query_use_case.questions, ["이 문서는 무엇을 설명해?"])


if __name__ == "__main__":
    unittest.main()
