import re
from uuid import uuid4

from app.modules.skill.application.ports import ManageSkillRepositoryPort
from app.modules.skill.domain.entities import (
    Skill,
    SkillCapability,
    SkillScopeType,
    SkillTool,
    SkillVersion,
)
from app.modules.skill.domain.policy import CAPABILITY_TOOLS, validate_allowed_tools
from app.modules.skill.domain.safety import inspect_skill_instructions


SLUG_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{0,62}$")


class ManageSkillUseCase:
    def __init__(self, repository: ManageSkillRepositoryPort) -> None:
        self._repository = repository

    def preview(
        self,
        *,
        user_id: str,
        name: str,
        description: str,
        instructions_markdown: str,
        capabilities: tuple[SkillCapability, ...],
        allowed_tools: tuple[SkillTool, ...],
    ) -> SkillVersion:
        _validate_definition("preview", name, description, instructions_markdown, capabilities, allowed_tools)
        return _new_version(
            skill_id="preview",
            version=1,
            user_id=user_id,
            name=name,
            description=description,
            instructions_markdown=instructions_markdown,
            capabilities=capabilities,
            allowed_tools=allowed_tools,
        )

    def create_draft(
        self,
        *,
        workspace_id: str,
        user_id: str,
        scope_type: SkillScopeType,
        slug: str,
        name: str,
        description: str,
        instructions_markdown: str,
        capabilities: tuple[SkillCapability, ...],
        allowed_tools: tuple[SkillTool, ...],
    ) -> Skill:
        _validate_definition(slug, name, description, instructions_markdown, capabilities, allowed_tools)
        skill_id = str(uuid4())
        version = _new_version(
            skill_id=skill_id,
            version=1,
            user_id=user_id,
            name=name,
            description=description,
            instructions_markdown=instructions_markdown,
            capabilities=capabilities,
            allowed_tools=allowed_tools,
        )
        skill = Skill(
            id=skill_id,
            workspace_id=workspace_id,
            scope_type=scope_type,
            owner_user_id=user_id if scope_type == "personal" else None,
            slug=slug,
            status="disabled",
            latest_version=version,
        )
        return self._repository.create(skill, version)

    def create_draft_version(
        self,
        *,
        workspace_id: str,
        user_id: str,
        skill_id: str,
        name: str,
        description: str,
        instructions_markdown: str,
        capabilities: tuple[SkillCapability, ...],
        allowed_tools: tuple[SkillTool, ...],
    ) -> Skill:
        skill = self._require_manageable(workspace_id, user_id, skill_id)
        _validate_definition(
            skill.slug,
            name,
            description,
            instructions_markdown,
            capabilities,
            allowed_tools,
        )
        latest_version = skill.latest_version or skill.enabled_version
        next_version = (latest_version.version if latest_version else 0) + 1
        version = _new_version(
            skill_id=skill.id,
            version=next_version,
            user_id=user_id,
            name=name,
            description=description,
            instructions_markdown=instructions_markdown,
            capabilities=capabilities,
            allowed_tools=allowed_tools,
        )
        return self._repository.save_draft_version(skill, version)

    def publish(self, workspace_id: str, user_id: str, skill_id: str, version_id: str) -> Skill:
        skill = self._require_manageable(workspace_id, user_id, skill_id)
        version = skill.latest_version
        if version is None or version.id != version_id or version.status != "draft":
            raise ValueError("Draft Skill version not found.")
        if _blocked_issues(version):
            raise ValueError("Skill version has blocked safety issues.")
        return self._repository.publish(workspace_id, user_id, skill_id, version_id)

    def set_enabled(self, workspace_id: str, user_id: str, skill_id: str, enabled: bool) -> Skill:
        skill = self._require_manageable(workspace_id, user_id, skill_id)
        if enabled and skill.enabled_version is None:
            raise ValueError("Publish a Skill version before enabling it.")
        return self._repository.set_enabled(workspace_id, user_id, skill_id, enabled)

    def _require_manageable(self, workspace_id: str, user_id: str, skill_id: str) -> Skill:
        skill = self._repository.get_manageable(workspace_id, user_id, skill_id)
        if skill is None:
            raise ValueError("Skill not found or not manageable.")
        return skill


def _validate_definition(
    slug: str,
    name: str,
    description: str,
    instructions_markdown: str,
    capabilities: tuple[SkillCapability, ...],
    allowed_tools: tuple[SkillTool, ...],
) -> None:
    if not SLUG_PATTERN.fullmatch(slug):
        raise ValueError("slug must contain lowercase letters, numbers, or hyphens.")
    if not name.strip() or not description.strip() or not instructions_markdown.strip():
        raise ValueError("name, description, and instructions_markdown are required.")
    if not capabilities or any(capability not in CAPABILITY_TOOLS for capability in capabilities):
        raise ValueError("capabilities must contain supported values.")
    validate_allowed_tools(capabilities, allowed_tools)


def _new_version(
    *,
    skill_id: str,
    version: int,
    user_id: str,
    name: str,
    description: str,
    instructions_markdown: str,
    capabilities: tuple[SkillCapability, ...],
    allowed_tools: tuple[SkillTool, ...],
) -> SkillVersion:
    issues = inspect_skill_instructions(instructions_markdown)
    return SkillVersion(
        id=str(uuid4()),
        skill_id=skill_id,
        version=version,
        name=name.strip(),
        description=description.strip(),
        instructions_markdown=instructions_markdown.strip(),
        capabilities=capabilities,
        allowed_tools=allowed_tools,
        lint_result={"issues": [issue.__dict__ for issue in issues]},
        status="draft",
        created_by=user_id,
    )


def _blocked_issues(version: SkillVersion) -> list[object]:
    lint_result = version.lint_result or {}
    issues = lint_result.get("issues", [])
    return [issue for issue in issues if isinstance(issue, dict) and issue.get("severity") == "blocked"]
