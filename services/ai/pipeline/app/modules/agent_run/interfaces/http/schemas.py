from pydantic import BaseModel, Field

from app.modules.agent_run.domain.entities import AgentRun
from app.modules.agent_run.domain.plan import AgentPlan, AgentPlanOperation


class AgentRunActorRequest(BaseModel):
    workspace_id: str = Field(..., min_length=1)
    user_id: str = Field(..., min_length=1)


class MarkdownAgentRunStatusResponse(BaseModel):
    id: str
    document_id: str
    base_version: int
    apply_operation_id: str
    status: str
    result: dict[str, object] | None
    error_code: str | None


class ApproveAgentPlanRequest(AgentRunActorRequest):
    plan_version: int = Field(..., ge=1)
    operation_hash: str = Field(..., min_length=64, max_length=64)


class ReviseAgentPlanRequest(AgentRunActorRequest):
    instruction: str = Field(..., min_length=1, max_length=1000)


class AgentToolReadAuthorizationRequest(AgentRunActorRequest):
    run_id: str = Field(..., min_length=1)


class AgentToolExecuteAuthorizationRequest(AgentToolReadAuthorizationRequest):
    plan_id: str = Field(..., min_length=1)
    plan_version: int = Field(..., ge=1)
    operation_hash: str = Field(..., min_length=64, max_length=64)
    operation_id: str = Field(..., min_length=1)
    tool_name: str = Field(..., min_length=1)
    arguments: dict[str, object]


class AgentPlanOperationResponse(BaseModel):
    id: str
    sequence: int
    tool_name: str
    target_type: str
    target_id: str | None
    base_version: int | None
    source_parent_id: str | None
    destination_parent_id: str | None
    arguments: dict[str, object]
    reason: str
    depends_on: list[str]
    status: str
    error_code: str | None

    @classmethod
    def from_domain(cls, operation: AgentPlanOperation) -> "AgentPlanOperationResponse":
        return cls(
            id=operation.id,
            sequence=operation.sequence,
            tool_name=operation.tool_name,
            target_type=operation.target_type,
            target_id=operation.target_id,
            base_version=operation.base_version,
            source_parent_id=operation.source_parent_id,
            destination_parent_id=operation.destination_parent_id,
            arguments=operation.arguments,
            reason=operation.reason,
            depends_on=list(operation.depends_on),
            status=operation.status,
            error_code=operation.error_code,
        )


class AgentPlanResponse(BaseModel):
    id: str
    version: int
    summary: str
    operation_hash: str
    status: str
    operations: list[AgentPlanOperationResponse]

    @classmethod
    def from_domain(cls, plan: AgentPlan) -> "AgentPlanResponse":
        return cls(
            id=plan.id,
            version=plan.version,
            summary=plan.summary,
            operation_hash=plan.operation_hash,
            status=plan.status,
            operations=[AgentPlanOperationResponse.from_domain(operation) for operation in plan.operations],
        )


class AgentRunResponse(BaseModel):
    id: str
    workspace_id: str
    action: str
    skill_version_id: str | None
    status: str
    request_summary: str
    error_code: str | None
    plan: AgentPlanResponse | None = None

    @classmethod
    def from_domain(cls, run: AgentRun, plan: AgentPlan | None = None) -> "AgentRunResponse":
        return cls(
            id=run.id,
            workspace_id=run.workspace_id,
            action=run.action,
            skill_version_id=run.skill_version_id,
            status=run.status,
            request_summary=run.request_summary,
            error_code=run.error_code,
            plan=AgentPlanResponse.from_domain(plan) if plan else None,
        )
