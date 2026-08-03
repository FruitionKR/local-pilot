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
from app.modules.skill.application.select_skill import SelectSkillUseCase
from app.modules.skill.domain.entities import Skill, SkillVersion


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
        self.kwargs: list[dict[str, object]] = []

    def execute(self, question: str, **kwargs: object) -> QueryAnswer:
        self.questions.append(question)
        self.kwargs.append(kwargs)
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


class FixedSkillRepository:
    def __init__(self, skill: Skill) -> None:
        self.skill = skill

    def list_accessible_enabled(self, workspace_id: str, user_id: str) -> list[Skill]:
        return [self.skill]

    def get_accessible(self, workspace_id: str, user_id: str, skill_id: str) -> Skill | None:
        return self.skill if self.skill.id == skill_id else None

    def get_accessible_by_slug(self, workspace_id: str, user_id: str, slug: str) -> Skill | None:
        return self.skill if self.skill.slug == slug else None


class RecordingAgentRunStarter:
    def __init__(self) -> None:
        self.requests: list[object] = []

    def start(self, request: object) -> tuple[str, str]:
        self.requests.append(request)
        return "run-1", "queued"


def document_skill(capability: str = "document-create") -> Skill:
    return Skill(
        id="skill-1",
        workspace_id="workspace-1",
        scope_type="personal",
        owner_user_id="user-1",
        slug="brief",
        status="enabled",
        enabled_version=SkillVersion(
            id="version-1",
            skill_id="skill-1",
            version=1,
            name="간결한 문서",
            description="문서를 간결하게 작성합니다.",
            instructions_markdown="핵심 내용을 세 문단 이내로 작성한다.",
            capabilities=(capability,),  # type: ignore[arg-type]
            status="published",
        ),
    )


class HandleAgentTurnUseCaseTest(unittest.TestCase):
    def test_folder_organize_starts_async_run_without_direct_edit(self) -> None:
        starter = RecordingAgentRunStarter()
        skill = document_skill("folder-organize")
        editor = RecordingMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="replace",
                    target=MarkdownEditTarget(type="selection", start_line=1, end_line=1),
                    summary="unused",
                    replacement_markdown="unused",
                )
            )
        )
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(
                AgentTurnRoute(
                    action="folder_organize",
                    confidence=0.95,
                    reason="folder request",
                    selected_skill_id="skill-1",
                )
            ),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
            markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
            skill_selector=SelectSkillUseCase(FixedSkillRepository(skill)),  # type: ignore[arg-type]
            agent_run_starter=starter,  # type: ignore[arg-type]
        )

        result = use_case.execute(
            AgentTurnRequest(
                message="분기 문서를 폴더별로 정리해줘",
                workspace_id="workspace-1",
                user_id="user-1",
            )
        )

        self.assertEqual(result.action, "folder_organize")
        self.assertEqual(result.run_id, "run-1")
        self.assertEqual(result.run_status, "queued")
        self.assertEqual(getattr(starter.requests[0], "skill_version_id"), "version-1")
        self.assertEqual(editor.requests, [])

    def test_passes_selected_skill_instructions_to_markdown_create(self) -> None:
        editor = RecordingMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="replace",
                    target=MarkdownEditTarget(type="selection", start_line=1, end_line=1),
                    summary="unused",
                    replacement_markdown="unused",
                )
            )
        )
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(
                AgentTurnRoute(
                    action="markdown_create",
                    confidence=0.9,
                    reason="create request",
                    selected_skill_id="skill-1",
                )
            ),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
            markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
            skill_selector=SelectSkillUseCase(FixedSkillRepository(document_skill())),  # type: ignore[arg-type]
        )

        result = use_case.execute(
            AgentTurnRequest(
                message="회의록을 만들어줘",
                workspace_id="workspace-1",
                user_id="user-1",
            )
        )

        self.assertEqual(result.route.selected_skill_id, "skill-1")
        self.assertEqual(
            editor.create_requests[0].skill_instructions,
            "핵심 내용을 세 문단 이내로 작성한다.",
        )

    def test_stops_for_ambiguous_skill_selection(self) -> None:
        skill = document_skill("folder-organize")
        editor = RecordingMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="replace",
                    target=MarkdownEditTarget(type="selection", start_line=1, end_line=1),
                    summary="unused",
                    replacement_markdown="unused",
                )
            )
        )
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(
                AgentTurnRoute(
                    action="clarify",
                    confidence=0.5,
                    reason="multiple skills match",
                    skill_candidates=("skill-1",),
                )
            ),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
            markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
            skill_selector=SelectSkillUseCase(FixedSkillRepository(skill)),  # type: ignore[arg-type]
        )

        result = use_case.execute(
            AgentTurnRequest(
                message="폴더를 정리해줘",
                workspace_id="workspace-1",
                user_id="user-1",
            )
        )

        self.assertEqual(result.action, "clarify")
        self.assertEqual(result.skill_candidates[0].description, "문서를 간결하게 작성합니다.")
        self.assertIn("Skill", result.message or "")

    def test_returns_reject_action_without_running_other_use_cases(self) -> None:
        editor = RecordingMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="replace",
                    target=MarkdownEditTarget(type="selection", start_line=1, end_line=1),
                    summary="사용하지 않는 결과",
                    replacement_markdown="사용하지 않는 결과",
                )
            )
        )
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(
                AgentTurnRoute(
                    action="reject",
                    confidence=1.0,
                    reason="unsupported request",
                )
            ),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
            markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
        )

        result = use_case.execute(AgentTurnRequest(message="지원하지 않는 작업을 해줘"))

        self.assertEqual(result.action, "reject")
        self.assertIsNotNone(result.message)
        self.assertEqual(editor.requests, [])

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
                workspace_id="workspace-1",
                user_id="user-1",
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
        self.assertEqual(editor.requests[0].workspace_id, "workspace-1")
        self.assertEqual(editor.requests[0].user_id, "user-1")

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
                workspace_id="workspace-1",
                user_id="user-1",
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
        self.assertEqual(editor.create_requests[0].workspace_id, "workspace-1")
        self.assertEqual(editor.create_requests[0].user_id, "user-1")

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

    def test_whole_document_target_counts_trailing_empty_line(self) -> None:
        for markdown in ("# 제목\n", "# 제목\r\n"):
            with self.subTest(markdown=repr(markdown)):
                target = MarkdownEditTarget(type="whole_document", start_line=1, end_line=2)
                editor = RecordingMarkdownEditor(
                    MarkdownEditResult(
                        edit=MarkdownEditOperation(
                            operation="replace",
                            target=target,
                            summary="문서 전체를 정리했습니다.",
                            replacement_markdown="# 정리된 문서",
                        )
                    )
                )
                use_case = HandleAgentTurnUseCase(
                    router=FixedRouter(
                        AgentTurnRoute(action="markdown_edit", confidence=0.8, reason="edit request")
                    ),
                    query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
                    markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
                    markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
                )

                result = use_case.execute(
                    AgentTurnRequest(
                        message="문서 전체를 정리해줘",
                        active_markdown_context=ActiveMarkdownContext(markdown=markdown),
                    )
                )

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

    def test_rechecks_insert_after_target_after_llm_routing(self) -> None:
        editor = RecordingMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="insert_after",
                    target=MarkdownEditTarget(type="current_section", start_line=1, end_line=1),
                    summary="unused",
                    replacement_markdown="unused",
                )
            )
        )
        use_case = HandleAgentTurnUseCase(
            router=FixedRouter(
                AgentTurnRoute(
                    action="markdown_edit",
                    confidence=0.8,
                    reason="LLM routed an insert_after request",
                    edit_goal="insert_after",
                )
            ),
            query_use_case=FakeQueryUseCase(),  # type: ignore[arg-type]
            markdown_edit_use_case=GenerateMarkdownEditUseCase(editor),
            markdown_create_use_case=GenerateMarkdownDocumentUseCase(editor),
        )

        result = use_case.execute(
            AgentTurnRequest(
                message="이어서 문제 해결 절을 추가해줘",
                active_markdown_context=ActiveMarkdownContext(markdown="# 설치\n\n설치 방법입니다."),
            )
        )

        self.assertEqual(result.action, "clarify")
        self.assertIn("현재 섹션을 선택", result.message or "")
        self.assertEqual(editor.requests, [])

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

        result = use_case.execute(
            AgentTurnRequest(
                message="이 문서는 무엇을 설명해?",
                workspace_id="workspace-1",
                user_id="user-1",
            )
        )

        self.assertEqual(result.action, "chat_answer")
        self.assertIsNotNone(result.query_answer)
        self.assertEqual(query_use_case.questions, ["이 문서는 무엇을 설명해?"])
        self.assertEqual(query_use_case.kwargs[0]["workspace_id"], "workspace-1")
        self.assertEqual(query_use_case.kwargs[0]["user_id"], "user-1")


if __name__ == "__main__":
    unittest.main()
