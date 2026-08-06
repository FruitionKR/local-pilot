from typing import Any, Literal, Self

from pydantic import BaseModel, Field, model_validator

from app.core.untrusted_input import validate_untrusted_payload
from app.modules.agent.domain.entities import (
    ActiveMarkdownContext,
    AgentConversationContext,
    AgentTurnRequest,
)
from app.modules.markdown_edit.domain.entities import MarkdownEditTarget
from app.modules.query.interfaces.http.schemas import QueryResponse
from app.modules.skill.interfaces.http.schemas import (
    SkillAuthoringResponse,
    SkillDraftProposalResponse,
    SkillDraftSourceRunRequest,
)


MAX_AGENT_MESSAGE_LENGTH = 1000


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

    def to_domain(self) -> ActiveMarkdownContext:
        return ActiveMarkdownContext(
            markdown=self.markdown,
            target=self.target.to_domain() if self.target else None,
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
    message: str = Field(..., min_length=1, max_length=MAX_AGENT_MESSAGE_LENGTH)
    workspace_id: str | None = Field(default=None, min_length=1)
    user_id: str | None = Field(default=None, min_length=1)
    conversation_context: AgentConversationContextRequest | None = None
    active_markdown_context: ActiveMarkdownContextRequest | None = None
    skill_mode: Literal["auto", "explicit", "off"] = "auto"
    skill_id: str | None = Field(default=None, min_length=1)
    skill_draft_sources: list[SkillDraftSourceRunRequest] = Field(default_factory=list)
    skill_draft_user_directives: list[str] = Field(default_factory=list)
    skill_draft_excluded_literals: list[str] = Field(default_factory=list)
    skill_scope_type: Literal["personal", "team"] = "personal"
    skill_reference_document_ids: list[str] = Field(default_factory=list, max_length=3)

    @model_validator(mode="after")
    def validate_untrusted_input(self) -> Self:
        validate_untrusted_payload(self.model_dump(mode="json"))
        return self

    def to_domain(self) -> AgentTurnRequest:
        return AgentTurnRequest(
            message=self.message,
            workspace_id=self.workspace_id,
            user_id=self.user_id,
            conversation_context=self.conversation_context.to_domain() if self.conversation_context else None,
            active_markdown_context=self.active_markdown_context.to_domain() if self.active_markdown_context else None,
            skill_mode=self.skill_mode,
            skill_id=self.skill_id,
            skill_draft_sources=tuple(source.to_domain() for source in self.skill_draft_sources),
            skill_draft_user_directives=tuple(self.skill_draft_user_directives),
            skill_draft_excluded_literals=tuple(self.skill_draft_excluded_literals),
            skill_scope_type=self.skill_scope_type,
            skill_reference_document_ids=tuple(self.skill_reference_document_ids),
        )


class AgentTurnRouteResponse(BaseModel):
    action: Literal[
        "chat_answer",
        "markdown_edit",
        "markdown_create",
        "folder_organize",
        "workspace_workflow",
        "skill_authoring",
        "skill_draft_proposal",
        "clarify",
        "reject",
    ]
    confidence: float
    reason: str
    edit_goal: str | None = None
    selected_skill_id: str | None = None
    skill_candidates: list[str] = Field(default_factory=list)


class SkillCandidateResponse(BaseModel):
    id: str
    version_id: str
    name: str
    description: str
    capabilities: list[str]


class MarkdownEditTargetResponse(BaseModel):
    type: Literal["selection", "current_section", "whole_document"]
    start_line: int
    end_line: int


class MarkdownEditOperationResponse(BaseModel):
    operation: Literal["replace", "insert_after"]
    requested_target: MarkdownEditTargetResponse
    actual_target: MarkdownEditTargetResponse
    scope_expanded: bool
    changed: bool
    summary: str
    replacement_markdown: str


class GeneratedMarkdownResponse(BaseModel):
    title: str
    summary: str
    markdown: str


class AgentTurnResponse(BaseModel):
    action: Literal[
        "chat_answer",
        "markdown_edit",
        "markdown_create",
        "folder_organize",
        "workspace_workflow",
        "skill_authoring",
        "skill_draft_proposal",
        "clarify",
        "reject",
    ]
    route: AgentTurnRouteResponse
    message: str | None = None
    chat: QueryResponse | None = None
    edit: MarkdownEditOperationResponse | None = None
    generated_markdown: GeneratedMarkdownResponse | None = None
    skill_candidates: list[SkillCandidateResponse] = Field(default_factory=list)
    run_id: str | None = None
    run_status: str | None = None
    skill_authoring: SkillAuthoringResponse | None = None
    skill_draft_proposal: SkillDraftProposalResponse | None = None
