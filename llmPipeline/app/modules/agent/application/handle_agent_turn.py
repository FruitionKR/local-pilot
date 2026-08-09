import re
from dataclasses import replace

from app.modules.agent.application.ports import AgentTurnRouterPort
from app.modules.agent.domain.entities import AgentTurnRequest, AgentTurnResult, PendingSkillProposal
from app.modules.agent_run.application.ports import AgentRunStarterPort
from app.modules.agent_run.domain.entities import StartAgentRunRequest
from app.modules.markdown_edit.application.generate_markdown_document import GenerateMarkdownDocumentUseCase
from app.modules.markdown_edit.application.generate_markdown_edit import GenerateMarkdownEditUseCase
from app.modules.markdown_edit.domain.entities import MarkdownCreateRequest, MarkdownEditRequest, MarkdownEditTarget
from app.modules.markdown_edit.domain.markdown_target_scope import markdown_line_count
from app.modules.query.application.answer_query import AnswerQueryUseCase
from app.modules.query.application.conversation_context_resolver import (
    conversation_messages_text,
    update_conversation_summary,
)
from app.modules.query.application.ports import ConversationSummarizerPort
from app.modules.query.domain.entities import ConversationContext
from app.modules.skill.application.author_skill import AuthorSkillUseCase
from app.modules.skill.application.select_skill import PreparedSkillSelection, SelectSkillUseCase
from app.modules.skill.application.propose_skill_draft import ProposeSkillDraftUseCase
from app.modules.skill.domain.entities import (
    Skill,
    SkillAuthoringProposal,
    SkillAuthoringResult,
    SkillScopeType,
)
from app.modules.skill.domain.policy import validate_skill_name
from app.modules.skill.domain.safety import inspect_skill_instructions


CLARIFY_MARKDOWN_TARGET_MESSAGE = "수정할 Markdown 범위를 선택한 뒤 다시 요청해 주세요."
CLARIFY_MARKDOWN_DOCUMENT_MESSAGE = "수정할 Markdown 문서를 연 뒤 다시 요청해 주세요."
DEFERRED_TEMPLATE_MESSAGE = "template 기반 전체 문서 재구성은 이후 단계에서 다루겠습니다. 현재는 선택 영역, 현재 섹션, 또는 문서 전체의 일반 편집만 지원합니다."
CLARIFY_INSERT_AFTER_TARGET_MESSAGE = "내용을 추가할 현재 섹션을 선택한 뒤 다시 요청해 주세요."
CLARIFY_SKILL_MESSAGE = "이 요청에 적용할 Skill을 선택하거나 Skill 없이 계속해 주세요."
CLARIFY_MUTATION_INTENT_MESSAGE = "변경 작업은 대화나 참조 문서가 아닌 현재 메시지에 직접 요청해 주세요."
BLOCKED_SKILL_AUTHORING_MESSAGE = "보안 문제가 있는 내용을 제거하거나 수정한 뒤 다시 시도해 주세요."
TITLE_REVISION_PATTERN = re.compile(
    r"(?:제목|이름|커맨드|식별자)(?:을|를)?\s*(?:[\"“「](.+?)[\"”」]|(.+?))\s*(?:로|으로)\s*(?:바꿔|변경|수정)"
)
PUBLISH_SKILL_PATTERN = re.compile(
    r"(?:이대로\s*)?(?:게시|등록)(?:해|해줘|해주세요|하자)|"
    r"(?:please\s+)?(?:publish|post)(?:\s+(?:it|this|the\s+skill))?",
    re.IGNORECASE,
)
SECURITY_REVIEW_PATTERN = re.compile(r"보안\s*(?:재)?검토|다시\s*검증|security\s*(?:re)?view", re.IGNORECASE)
REGENERATE_PATTERN = re.compile(r"(?:AI로\s*)?재생성|다시\s*(?:만들어|작성)|regenerate", re.IGNORECASE)


class HandleAgentTurnUseCase:
    def __init__(
        self,
        router: AgentTurnRouterPort,
        query_use_case: AnswerQueryUseCase,
        markdown_edit_use_case: GenerateMarkdownEditUseCase,
        markdown_create_use_case: GenerateMarkdownDocumentUseCase,
        skill_selector: SelectSkillUseCase | None = None,
        agent_run_starter: AgentRunStarterPort | None = None,
        skill_authorer: AuthorSkillUseCase | None = None,
        skill_draft_proposer: ProposeSkillDraftUseCase | None = None,
        conversation_summarizer: ConversationSummarizerPort | None = None,
    ) -> None:
        self._router = router
        self._query_use_case = query_use_case
        self._markdown_edit_use_case = markdown_edit_use_case
        self._markdown_create_use_case = markdown_create_use_case
        self._skill_selector = skill_selector
        self._agent_run_starter = agent_run_starter
        self._skill_authorer = skill_authorer
        self._skill_draft_proposer = skill_draft_proposer
        self._conversation_summarizer = conversation_summarizer

    def execute(self, request: AgentTurnRequest) -> AgentTurnResult:
        if not request.message.strip():
            raise ValueError("message is required.")
        result = self._execute(request)
        updated_summary = (
            result.query_answer.updated_conversation_summary
            if result.query_answer is not None
            else update_conversation_summary(
                _to_query_conversation_context(request),
                self._conversation_summarizer,
            )
        )
        return replace(
            result,
            updated_conversation_summary=updated_summary,
        )

    def _execute(self, request: AgentTurnRequest) -> AgentTurnResult:
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
                    conversation_summary=_conversation_context_text(request),
                    reference_context=(
                        request.conversation_context.reference_context
                        if request.conversation_context
                        else {}
                    ),
                    skill_instructions=_skill_instructions(selected_skill),
                    output_language=request.output_language,
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
            if (
                self._skill_draft_proposer is None
                or self._skill_authorer is None
                or not request.workspace_id
                or not request.user_id
            ):
                raise ValueError("Skill draft proposal is not configured.")
            scope_type = _skill_scope_type(request)
            if scope_type is None:
                return AgentTurnResult(
                    action="clarify",
                    route=route,
                    message="개인 스킬로 만들까요, 현재 팀 스킬로 만들까요?",
                )
            proposal = self._skill_draft_proposer.execute(
                source_runs=request.skill_draft_sources,
                user_directives=request.skill_draft_user_directives,
                excluded_literals=request.skill_draft_excluded_literals,
            )
            reviewed = self._skill_authorer.review_draft(
                workspace_id=request.workspace_id,
                user_id=request.user_id,
                scope_type=scope_type,
                draft=proposal,
            )
            return AgentTurnResult(
                action="skill_authoring",
                route=route,
                message=(
                    BLOCKED_SKILL_AUTHORING_MESSAGE
                    if reviewed.status == "blocked"
                    else _skill_authoring_message(reviewed)
                ),
                skill_authoring_result=reviewed,
            )

        if route.action == "skill_authoring":
            if not request.workspace_id or not request.user_id:
                raise ValueError("Skill authoring requires workspace_id and user_id.")
            pending_proposal = (
                request.conversation_context.pending_skill_proposal
                if request.conversation_context
                else None
            )
            if pending_proposal is not None:
                authored = self._handle_pending_skill(request, pending_proposal)
            else:
                if self._skill_authorer is None:
                    raise ValueError("Skill authoring is not configured.")
                scope_type = _skill_scope_type(request)
                if scope_type is None:
                    authored = SkillAuthoringResult(
                        status="clarification_required",
                        question="개인 스킬로 만들까요, 현재 팀 스킬로 만들까요?",
                    )
                else:
                    authored = self._skill_authorer.execute(
                        workspace_id=request.workspace_id,
                        user_id=request.user_id,
                        scope_type=scope_type,
                        instruction=_skill_authoring_instruction(request),
                        reference_document_ids=request.skill_reference_document_ids,
                        allow_clarification=True,
                        authoring_mode=request.skill_authoring_mode,
                    )
            message = (
                BLOCKED_SKILL_AUTHORING_MESSAGE
                if authored.status == "blocked"
                else authored.question or _skill_authoring_message(authored)
            )
            return AgentTurnResult(
                action="skill_authoring",
                route=route,
                message=message,
                skill_authoring_result=authored,
            )

        if route.action in {"folder_organize", "workspace_workflow"}:
            if self._agent_run_starter is None or not request.workspace_id or not request.user_id:
                raise ValueError("Workspace workflow requires workspace_id and user_id.")
            direct_route = self._router.route(
                replace(
                    request,
                    conversation_context=None,
                    active_markdown_context=None,
                    skill_mode="off",
                    skill_id=None,
                    available_skills=(),
                    skill_draft_sources=(),
                    skill_draft_user_directives=(),
                    skill_draft_excluded_literals=(),
                )
            )
            if direct_route.action != route.action:
                return AgentTurnResult(
                    action="clarify",
                    route=replace(
                        route,
                        action="clarify",
                        confidence=0.0,
                        reason="Direct mutation intent was not confirmed.",
                        selected_skill_id=None,
                        skill_candidates=(),
                    ),
                    message=CLARIFY_MUTATION_INTENT_MESSAGE,
                )
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
                    conversation_summary=_conversation_context_text(request),
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
            output_language=request.output_language,
            response_length=request.response_length,
            allow_web_search=request.allow_web_search,
        )
        return AgentTurnResult(action="chat_answer", route=route, query_answer=answer)

    def _handle_pending_skill(
        self,
        request: AgentTurnRequest,
        proposal: PendingSkillProposal,
    ) -> SkillAuthoringResult:
        if self._skill_authorer is None:
            raise ValueError("Skill authoring is not configured.")
        if PUBLISH_SKILL_PATTERN.fullmatch(request.message.strip()):
            return self._skill_authorer.publish(
                workspace_id=request.workspace_id or "",
                user_id=request.user_id or "",
                scope_type=proposal.scope_type,
                name=proposal.name,
                description=proposal.description,
                instructions_markdown=proposal.instructions_markdown,
            )
        if SECURITY_REVIEW_PATTERN.search(request.message):
            return self._skill_authorer.execute(
                workspace_id=request.workspace_id or "",
                user_id=request.user_id or "",
                scope_type=proposal.scope_type,
                name=proposal.name,
                description=proposal.description,
                instruction=proposal.instructions_markdown,
                reference_document_ids=(),
                allow_clarification=False,
                authoring_mode="preserve",
            )
        if REGENERATE_PATTERN.search(request.message):
            return self._skill_authorer.execute(
                workspace_id=request.workspace_id or "",
                user_id=request.user_id or "",
                scope_type=proposal.scope_type,
                name=proposal.name,
                instruction=proposal.instructions_markdown,
                reference_document_ids=(),
                allow_clarification=False,
                authoring_mode="regenerate",
            )
        match = TITLE_REVISION_PATTERN.search(request.message.strip())
        if match is not None:
            name = validate_skill_name(match.group(1) or match.group(2) or "")
            issues = inspect_skill_instructions(name)
            if issues:
                return SkillAuthoringResult(status="blocked", issues=issues)
            return SkillAuthoringResult(
                status="proposal_ready",
                proposal=SkillAuthoringProposal(
                    workspace_id=request.workspace_id or "",
                    user_id=request.user_id or "",
                    scope_type=proposal.scope_type,
                    name=name,
                    description=proposal.description,
                    instructions_markdown=proposal.instructions_markdown,
                ),
            )
        scope_type = _scope_from_text(request.message)
        if scope_type is not None:
            return SkillAuthoringResult(
                status="proposal_ready",
                proposal=SkillAuthoringProposal(
                    workspace_id=request.workspace_id or "",
                    user_id=request.user_id or "",
                    scope_type=scope_type,
                    name=proposal.name,
                    description=proposal.description,
                    instructions_markdown=proposal.instructions_markdown,
                ),
            )
        raise ValueError("현재 제안은 게시, 재생성, 보안 재검토, 커맨드·범위 변경을 지원합니다.")

    def _prepare_skill_selection(self, request: AgentTurnRequest) -> PreparedSkillSelection:
        if self._skill_selector is not None:
            return self._skill_selector.prepare(request)
        return PreparedSkillSelection(request=request, skills=())

def _to_query_conversation_context(request: AgentTurnRequest) -> ConversationContext | None:
    if request.conversation_context is None:
        return None
    if (
        request.conversation_context.recent_conversation_summary is None
        and not request.conversation_context.recent_messages
        and not request.conversation_context.reference_context
    ):
        return None
    return ConversationContext(
        recent_conversation_summary=request.conversation_context.recent_conversation_summary,
        recent_messages=request.conversation_context.recent_messages,
        reference_context=request.conversation_context.reference_context,
    )


def _whole_document_target(markdown: str) -> MarkdownEditTarget:
    return MarkdownEditTarget(
        type="whole_document",
        start_line=1,
        end_line=max(1, markdown_line_count(markdown)),
    )


def _skill_authoring_instruction(request: AgentTurnRequest) -> str:
    context = _conversation_context_text(request)
    return f"{context}\n\n사용자의 현재 답변:\n{request.message}" if context else request.message


def _conversation_context_text(request: AgentTurnRequest) -> str | None:
    context = _to_query_conversation_context(request)
    if context is None:
        return None
    sections = [
        context.recent_conversation_summary or "",
        conversation_messages_text(context),
    ]
    text = "\n".join(section.strip() for section in sections if section.strip())
    return text or None


def _skill_scope_type(request: AgentTurnRequest) -> SkillScopeType | None:
    return request.skill_scope_type or _scope_from_text(request.message)


def _scope_from_text(message: str) -> SkillScopeType | None:
    personal = re.search(r"(?:개인|personal)(?:\s*스킬)?", message, re.IGNORECASE) is not None
    team = re.search(r"(?:팀|현재\s*워크스페이스|team)(?:\s*스킬)?", message, re.IGNORECASE) is not None
    if personal == team:
        return None
    return "personal" if personal else "team"


def _skill_authoring_message(result: SkillAuthoringResult) -> str:
    if result.status == "published" and result.proposal is not None:
        return f"게시했습니다: /{result.proposal.name}"
    return "Skill 제안을 만들었습니다. Markdown과 보안 결과를 확인한 뒤 게시해 주세요."


def _skill_instructions(skill: Skill | None) -> str | None:
    if skill is None or skill.enabled_version is None:
        return None
    return skill.enabled_version.instructions_markdown
