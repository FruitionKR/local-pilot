from fastapi import APIRouter, Depends, HTTPException

from app.modules.agent.application.handle_agent_turn import HandleAgentTurnUseCase
from app.modules.agent.domain.entities import AgentTurnResult
from app.modules.agent.interfaces.http.dependencies import get_handle_agent_turn_use_case
from app.modules.agent.interfaces.http.schemas import (
    AgentTurnRequestBody,
    AgentTurnResponse,
    AgentTurnRouteResponse,
    MarkdownEditOperationResponse,
    MarkdownEditTargetResponse,
)
from app.modules.query.interfaces.http.routes import _to_response as query_to_response


router = APIRouter(prefix="/agent", tags=["agent"])


@router.post("/turn", response_model=AgentTurnResponse)
def handle_agent_turn(
    payload: AgentTurnRequestBody,
    use_case: HandleAgentTurnUseCase = Depends(get_handle_agent_turn_use_case),
) -> AgentTurnResponse:
    try:
        result = use_case.execute(payload.to_domain())
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return _to_response(result)


def _to_response(result: AgentTurnResult) -> AgentTurnResponse:
    return AgentTurnResponse(
        action=result.action,
        route=AgentTurnRouteResponse(
            action=result.route.action,
            confidence=result.route.confidence,
            reason=result.route.reason,
            edit_goal=result.route.edit_goal,
        ),
        message=result.message,
        chat=query_to_response(result.query_answer) if result.query_answer else None,
        edit=_edit_to_response(result) if result.edit else None,
    )


def _edit_to_response(result: AgentTurnResult) -> MarkdownEditOperationResponse | None:
    if result.edit is None:
        return None
    return MarkdownEditOperationResponse(
        operation=result.edit.operation,
        target=MarkdownEditTargetResponse(
            type=result.edit.target.type,
            start_line=result.edit.target.start_line,
            end_line=result.edit.target.end_line,
        ),
        summary=result.edit.summary,
        replacement_markdown=result.edit.replacement_markdown,
    )
