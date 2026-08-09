from typing import Any, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.core.untrusted_input import validate_untrusted_payload
from app.modules.agent.domain.entities import (
    ActiveMarkdownContext,
    AgentConversationContext,
    AgentTurnRequest,
    PendingSkillProposal,
)
from app.modules.markdown_edit.domain.entities import MarkdownEditTarget
from app.modules.query.interfaces.http.schemas import QueryResponse
from app.modules.skill.domain.entities import Skill, SkillVersion
from app.modules.skill.interfaces.http.schemas import (
    CapabilityValue,
    SkillAuthoringResponse,
    SkillDraftSourceRunRequest,
    ToolValue,
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


class PendingSkillProposalRequest(BaseModel):
    scope_type: Literal["personal", "team"]
    name: str = Field(..., min_length=1, max_length=63, pattern=r"^[a-z0-9][a-z0-9-]{0,62}$")
    description: str = Field(..., min_length=1, max_length=500)
    instructions_markdown: str = Field(..., min_length=1, max_length=30_000)

    def to_domain(self) -> PendingSkillProposal:
        return PendingSkillProposal(
            scope_type=self.scope_type,
            name=self.name,
            description=self.description,
            instructions_markdown=self.instructions_markdown,
        )


class AgentConversationContextRequest(BaseModel):
    recent_conversation_summary: str | None = None
    reference_context: dict[str, Any] | None = None
    pending_skill_proposal: PendingSkillProposalRequest | None = None

    def to_domain(self) -> AgentConversationContext:
        return AgentConversationContext(
            recent_conversation_summary=self.recent_conversation_summary,
            reference_context=self.reference_context or {},
            pending_skill_proposal=(
                self.pending_skill_proposal.to_domain() if self.pending_skill_proposal else None
            ),
        )


class SkillExecutionReferenceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(..., min_length=1)
    name: str = Field(..., min_length=1)
    content_hash: str = Field(..., min_length=1)


class SkillExecutionDefinitionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    skill_id: str = Field(..., min_length=1)
    version_id: str = Field(..., min_length=1)
    command: str = Field(default="", pattern=r"^$|^[a-z0-9][a-z0-9-]{0,62}$")
    name: str = Field(..., min_length=1, max_length=63)
    description: str = Field(..., min_length=1, max_length=500)
    instructions_markdown: str = Field(..., min_length=1, max_length=30_000)
    capabilities: list[CapabilityValue] = Field(..., min_length=1)
    allowed_tools: list[ToolValue] = Field(default_factory=list)
    reference_documents: list[SkillExecutionReferenceRequest] = Field(default_factory=list, max_length=3)

    def to_domain(self, workspace_id: str | None) -> Skill:
        version = SkillVersion(
            id=self.version_id,
            skill_id=self.skill_id,
            version=0,
            name=self.name,
            description=self.description,
            instructions_markdown=self.instructions_markdown,
            capabilities=tuple(self.capabilities),
            allowed_tools=tuple(self.allowed_tools),
            status="published",
        )
        return Skill(
            id=self.skill_id,
            workspace_id=workspace_id,
            scope_type="team",
            owner_user_id=None,
            slug=self.command,
            status="enabled",
            enabled_version=version,
            latest_version=version,
        )


class AgentTurnRequestBody(BaseModel):
    message: str = Field(..., min_length=1, max_length=MAX_AGENT_MESSAGE_LENGTH)
    workspace_id: str | None = Field(default=None, min_length=1)
    user_id: str | None = Field(default=None, min_length=1)
    output_language: Literal["ko", "en", "document"] | None = None
    response_length: Literal["concise", "balanced", "detailed"] | None = None
    allow_web_search: bool | None = None
    conversation_context: AgentConversationContextRequest | None = None
    active_markdown_context: ActiveMarkdownContextRequest | None = None
    skill_mode: Literal["auto", "explicit", "off"] = "auto"
    skill_id: str | None = Field(default=None, min_length=1)
    selected_skill: SkillExecutionDefinitionRequest | None = None
    skill_candidates: list[SkillExecutionDefinitionRequest] = Field(default_factory=list, max_length=20)
    skill_draft_sources: list[SkillDraftSourceRunRequest] = Field(default_factory=list)
    skill_draft_user_directives: list[str] = Field(default_factory=list)
    skill_draft_excluded_literals: list[str] = Field(default_factory=list)
    skill_scope_type: Literal["personal", "team"] | None = None
    skill_authoring_mode: Literal["preserve", "enhance"] = "enhance"
    skill_reference_document_ids: list[str] = Field(default_factory=list, max_length=3)

    @model_validator(mode="after")
    def validate_untrusted_input(self) -> Self:
        validate_untrusted_payload(self.model_dump(mode="json"))
        if self.selected_skill is not None and self.skill_candidates:
            raise ValueError("selected_skill and skill_candidates cannot be used together.")
        if self.selected_skill is not None and self.skill_mode != "explicit":
            raise ValueError("selected_skill requires explicit skill_mode.")
        if self.skill_candidates and self.skill_mode != "auto":
            raise ValueError("skill_candidates require auto skill_mode.")
        if self.selected_skill is not None and self.skill_id not in {None, self.selected_skill.skill_id}:
            raise ValueError("skill_id must match selected_skill.skill_id.")
        return self

    def to_domain(self) -> AgentTurnRequest:
        skill_definitions = None
        skill_id = self.skill_id
        if self.selected_skill is not None:
            skill_definitions = (self.selected_skill.to_domain(self.workspace_id),)
            skill_id = skill_id or self.selected_skill.skill_id
        elif "skill_candidates" in self.model_fields_set:
            skill_definitions = tuple(
                definition.to_domain(self.workspace_id) for definition in self.skill_candidates
            )
        return AgentTurnRequest(
            message=self.message,
            workspace_id=self.workspace_id,
            user_id=self.user_id,
            output_language=self.output_language,
            response_length=self.response_length,
            allow_web_search=self.allow_web_search,
            conversation_context=self.conversation_context.to_domain() if self.conversation_context else None,
            active_markdown_context=self.active_markdown_context.to_domain() if self.active_markdown_context else None,
            skill_mode=self.skill_mode,
            skill_id=skill_id,
            skill_definitions=skill_definitions,
            skill_draft_sources=tuple(source.to_domain() for source in self.skill_draft_sources),
            skill_draft_user_directives=tuple(self.skill_draft_user_directives),
            skill_draft_excluded_literals=tuple(self.skill_draft_excluded_literals),
            skill_scope_type=self.skill_scope_type,
            skill_authoring_mode=self.skill_authoring_mode,
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
