from fastapi import APIRouter, Depends, HTTPException

from app.modules.skill.application.author_skill import AuthorSkillUseCase
from app.modules.skill.application.manage_skill import ManageSkillUseCase
from app.modules.skill.application.propose_skill_draft import ProposeSkillDraftUseCase
from app.modules.skill.application.ports import SkillRepositoryPort
from app.modules.skill.interfaces.http.dependencies import (
    get_author_skill_use_case,
    get_manage_skill_use_case,
    get_propose_skill_draft_use_case,
    get_skill_repository,
)
from app.modules.skill.interfaces.http.schemas import (
    PublishAuthoredSkillRequest,
    SkillAuthoringRequest,
    SkillAuthoringResponse,
    SkillActorRequest,
    SkillDefinitionRequest,
    SkillDraftProposalRequest,
    SkillPreviewResponse,
    SkillRefineResponse,
    SkillResponse,
    UpdateSkillRequest,
)


router = APIRouter(prefix="/skills", tags=["skills"])


@router.post("/refine", response_model=SkillRefineResponse)
def refine_skill(
    payload: SkillDefinitionRequest,
    use_case: AuthorSkillUseCase = Depends(get_author_skill_use_case),
) -> SkillRefineResponse:
    try:
        result = use_case.refine_definition(
            workspace_id=payload.workspace_id or "",
            user_id=payload.user_id,
            command=payload.normalized_command,
            name=payload.name,
            description=payload.description,
            instructions_markdown=payload.instructions_markdown,
            scope_type=payload.scope_type,
            capabilities=tuple(payload.capabilities),
            allowed_tools=tuple(payload.allowed_tools),
            references=payload.references(),
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail="Skill AI 구체화를 완료하지 못했습니다.") from exc
    return SkillRefineResponse.from_domain(result)


@router.post("/author", response_model=SkillAuthoringResponse)
def author_skill(
    payload: SkillAuthoringRequest,
    use_case: AuthorSkillUseCase = Depends(get_author_skill_use_case),
) -> SkillAuthoringResponse:
    try:
        result = use_case.execute(
            workspace_id=payload.workspace_id,
            user_id=payload.user_id,
            scope_type=payload.scope_type,
            name=payload.name,
            description=payload.description,
            instruction=payload.instruction,
            authoring_mode=payload.authoring_mode,
            reference_document_ids=tuple(payload.reference_document_ids),
            allow_clarification=False,
        )
        return SkillAuthoringResponse.from_domain(result)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/author/publish", response_model=SkillAuthoringResponse)
def publish_authored_skill(
    payload: PublishAuthoredSkillRequest,
    use_case: AuthorSkillUseCase = Depends(get_author_skill_use_case),
) -> SkillAuthoringResponse:
    try:
        result = use_case.publish(
            workspace_id=payload.workspace_id,
            user_id=payload.user_id,
            scope_type=payload.scope_type,
            name=payload.name,
            description=payload.description,
            instructions_markdown=payload.instructions_markdown,
        )
        return SkillAuthoringResponse.from_domain(result)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/draft-from-runs/preview", response_model=SkillAuthoringResponse)
def propose_skill_draft(
    payload: SkillDraftProposalRequest,
    use_case: ProposeSkillDraftUseCase = Depends(get_propose_skill_draft_use_case),
    authorer: AuthorSkillUseCase = Depends(get_author_skill_use_case),
) -> SkillAuthoringResponse:
    try:
        proposal = use_case.execute(
            source_runs=tuple(source.to_domain() for source in payload.source_runs),
            user_directives=tuple(payload.user_directives),
            excluded_literals=tuple(payload.excluded_literals),
        )
        reviewed = authorer.review_draft(
            workspace_id=payload.workspace_id,
            user_id=payload.user_id,
            scope_type=payload.scope_type,
            draft=proposal,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return SkillAuthoringResponse.from_domain(reviewed)


@router.post("/preview", response_model=SkillPreviewResponse)
def preview_skill(
    payload: SkillDefinitionRequest,
    use_case: AuthorSkillUseCase = Depends(get_author_skill_use_case),
) -> SkillPreviewResponse:
    result = use_case.review_definition(
        workspace_id=payload.workspace_id or "",
        user_id=payload.user_id,
        command=payload.normalized_command,
        name=payload.name,
        description=payload.description,
        instructions_markdown=payload.instructions_markdown,
        scope_type=payload.scope_type,
        capabilities=tuple(payload.capabilities),
        allowed_tools=tuple(payload.allowed_tools),
        references=payload.references(),
    )
    return SkillPreviewResponse.from_domain(result)


@router.get("", response_model=list[SkillResponse])
def list_skills(
    workspace_id: str,
    user_id: str,
    repository: SkillRepositoryPort = Depends(get_skill_repository),
) -> list[SkillResponse]:
    return [SkillResponse.from_domain(skill) for skill in repository.list_accessible(workspace_id, user_id)]


@router.get("/{skill_id}", response_model=SkillResponse)
def get_skill(
    skill_id: str,
    workspace_id: str,
    user_id: str,
    repository: SkillRepositoryPort = Depends(get_skill_repository),
) -> SkillResponse:
    skill = repository.get_accessible(workspace_id, user_id, skill_id)
    if skill is None:
        raise HTTPException(status_code=404, detail="Skill not found.")
    return SkillResponse.from_domain(skill)


@router.patch("/{skill_id}", response_model=SkillAuthoringResponse)
def update_skill(
    skill_id: str,
    payload: UpdateSkillRequest,
    use_case: AuthorSkillUseCase = Depends(get_author_skill_use_case),
) -> SkillAuthoringResponse:
    try:
        result = use_case.update(
            workspace_id=payload.workspace_id,
            user_id=payload.user_id,
            skill_id=skill_id,
            name=payload.name,
            description=payload.description,
            instructions_markdown=payload.instructions_markdown,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return SkillAuthoringResponse.from_domain(result)


@router.post("/{skill_id}/enable", response_model=SkillResponse)
def enable_skill(
    skill_id: str,
    payload: SkillActorRequest,
    use_case: ManageSkillUseCase = Depends(get_manage_skill_use_case),
) -> SkillResponse:
    return _set_enabled(use_case, payload, skill_id, True)


@router.post("/{skill_id}/disable", response_model=SkillResponse)
def disable_skill(
    skill_id: str,
    payload: SkillActorRequest,
    use_case: ManageSkillUseCase = Depends(get_manage_skill_use_case),
) -> SkillResponse:
    return _set_enabled(use_case, payload, skill_id, False)


def _set_enabled(
    use_case: ManageSkillUseCase,
    payload: SkillActorRequest,
    skill_id: str,
    enabled: bool,
) -> SkillResponse:
    try:
        skill = use_case.set_enabled(payload.workspace_id, payload.user_id, skill_id, enabled)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return SkillResponse.from_domain(skill)
