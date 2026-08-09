import json
import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.modules.skill.domain.entities import (
    Skill,
    SkillAuthoringReference,
    SkillAuthoringResult,
    SkillDefinitionDraft,
    SkillDefinitionResult,
    SkillDraftSourceOperation,
    SkillDraftSourceRun,
    SkillVersion,
    SkillReviewResult,
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


class SkillReferenceDocumentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(..., min_length=1)
    name: str = Field(..., min_length=1, max_length=255)
    content_hash: str = Field(..., min_length=1, max_length=128)
    content: str = Field(..., min_length=1, max_length=40_000)

    def to_domain(self) -> SkillAuthoringReference:
        return SkillAuthoringReference(id=self.id, name=self.name, markdown=self.content)


class SkillDefinitionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workspace_id: str | None = Field(default=None, min_length=1)
    user_id: str = Field(..., min_length=1)
    command: str = Field(default="", pattern=r"^$|^[a-z0-9][a-z0-9-]{0,62}$")
    name: str = Field(..., min_length=1, max_length=63)
    description: str = Field(default="", max_length=500)
    instructions_markdown: str = Field(..., min_length=1, max_length=30_000)
    scope_type: Literal["personal", "team"] = "personal"
    capabilities: list[CapabilityValue] = Field(default_factory=list)
    allowed_tools: list[ToolValue] = Field(default_factory=list)
    reference_documents: list[SkillReferenceDocumentRequest] = Field(default_factory=list, max_length=3)

    @property
    def normalized_command(self) -> str:
        if self.command:
            return self.command
        return self.name if re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", self.name) else ""

    def references(self) -> tuple[SkillAuthoringReference, ...]:
        return tuple(reference.to_domain() for reference in self.reference_documents)


class SkillAuthoringRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workspace_id: str = Field(..., min_length=1)
    user_id: str = Field(..., min_length=1)
    scope_type: Literal["personal", "team"]
    name: str | None = Field(
        default=None,
        min_length=1,
        max_length=63,
        pattern=r"^[a-z0-9][a-z0-9-]{0,62}$",
    )
    description: str | None = Field(default=None, min_length=1, max_length=500)
    instruction: str = Field(..., min_length=1, max_length=30_000)
    authoring_mode: Literal["preserve", "enhance", "regenerate"] = "enhance"
    reference_document_ids: list[str] = Field(default_factory=list, max_length=3)


class SkillAuthoringResponse(BaseModel):
    status: Literal["clarification_required", "blocked", "proposal_ready", "published"]
    question: str | None = None
    skill_id: str | None = None
    version_id: str | None = None
    scope_type: Literal["personal", "team"] | None = None
    name: str | None = None
    description: str | None = None
    skill_markdown: str | None = None
    issues: list[dict[str, object]] = Field(default_factory=list)

    @classmethod
    def from_domain(cls, result: SkillAuthoringResult) -> "SkillAuthoringResponse":
        if result.proposal is not None:
            proposal = result.proposal
            skill = result.skill
            version = skill.enabled_version if skill else None
            return cls(
                status=result.status,
                skill_id=skill.id if skill else None,
                version_id=version.id if version else None,
                scope_type=proposal.scope_type,
                name=proposal.name,
                description=proposal.description,
                skill_markdown=_skill_markdown(
                    proposal.name,
                    proposal.description,
                    proposal.instructions_markdown,
                ),
                issues=[issue.__dict__ for issue in result.issues],
            )
        if result.skill is None:
            return cls(
                status=result.status,
                question=result.question,
                issues=[issue.__dict__ for issue in result.issues],
            )
        version = result.skill.enabled_version or result.skill.latest_version
        if version is None:
            raise ValueError("Authored Skill version is missing.")
        return cls(
            status=result.status,
            skill_id=result.skill.id,
            version_id=version.id,
            scope_type=result.skill.scope_type,
            name=version.name,
            description=version.description,
            skill_markdown=_skill_markdown(version.name, version.description, version.instructions_markdown),
        )


class PublishAuthoredSkillRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workspace_id: str = Field(..., min_length=1)
    user_id: str = Field(..., min_length=1)
    scope_type: Literal["personal", "team"]
    name: str = Field(..., min_length=1, max_length=63, pattern=r"^[a-z0-9][a-z0-9-]{0,62}$")
    description: str = Field(..., min_length=1, max_length=500)
    instructions_markdown: str = Field(..., min_length=1, max_length=30_000)


class UpdateSkillRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workspace_id: str = Field(..., min_length=1)
    user_id: str = Field(..., min_length=1)
    name: str = Field(..., min_length=1, max_length=63, pattern=r"^[a-z0-9][a-z0-9-]{0,62}$")
    description: str = Field(..., min_length=1, max_length=500)
    instructions_markdown: str = Field(..., min_length=1, max_length=30_000)


class SkillActorRequest(BaseModel):
    workspace_id: str = Field(..., min_length=1)
    user_id: str = Field(..., min_length=1)


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
    workspace_id: str | None
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


class SkillDraftResponse(BaseModel):
    command: str
    name: str
    description: str
    instructions_markdown: str
    scope_type: Literal["personal", "team"]
    capabilities: list[str]
    allowed_tools: list[str]

    @classmethod
    def from_domain(cls, draft: SkillDefinitionDraft) -> "SkillDraftResponse":
        return cls(
            command=draft.command,
            name=draft.name,
            description=draft.description,
            instructions_markdown=draft.instructions_markdown,
            scope_type=draft.scope_type,
            capabilities=list(draft.capabilities),
            allowed_tools=list(draft.allowed_tools),
        )


class SkillRefineResponse(BaseModel):
    draft: SkillDraftResponse | None
    persisted: Literal[False] = False
    issues: list[dict[str, object]] = Field(default_factory=list)

    @classmethod
    def from_domain(cls, result: SkillDefinitionResult) -> "SkillRefineResponse":
        return cls(
            draft=SkillDraftResponse.from_domain(result.draft) if result.draft else None,
            issues=[issue.__dict__ for issue in result.issues],
        )


class SkillCheckResponse(BaseModel):
    name: Literal["rules", "semantic"]
    passed: bool
    issues: list[str] = Field(default_factory=list)


class SkillPreviewResponse(BaseModel):
    draft: SkillDraftResponse
    checks: list[SkillCheckResponse]
    publish_allowed: bool
    lint_result: dict[str, object]
    has_blocked_issues: bool

    @classmethod
    def from_domain(cls, result: SkillReviewResult) -> "SkillPreviewResponse":
        issues = [issue.__dict__ for issue in result.issues]
        return cls(
            draft=SkillDraftResponse.from_domain(result.draft),
            checks=[
                SkillCheckResponse(name=check.name, passed=check.passed, issues=list(check.issues))
                for check in result.checks
            ],
            publish_allowed=result.publish_allowed,
            lint_result={"issues": issues},
            has_blocked_issues=not result.publish_allowed,
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
    workspace_id: str = Field(..., min_length=1)
    user_id: str = Field(..., min_length=1)
    scope_type: Literal["personal", "team"]
    source_runs: list[SkillDraftSourceRunRequest] = Field(..., min_length=1)
    user_directives: list[str] = Field(default_factory=list)
    excluded_literals: list[str] = Field(default_factory=list)

def _skill_markdown(name: str, description: str, instructions_markdown: str) -> str:
    return (
        "---\n"
        f"name: {json.dumps(name, ensure_ascii=False)}\n"
        f"description: {json.dumps(description, ensure_ascii=False)}\n"
        "---\n\n"
        f"{instructions_markdown}"
    )
