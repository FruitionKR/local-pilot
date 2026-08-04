from dataclasses import dataclass
from datetime import datetime
from typing import Literal


SkillCapability = Literal["document-create", "document-edit", "folder-organize", "template"]
SkillTool = Literal[
    "list_root_items",
    "list_folder_children",
    "search_hierarchy",
    "get_breadcrumb",
    "get_document_metadata",
    "create_folder",
    "rename_folder",
    "move_folder",
    "move_document",
    "rename_document",
]
SkillScopeType = Literal["personal", "team"]
SkillStatus = Literal["enabled", "disabled"]
SkillVersionStatus = Literal["draft", "published", "rejected"]


@dataclass(frozen=True)
class SkillVersion:
    id: str
    skill_id: str
    version: int
    name: str
    description: str
    instructions_markdown: str
    capabilities: tuple[SkillCapability, ...]
    allowed_tools: tuple[SkillTool, ...] = ()
    status: SkillVersionStatus = "draft"
    lint_result: dict[str, object] | None = None
    created_by: str | None = None
    created_at: datetime | None = None
    published_at: datetime | None = None


@dataclass(frozen=True)
class Skill:
    id: str
    workspace_id: str
    scope_type: SkillScopeType
    owner_user_id: str | None
    slug: str
    status: SkillStatus
    enabled_version: SkillVersion | None = None
    latest_version: SkillVersion | None = None
    created_at: datetime | None = None
    updated_at: datetime | None = None
