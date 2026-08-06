import json
from typing import Literal

from pydantic import BaseModel, Field

from app.modules.skill.domain.entities import (
    Skill,
    SkillAuthoringResult,
    SkillDraftProposal,
    SkillDraftSourceOperation,
    SkillDraftSourceRun,
    SkillVersion,
)


CapabilityValue = Literal["document-create", "document-edit", "folder-organize", "template"]
ToolValue = Literal[
    "list_root_items",
    "list_folder_children",
    "search_hierarchy",
    "get_breadcrumb",
    "get_document_metadata",
    "get_document_content",
    "create_folder",
    "rename_folder",
    "move_folder",
    "move_document",
    "rename_document",
    "create_document",
    "apply_document_edit",
]


class SkillDefinitionRequest(BaseModel):
    user_id: str = Field(..., min_length=1)
    name: str = Field(..., min_length=1)
    description: str = Field(..., min_length=1)
    instructions_markdown: str = Field(..., min_length=1)
    capabilities: list[CapabilityValue] = Field(..., min_length=1)
    allowed_tools: list[ToolValue] = Field(default_factory=list)


class SkillAuthoringRequest(BaseModel):
    workspace_id: str = Field(..., min_length=1)
    user_id: str = Field(..., min_length=1)
    scope_type: Literal["personal", "team"]
    instruction: str = Field(..., min_length=1, max_length=4_000)
    reference_document_ids: list[str] = Field(default_factory=list, max_length=3)


class SkillAuthoringResponse(BaseModel):
    status: Literal["clarification_required", "draft_created"]
    question: str | None = None
    skill_id: str | None = None
    version_id: str | None = None
    skill_markdown: str | None = None

    @classmethod
    def from_domain(cls, result: SkillAuthoringResult) -> "SkillAuthoringResponse":
        if result.skill is None:
            return cls(status=result.status, question=result.question)
        version = result.skill.latest_version
        if version is None:
            raise ValueError("Authored Skill draft version is missing.")
        return cls(
            status=result.status,
            skill_id=result.skill.id,
            version_id=version.id,
            skill_markdown=(
                "---\n"
                f"name: {json.dumps(version.name, ensure_ascii=False)}\n"
                f"description: {json.dumps(version.description, ensure_ascii=False)}\n"
                "---\n\n"
                f"{version.instructions_markdown}"
            ),
        )


class CreateSkillRequest(SkillDefinitionRequest):
    workspace_id: str = Field(..., min_length=1)
    scope_type: Literal["personal", "team"]
    slug: str = Field(..., min_length=1, max_length=63)


class UpdateSkillRequest(SkillDefinitionRequest):
    workspace_id: str = Field(..., min_length=1)


class SkillActorRequest(BaseModel):
    workspace_id: str = Field(..., min_length=1)
    user_id: str = Field(..., min_length=1)
    version_id: str | None = Field(default=None, min_length=1)


class SkillVersionResponse(BaseModel):
    id: str
    version: int
    name: str
    description: str
    instructions_markdown: str
    capabilities: list[str]
    allowed_tools: list[str]
    lint_result: dict[str, object]
    status: str

    @classmethod
    def from_domain(cls, version: SkillVersion) -> "SkillVersionResponse":
        return cls(
            id=version.id,
            version=version.version,
            name=version.name,
            description=version.description,
            instructions_markdown=version.instructions_markdown,
            capabilities=list(version.capabilities),
            allowed_tools=list(version.allowed_tools),
            lint_result=version.lint_result or {},
            status=version.status,
        )


class SkillResponse(BaseModel):
    id: str
    workspace_id: str
    scope_type: str
    owner_user_id: str | None
    slug: str
    status: str
    enabled_version: SkillVersionResponse | None
    latest_version: SkillVersionResponse | None

    @classmethod
    def from_domain(cls, skill: Skill) -> "SkillResponse":
        return cls(
            id=skill.id,
            workspace_id=skill.workspace_id,
            scope_type=skill.scope_type,
            owner_user_id=skill.owner_user_id,
            slug=skill.slug,
            status=skill.status,
            enabled_version=(SkillVersionResponse.from_domain(skill.enabled_version) if skill.enabled_version else None),
            latest_version=(SkillVersionResponse.from_domain(skill.latest_version) if skill.latest_version else None),
        )


class SkillPreviewResponse(BaseModel):
    lint_result: dict[str, object]
    has_blocked_issues: bool

    @classmethod
    def from_domain(cls, version: SkillVersion) -> "SkillPreviewResponse":
        issues = (version.lint_result or {}).get("issues", [])
        return cls(
            lint_result=version.lint_result or {},
            has_blocked_issues=any(
                isinstance(issue, dict) and issue.get("severity") == "blocked" for issue in issues
            ),
        )


class SkillDraftSourceOperationRequest(BaseModel):
    tool_name: ToolValue
    reason: str = Field(..., min_length=1)

    def to_domain(self) -> SkillDraftSourceOperation:
        return SkillDraftSourceOperation(tool_name=self.tool_name, reason=self.reason)


class SkillDraftSourceRunRequest(BaseModel):
    run_id: str = Field(..., min_length=1)
    status: Literal["completed"]
    request_summary: str = Field(..., min_length=1)
    plan_summary: str = Field(..., min_length=1)
    successful_operations: list[SkillDraftSourceOperationRequest] = Field(..., min_length=1)

    def to_domain(self) -> SkillDraftSourceRun:
        return SkillDraftSourceRun(
            run_id=self.run_id,
            status=self.status,
            request_summary=self.request_summary,
            plan_summary=self.plan_summary,
            successful_operations=tuple(operation.to_domain() for operation in self.successful_operations),
        )


class SkillDraftProposalRequest(BaseModel):
    source_runs: list[SkillDraftSourceRunRequest] = Field(..., min_length=1)
    user_directives: list[str] = Field(default_factory=list)
    excluded_literals: list[str] = Field(default_factory=list)


class SkillDraftProposalResponse(BaseModel):
    name: str
    description: str
    instructions_markdown: str
    capabilities: list[str]
    allowed_tools: list[str]
    source_run_ids: list[str]
    persisted: bool

    @classmethod
    def from_domain(cls, proposal: SkillDraftProposal) -> "SkillDraftProposalResponse":
        return cls(
            name=proposal.name,
            description=proposal.description,
            instructions_markdown=proposal.instructions_markdown,
            capabilities=list(proposal.capabilities),
            allowed_tools=list(proposal.allowed_tools),
            source_run_ids=list(proposal.source_run_ids),
            persisted=proposal.persisted,
        )
