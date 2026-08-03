from functools import lru_cache

from app.modules.skill.application.manage_skill import ManageSkillUseCase
from app.modules.skill.infrastructure.postgres_skill_repository import PostgresSkillRepository


@lru_cache(maxsize=1)
def get_skill_repository() -> PostgresSkillRepository:
    return PostgresSkillRepository()


@lru_cache(maxsize=1)
def get_manage_skill_use_case() -> ManageSkillUseCase:
    return ManageSkillUseCase(get_skill_repository())
