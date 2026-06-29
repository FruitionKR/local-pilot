from typing import Any, Literal

from pydantic import BaseModel, Field

from app.modules.agent.domain.entities import (
    ActiveMarkdownContext,
    AgentConversationContext,
    AgentTurnRequest,
)
from app.modules.markdown_edit.domain.entities import MarkdownEditTarget
from app.modules.query.interfaces.http.schemas import QueryResponse


class MarkdownEditTargetRequest(BaseModel):
    type: Literal["selection", "current_section", "whole_document"]
    start_line: int = Field(..., ge=1)
    end_line: int = Field(..., ge=1)

    def to_domain(self) -> MarkdownEditTarget:
        return MarkdownEditTarget(
            type=self.type,
            start_line=self.start_line,
            end_line=self.end_line,
        )


class ActiveMarkdownContextRequest(BaseModel):
    markdown: str
    target: MarkdownEditTargetRequest | None = None
    document_kind: str | None = None

    def to_domain(self) -> ActiveMarkdownContext:
        return ActiveMarkdownContext(
            markdown=self.markdown,
            target=self.target.to_domain() if self.target else None,
            document_kind=self.document_kind,
        )


class AgentConversationContextRequest(BaseModel):
    recent_conversation_summary: str | None = None
    reference_context: dict[str, Any] | None = None

    def to_domain(self) -> AgentConversationContext:
        return AgentConversationContext(
            recent_conversation_summary=self.recent_conversation_summary,
            reference_context=self.reference_context or {},
        )


class AgentTurnRequestBody(BaseModel):
    message: str = Field(..., min_length=1)
    conversation_context: AgentConversationContextRequest | None = None
    active_markdown_context: ActiveMarkdownContextRequest | None = None

    def to_domain(self) -> AgentTurnRequest:
        return AgentTurnRequest(
            message=self.message,
            conversation_context=self.conversation_context.to_domain() if self.conversation_context else None,
            active_markdown_context=self.active_markdown_context.to_domain() if self.active_markdown_context else None,
        )


class AgentTurnRouteResponse(BaseModel):
    action: Literal["chat_answer", "markdown_edit", "markdown_create", "clarify", "reject"]
    confidence: float
    reason: str
    edit_goal: str | None = None


class MarkdownEditTargetResponse(BaseModel):
    type: Literal["selection", "current_section", "whole_document"]
    start_line: int
    end_line: int


class MarkdownEditOperationResponse(BaseModel):
    operation: Literal["replace"]
    target: MarkdownEditTargetResponse
    summary: str
    replacement_markdown: str


class GeneratedMarkdownResponse(BaseModel):
    title: str
    summary: str
    markdown: str


class AgentTurnResponse(BaseModel):
    action: Literal["chat_answer", "markdown_edit", "markdown_create", "clarify", "reject"]
    route: AgentTurnRouteResponse
    message: str | None = None
    chat: QueryResponse | None = None
    edit: MarkdownEditOperationResponse | None = None
    generated_markdown: GeneratedMarkdownResponse | None = None
