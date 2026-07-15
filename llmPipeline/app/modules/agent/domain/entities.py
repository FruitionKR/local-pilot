from dataclasses import dataclass, field
from typing import Literal

from app.modules.markdown_edit.domain.entities import (
    GeneratedMarkdownDocument,
    MarkdownEditOperation,
    MarkdownEditTarget,
)
from app.modules.query.domain.entities import QueryAnswer


AgentAction = Literal["chat_answer", "markdown_edit", "markdown_create", "clarify", "reject"]


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
    conversation_context: AgentConversationContext | None = None
    active_markdown_context: ActiveMarkdownContext | None = None


@dataclass(frozen=True)
class AgentTurnRoute:
    action: AgentAction
    confidence: float
    reason: str
    edit_goal: str | None = None


@dataclass(frozen=True)
class AgentTurnResult:
    action: AgentAction
    route: AgentTurnRoute
    query_answer: QueryAnswer | None = None
    edit: MarkdownEditOperation | None = None
    generated_markdown: GeneratedMarkdownDocument | None = None
    message: str | None = None
