from functools import lru_cache

from app.modules.skill.application.author_skill import AuthorSkillUseCase
from app.modules.skill.application.manage_skill import ManageSkillUseCase
from app.modules.skill.application.propose_skill_draft import ProposeSkillDraftUseCase
from app.modules.skill.infrastructure.backend_skill_reference_reader import (
    build_skill_reference_reader,
)
from app.modules.skill.infrastructure.chat_completions_skill_authoring_generator import (
    build_skill_authoring_generator,
)
from app.modules.skill.infrastructure.chat_completions_skill_draft_generator import (
    build_skill_draft_generator,
)
from app.modules.skill.infrastructure.postgres_skill_repository import PostgresSkillRepository


@lru_cache(maxsize=1)
def get_skill_repository() -> PostgresSkillRepository:
    return PostgresSkillRepository()


@lru_cache(maxsize=1)
def get_manage_skill_use_case() -> ManageSkillUseCase:
    return ManageSkillUseCase(get_skill_repository())


@lru_cache(maxsize=1)
def get_propose_skill_draft_use_case() -> ProposeSkillDraftUseCase:
    return ProposeSkillDraftUseCase(build_skill_draft_generator())


@lru_cache(maxsize=1)
def get_author_skill_use_case() -> AuthorSkillUseCase:
    return AuthorSkillUseCase(
        build_skill_authoring_generator(),
        build_skill_reference_reader(),
        get_manage_skill_use_case(),
    )
