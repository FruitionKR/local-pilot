from app.modules.agent.application.ports import AgentTurnRouterPort
from app.modules.agent.domain.entities import AgentTurnRequest, AgentTurnResult
from app.modules.markdown_edit.application.generate_markdown_document import GenerateMarkdownDocumentUseCase
from app.modules.markdown_edit.application.generate_markdown_edit import GenerateMarkdownEditUseCase
from app.modules.markdown_edit.domain.entities import MarkdownCreateRequest, MarkdownEditRequest
from app.modules.query.application.answer_query import AnswerQueryUseCase
from app.modules.query.domain.entities import ConversationContext


CLARIFY_MARKDOWN_TARGET_MESSAGE = "수정할 Markdown 범위를 선택한 뒤 다시 요청해 주세요."
DEFERRED_TEMPLATE_MESSAGE = "현재는 선택 영역이나 현재 섹션 단위 편집만 지원합니다. template 기반 전체 문서 재구성은 이후 단계에서 다루겠습니다."


class HandleAgentTurnUseCase:
    def __init__(
        self,
        router: AgentTurnRouterPort,
        query_use_case: AnswerQueryUseCase,
        markdown_edit_use_case: GenerateMarkdownEditUseCase,
        markdown_create_use_case: GenerateMarkdownDocumentUseCase,
    ) -> None:
        self._router = router
        self._query_use_case = query_use_case
        self._markdown_edit_use_case = markdown_edit_use_case
        self._markdown_create_use_case = markdown_create_use_case

    def execute(self, request: AgentTurnRequest) -> AgentTurnResult:
        if not request.message.strip():
            raise ValueError("message is required.")

        route = self._router.route(request)
        if route.action == "markdown_create":
            result = self._markdown_create_use_case.execute(
                MarkdownCreateRequest(
                    instruction=request.message,
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
                )
            )
            return AgentTurnResult(action="markdown_create", route=route, generated_markdown=result.document)

        if route.action == "markdown_edit":
            markdown_context = request.active_markdown_context
            if markdown_context is None or markdown_context.target is None or not markdown_context.markdown.strip():
                return AgentTurnResult(
                    action="clarify",
                    route=route,
                    message=CLARIFY_MARKDOWN_TARGET_MESSAGE,
                )
            result = self._markdown_edit_use_case.execute(
                MarkdownEditRequest(
                    instruction=request.message,
                    markdown=markdown_context.markdown,
                    target=markdown_context.target,
                    conversation_summary=(
                        request.conversation_context.recent_conversation_summary
                        if request.conversation_context
                        else None
                    ),
                    edit_goal=route.edit_goal,
                )
            )
            return AgentTurnResult(action="markdown_edit", route=route, edit=result.edit)

        if route.action == "clarify":
            message = DEFERRED_TEMPLATE_MESSAGE if route.edit_goal == "template_transform" else CLARIFY_MARKDOWN_TARGET_MESSAGE
            return AgentTurnResult(action="clarify", route=route, message=message)

        if route.action == "reject":
            return AgentTurnResult(
                action="reject",
                route=route,
                message="요청한 작업은 현재 지원 범위에서 처리할 수 없습니다.",
            )

        answer = self._query_use_case.execute(
            request.message,
            conversation_context=_to_query_conversation_context(request),
        )
        return AgentTurnResult(action="chat_answer", route=route, query_answer=answer)


def _to_query_conversation_context(request: AgentTurnRequest) -> ConversationContext | None:
    if request.conversation_context is None:
        return None
    if request.conversation_context.recent_conversation_summary is None and not request.conversation_context.reference_context:
        return None
    return ConversationContext(
        recent_conversation_summary=request.conversation_context.recent_conversation_summary,
        reference_context=request.conversation_context.reference_context,
    )
