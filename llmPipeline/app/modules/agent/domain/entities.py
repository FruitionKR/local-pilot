from dataclasses import dataclass, field
from typing import Literal

from app.modules.markdown_edit.domain.entities import (
    GeneratedMarkdownDocument,
    MarkdownEditOperation,
    MarkdownEditTarget,
)
from app.modules.query.domain.entities import QueryAnswer
from app.modules.skill.domain.entities import (
    SkillAuthoringResult,
    SkillDraftProposal,
    SkillDraftSourceRun,
    SkillScopeType,
)


AgentAction = Literal[
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
SkillMode = Literal["auto", "explicit", "off"]


@dataclass(frozen=True)
class SkillCandidate:
    id: str
    version_id: str
    name: str
    description: str
    capabilities: tuple[str, ...]


@dataclass(frozen=True)
class ActiveMarkdownContext:
    markdown: str
    target: MarkdownEditTarget | None = None


@dataclass(frozen=True)
class AgentConversationContext:
    recent_conversation_summary: str | None = None
    reference_context: dict[str, object] = field(default_factory=dict)


@dataclass(frozen=True)
class AgentTurnRequest:
    message: str
    workspace_id: str | None = None
    user_id: str | None = None
    conversation_context: AgentConversationContext | None = None
    active_markdown_context: ActiveMarkdownContext | None = None
    skill_mode: SkillMode = "auto"
    skill_id: str | None = None
    available_skills: tuple[SkillCandidate, ...] = ()
    skill_draft_sources: tuple[SkillDraftSourceRun, ...] = ()
    skill_draft_user_directives: tuple[str, ...] = ()
    skill_draft_excluded_literals: tuple[str, ...] = ()
    skill_scope_type: SkillScopeType = "personal"
    skill_reference_document_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class AgentTurnRoute:
    action: AgentAction
    confidence: float
    reason: str
    edit_goal: str | None = None
    selected_skill_id: str | None = None
    skill_candidates: tuple[str, ...] = ()


@dataclass(frozen=True)
class AgentTurnResult:
    action: AgentAction
    route: AgentTurnRoute
    query_answer: QueryAnswer | None = None
    edit: MarkdownEditOperation | None = None
    generated_markdown: GeneratedMarkdownDocument | None = None
    message: str | None = None
    skill_candidates: tuple[SkillCandidate, ...] = ()
    run_id: str | None = None
    run_status: str | None = None
    skill_authoring_result: SkillAuthoringResult | None = None
    skill_draft_proposal: SkillDraftProposal | None = None
