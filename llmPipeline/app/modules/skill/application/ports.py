from typing import Protocol

from app.modules.skill.domain.entities import Skill


class SkillRepositoryPort(Protocol):
    def list_accessible_enabled(self, workspace_id: str, user_id: str) -> list[Skill]:
        ...

    def get_accessible(self, workspace_id: str, user_id: str, skill_id: str) -> Skill | None:
        ...

    def get_accessible_by_slug(self, workspace_id: str, user_id: str, slug: str) -> Skill | None:
        ...


class ManageSkillRepositoryPort(Protocol):
    def create(self, skill: Skill, version: object) -> Skill:
        ...

    def get_manageable(self, workspace_id: str, user_id: str, skill_id: str) -> Skill | None:
        ...

    def save_draft_version(self, skill: Skill, version: object) -> Skill:
        ...

    def publish(self, workspace_id: str, user_id: str, skill_id: str, version_id: str) -> Skill:
        ...

    def set_enabled(self, workspace_id: str, user_id: str, skill_id: str, enabled: bool) -> Skill:
        ...
