from app.modules.agent.application.ports import AgentTurnRouterPort
from app.modules.agent.domain.entities import AgentTurnRequest, AgentTurnResult
from app.modules.agent_run.application.ports import AgentRunStarterPort
from app.modules.agent_run.domain.entities import StartAgentRunRequest
from app.modules.markdown_edit.application.generate_markdown_document import GenerateMarkdownDocumentUseCase
from app.modules.markdown_edit.application.generate_markdown_edit import GenerateMarkdownEditUseCase
from app.modules.markdown_edit.domain.entities import MarkdownCreateRequest, MarkdownEditRequest, MarkdownEditTarget
from app.modules.markdown_edit.domain.markdown_target_scope import markdown_line_count
from app.modules.query.application.answer_query import AnswerQueryUseCase
from app.modules.query.domain.entities import ConversationContext
from app.modules.skill.application.select_skill import PreparedSkillSelection, SelectSkillUseCase
from app.modules.skill.application.propose_skill_draft import ProposeSkillDraftUseCase
from app.modules.skill.domain.entities import Skill


CLARIFY_MARKDOWN_TARGET_MESSAGE = "수정할 Markdown 범위를 선택한 뒤 다시 요청해 주세요."
CLARIFY_MARKDOWN_DOCUMENT_MESSAGE = "수정할 Markdown 문서를 연 뒤 다시 요청해 주세요."
DEFERRED_TEMPLATE_MESSAGE = "template 기반 전체 문서 재구성은 이후 단계에서 다루겠습니다. 현재는 선택 영역, 현재 섹션, 또는 문서 전체의 일반 편집만 지원합니다."
CLARIFY_INSERT_AFTER_TARGET_MESSAGE = "내용을 추가할 현재 섹션을 선택한 뒤 다시 요청해 주세요."
CLARIFY_SKILL_MESSAGE = "이 요청에 적용할 Skill을 선택하거나 Skill 없이 계속해 주세요."


class HandleAgentTurnUseCase:
    def __init__(
        self,
        router: AgentTurnRouterPort,
        query_use_case: AnswerQueryUseCase,
        markdown_edit_use_case: GenerateMarkdownEditUseCase,
        markdown_create_use_case: GenerateMarkdownDocumentUseCase,
        skill_selector: SelectSkillUseCase | None = None,
        agent_run_starter: AgentRunStarterPort | None = None,
        skill_draft_proposer: ProposeSkillDraftUseCase | None = None,
    ) -> None:
        self._router = router
        self._query_use_case = query_use_case
        self._markdown_edit_use_case = markdown_edit_use_case
        self._markdown_create_use_case = markdown_create_use_case
        self._skill_selector = skill_selector
        self._agent_run_starter = agent_run_starter
        self._skill_draft_proposer = skill_draft_proposer

    def execute(self, request: AgentTurnRequest) -> AgentTurnResult:
        if not request.message.strip():
            raise ValueError("message is required.")

        selection = self._prepare_skill_selection(request)
        request = selection.request
        resolved = selection.resolve_route(self._router.route(request))
        route = resolved.route
        selected_skill = resolved.skill
        if route.action == "clarify" and route.skill_candidates:
            candidates = tuple(
                candidate
                for candidate in request.available_skills
                if candidate.id in route.skill_candidates
            )
            return AgentTurnResult(
                action="clarify",
                route=route,
                message=CLARIFY_SKILL_MESSAGE,
                skill_candidates=candidates,
            )
        if route.action == "markdown_create":
            result = self._markdown_create_use_case.execute(
                MarkdownCreateRequest(
                    instruction=request.message,
                    workspace_id=request.workspace_id,
                    user_id=request.user_id,
                    conversation_summary=(
                        request.conversation_context.recent_conversation_summary
                        if request.conversation_context
                        else None
                    ),
                    reference_context=(
                        request.conversation_context.reference_context
                        if request.conversation_context
                        else {}
                    ),
                    skill_instructions=_skill_instructions(selected_skill),
                )
            )
            return AgentTurnResult(action="markdown_create", route=route, generated_markdown=result.document)

        if route.action == "skill_draft_proposal":
            if not request.skill_draft_sources:
                return AgentTurnResult(
                    action="clarify",
                    route=route,
                    message="Skill로 만들 완료 작업을 선택해 주세요.",
                )
            if self._skill_draft_proposer is None:
                raise ValueError("Skill draft proposal is not configured.")
            proposal = self._skill_draft_proposer.execute(
                source_runs=request.skill_draft_sources,
                user_directives=request.skill_draft_user_directives,
                excluded_literals=request.skill_draft_excluded_literals,
            )
            return AgentTurnResult(
                action="skill_draft_proposal",
                route=route,
                skill_draft_proposal=proposal,
            )

        if route.action in {"folder_organize", "workspace_workflow"}:
            if self._agent_run_starter is None or not request.workspace_id or not request.user_id:
                raise ValueError("Workspace workflow requires workspace_id and user_id.")
            skill_version_id = (
                selected_skill.enabled_version.id
                if selected_skill is not None and selected_skill.enabled_version is not None
                else None
            )
            run_id, run_status = self._agent_run_starter.start(
                StartAgentRunRequest(
                    workspace_id=request.workspace_id,
                    user_id=request.user_id,
                    instruction=request.message,
                    action=route.action,
                    skill_version_id=skill_version_id,
                )
            )
            return AgentTurnResult(
                action=route.action,
                route=route,
                run_id=run_id,
                run_status=run_status,
            )

        if route.action == "markdown_edit":
            markdown_context = request.active_markdown_context
            if markdown_context is None or not markdown_context.markdown.strip():
                return AgentTurnResult(
                    action="clarify",
                    route=route,
                    message=CLARIFY_MARKDOWN_DOCUMENT_MESSAGE,
                )
            if route.edit_goal == "insert_after" and (
                markdown_context.target is None or markdown_context.target.type != "current_section"
            ):
                return AgentTurnResult(
                    action="clarify",
                    route=route,
                    message=CLARIFY_INSERT_AFTER_TARGET_MESSAGE,
                )
            target = markdown_context.target or _whole_document_target(markdown_context.markdown)
            result = self._markdown_edit_use_case.execute(
                MarkdownEditRequest(
                    instruction=request.message,
                    markdown=markdown_context.markdown,
                    target=target,
                    workspace_id=request.workspace_id,
                    user_id=request.user_id,
                    conversation_summary=(
                        request.conversation_context.recent_conversation_summary
                        if request.conversation_context
                        else None
                    ),
                    edit_goal=route.edit_goal,
                    skill_instructions=_skill_instructions(selected_skill),
                )
            )
            return AgentTurnResult(action="markdown_edit", route=route, edit=result.edit)

        if route.action == "clarify":
            if route.edit_goal == "template_transform":
                message = DEFERRED_TEMPLATE_MESSAGE
            elif route.edit_goal == "insert_after":
                message = CLARIFY_INSERT_AFTER_TARGET_MESSAGE
            else:
                message = CLARIFY_MARKDOWN_TARGET_MESSAGE
            return AgentTurnResult(action="clarify", route=route, message=message)

        if route.action == "reject":
            return AgentTurnResult(
                action="reject",
                route=route,
                message="요청한 작업은 현재 지원 범위에서 처리할 수 없습니다.",
            )

        answer = self._query_use_case.execute(
            request.message,
            workspace_id=request.workspace_id or "",
            user_id=request.user_id,
            conversation_context=_to_query_conversation_context(request),
        )
        return AgentTurnResult(action="chat_answer", route=route, query_answer=answer)

    def _prepare_skill_selection(self, request: AgentTurnRequest) -> PreparedSkillSelection:
        if self._skill_selector is not None:
            return self._skill_selector.prepare(request)
        return PreparedSkillSelection(request=request, skills=())


def _to_query_conversation_context(request: AgentTurnRequest) -> ConversationContext | None:
    if request.conversation_context is None:
        return None
    if request.conversation_context.recent_conversation_summary is None and not request.conversation_context.reference_context:
        return None
    return ConversationContext(
        recent_conversation_summary=request.conversation_context.recent_conversation_summary,
        reference_context=request.conversation_context.reference_context,
    )


def _whole_document_target(markdown: str) -> MarkdownEditTarget:
    return MarkdownEditTarget(
        type="whole_document",
        start_line=1,
        end_line=max(1, markdown_line_count(markdown)),
    )


def _skill_instructions(skill: Skill | None) -> str | None:
    if skill is None or skill.enabled_version is None:
        return None
    return skill.enabled_version.instructions_markdown
