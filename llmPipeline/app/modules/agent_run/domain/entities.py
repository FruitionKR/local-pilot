from dataclasses import dataclass
from datetime import datetime
from typing import Literal


AgentRunStatus = Literal[
    "queued",
    "planning",
    "clarification_required",
    "awaiting_approval",
    "executing",
    "verifying",
    "completed",
    "partial_failed",
    "failed",
    "conflicted",
    "rejected",
    "cancelled",
]


@dataclass(frozen=True)
class StartAgentRunRequest:
    workspace_id: str
    user_id: str
    instruction: str
    skill_version_id: str | None = None


@dataclass(frozen=True)
class AgentRun:
    id: str
    workspace_id: str
    user_id: str
    action: str
    skill_version_id: str | None
    status: AgentRunStatus
    request_summary: str
    current_plan_id: str | None = None
    error_code: str | None = None
    created_at: datetime | None = None
    updated_at: datetime | None = None
    finished_at: datetime | None = None


@dataclass(frozen=True)
class AgentJob:
    id: str
    run_id: str
    job_type: str
    attempt_count: int
    lease_token: str
    leased_until: datetime


@dataclass(frozen=True)
class AgentRunContext:
    run: AgentRun
    skill_instructions: str | None
    allowed_tools: tuple[str, ...]
