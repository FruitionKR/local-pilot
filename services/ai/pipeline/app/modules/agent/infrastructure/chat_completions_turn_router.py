import json
import os
import re
from dataclasses import replace
from pathlib import Path
from typing import Any

from app.core.llm_env import (
    api_key_from_env,
    float_env,
    int_env,
    optional_int_env,
    provider_api_key_env,
    resolve_llm_selection,
)
from app.modules.agent.application.ports import AgentTurnRouterPort
from app.modules.agent.domain.entities import AgentAction, AgentTurnRequest, AgentTurnRoute
from app.modules.agent.domain.exceptions import AgentTurnRouteContractError
from app.modules.wiki_generation.infrastructure.chat_completions_llm import ChatClientConfig, ChatCompletionsJsonClient
from app.modules.wiki_generation.infrastructure.json_output_parser import JsonParseError


DEFAULT_AGENT_TURN_ROUTER_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / "agent_turn_router.system.md"
TEMPLATE_DEFERRED_MARKERS = (
    "template",
    "템플릿",
)
INSERT_AFTER_POSITION_MARKERS = ("아래에", "아래로", "뒤에", "뒤로", "after", "below")
INSERT_AFTER_ACTION_MARKERS = ("추가", "삽입", "붙여", "insert", "append", "add")
NEW_SKILL_REQUEST_PATTERN = re.compile(
    r"(?:스킬|skill)(?:을|를)?\s*(?:(?:하나|새로|새로운|신규로|직접)\s*){0,2}"
    r"(?:만들어|생성해|정의해|작성해)|"
    r"(?:스킬|skill)로\s+[a-z0-9][a-z0-9-]{0,62}(?:을|를)?\s*(?:만들어|생성해|정의해|작성해)|"
    r"(?:create|make|define|write)\s+(?:a\s+)?(?:new\s+)?skill\b",
    re.IGNORECASE,
)
SKILL_NEGATION_PATTERN = re.compile(
    r"(?:스킬|skill).{0,30}(?:만들지\s*(?:마|말고)|생성하지\s*(?:마|말고)|작성하지\s*(?:마|말고)|하지\s*말|취소|cancel)|"
    r"(?:don't|do not|never)\s+(?:create|make|define|write)\s+(?:a\s+)?(?:new\s+)?skill\b",
    re.IGNORECASE,
)
COMPLETED_WORK_REQUEST_PATTERN = re.compile(
    r"(?:방금|아까|이전|앞서).{0,20}(?:방식|작업|결과|과정|흐름)|"
    r"(?:just now|earlier|previous).{0,30}(?:method|work|result|process|workflow)",
    re.IGNORECASE,
)
PUBLISH_SKILL_PATTERN = re.compile(
    r"(?:이대로\s*)?(?:게시|등록)(?:해|해줘|해주세요|하자)|"
    r"(?:please\s+)?(?:publish|post)(?:\s+(?:it|this|the\s+skill))?",
    re.IGNORECASE,
)
PENDING_SKILL_FOLLOWUP_PATTERN = re.compile(
    r"보안\s*(?:재)?검토|다시\s*검증|security\s*(?:re)?view|"
    r"(?:AI로\s*)?재생성|다시\s*(?:만들어|작성)|regenerate|"
    r"(?:제목|이름|커맨드|식별자).*(?:바꿔|변경|수정)|"
    r"(?:개인|팀)(?:\s*(?:스킬|skill))?(?:로|으로)?\s*(?:해|바꿔|변경|수정)",
    re.IGNORECASE,
)
DOCUMENT_DISPLAY_NAME_PATTERN = re.compile(
    r"(?:문서\s*트리|display_name|표시\s*이름|문서(?:\s*파일)?(?:의)?\s*이름).{0,80}"
    r"(?:바꿔|변경|수정|rename)",
    re.IGNORECASE,
)
PERSISTENT_EDIT_PATTERN = re.compile(
    r"(?:워크스페이스.{0,20}(?:저장|반영)|영구.{0,20}(?:적용|반영|저장)|"
    r"(?:저장|반영)(?:해|해줘|해주세요)|승인\s*(?:후|뒤)|"
    r"(?:persist|save|apply).{0,30}(?:workspace|approval|permanent))",
    re.IGNORECASE,
)
WORKSPACE_MUTATION_PATTERN = re.compile(
    r"(?:저장|반영|적용|생성|작성|수정|변경|이동|삭제|복사)(?:해|해줘|해주세요)|"
    r"(?:만들어|바꿔|옮겨)(?:줘|주세요)|"
    r"\b(?:save|apply|create|write|edit|rename|move|delete|copy)\b",
    re.IGNORECASE,
)
CONVERSATION_REFINEMENT_PATTERN = re.compile(
    r"(?:형식|말투|길이|이모지|제목|문구).{0,40}(?:만들어|바꿔|변경|수정|써줘|해줘)|"
    r"(?:format|tone|length|emoji|title|wording).{0,40}(?:make|change|revise|write)|"
    r"how\s+do\s+i\s+make.{0,60}\bwork|"
    r"what\s+process\s+should\s+i\s+use\s+to",
    re.IGNORECASE,
)
GROUNDED_RETRIEVAL_PATTERN = re.compile(
    r"(?:내부\s*문서|워크스페이스|위키|(?a:\b(?:wiki|workspace|document)\b)).{0,40}"
    r"(?:기준|근거|찾아|검색|조회|(?a:\b(?:search|find|retrieve|ground)\b))|"
    r"(?:기준|근거|찾아|검색|조회|(?a:\b(?:search|find|retrieve|ground)\b)).{0,40}"
    r"(?:내부\s*문서|워크스페이스|위키|(?a:\b(?:wiki|workspace|document)\b))",
    re.IGNORECASE,
)
WEB_RETRIEVAL_PATTERN = re.compile(
    r"(?:웹|인터넷|온라인).{0,40}(?:검색|찾|조사|조회)|"
    r"(?:검색|찾|조사|조회).{0,40}(?:웹|인터넷|온라인)|"
    r"(?:최신|최근|오늘).{0,30}(?:정보|동향|뉴스|자료).{0,30}(?:검색|찾|조사|조회)|"
    r"(?a:\b(?:web|internet|online)\b).{0,40}(?a:\b(?:search|find|research|look\s+up)\b)|"
    r"(?a:\b(?:search|find|research|look\s+up)\b).{0,40}(?a:\b(?:web|internet|online)\b)|"
    r"(?a:\b(?:latest|recent|current)\b).{0,30}(?a:\b(?:information|trends|news|sources)\b)"
    r".{0,30}(?a:\b(?:search|find|research|look\s+up)\b)",
    re.IGNORECASE,
)
DOCUMENT_CREATION_PATTERN = re.compile(
    r"(?:새(?:로운)?\s*문서|문서(?:를|로)|보고서(?:를|로)|markdown(?:을|으로)).{0,30}"
    r"(?:만들|생성|작성|저장)|"
    r"(?:만들|생성|작성).{0,30}(?:새(?:로운)?\s*문서|문서(?:를|로)|보고서(?:를|로)|markdown)|"
    r"(?:검색|조회)\s*결과.{0,20}저장|"
    r"(?a:\b(?:create|write|draft|save)\b).{0,30}"
    r"(?a:\b(?:new\s+)?(?:document|markdown|report)\b)",
    re.IGNORECASE,
)
TECHNICAL_PROCESS_QUESTION_PATTERN = re.compile(
    r"(?:위키|워크스페이스|(?a:\b(?:wiki|workspace|ingest|pipeline|query|lint|agent|skill)\b)).{0,40}"
    r"(?:어떤\s*단계로.{0,20}(?:동작|작동|진행|처리)|"
    r"어떻게.{0,20}(?:동작|작동))"
    r"(?:해|하나요|합니까|돼|되나요|됩니까)(?:\?+)?$|"
    r"how\s+(?:does|do)\s+(?:the\s+)?"
    r"(?a:\b(?:wiki|workspace|ingest|pipeline|query|lint|agent|skill)\b).{0,30}\bwork(?:\?+)?$|"
    r"what\s+are\s+the\s+stages\s+of\s+(?:the\s+)?"
    r"(?a:\b(?:wiki|workspace|ingest|pipeline|query|lint|agent|skill)\b)(?:\?+)?$|"
    r"what\s+is\s+the\s+process\s+(?:of|for)\s+(?:the\s+)?"
    r"(?a:\b(?:wiki|workspace|ingest|pipeline|query|lint|agent|skill)\b)(?:\s+\w+){0,3}(?:\?+)?$",
    re.IGNORECASE,
)
CURRENT_MARKDOWN_EDIT_PATTERN = re.compile(
    r"(?:현재|이|열린)\s*(?:문서|markdown).{0,50}(?:수정|편집|보완|다듬|바꿔|변경|반영)|"
    r"(?:update|edit|revise|improve).{0,40}(?:current|this|open)\s+(?:document|markdown)",
    re.IGNORECASE,
)
ALLOWED_ACTIONS = {
    "chat_answer",
    "conversation_reply",
    "markdown_edit",
    "markdown_create",
    "folder_organize",
    "workspace_workflow",
    "skill_authoring",
    "skill_draft_proposal",
    "clarify",
    "reject",
}
JSON_OBJECT_CONTRACT_FAILURE = "model output must be a JSON object"


class ChatCompletionsTurnRouter(AgentTurnRouterPort):
    def __init__(self, client: ChatCompletionsJsonClient, system_prompt: str) -> None:
        self._client = client
        self._system_prompt = system_prompt

    def route(self, request: AgentTurnRequest) -> AgentTurnRoute:
        guarded = _local_guard(request)
        if guarded is not None:
            return guarded

        payload = {
            "message": request.message,
            "conversation_summary": (
                request.conversation_context.recent_conversation_summary
                if request.conversation_context
                else None
            ),
            "recent_messages": (
                [
                    {
                        "role": message.role,
                        "content": message.content,
                        "action": message.action,
                    }
                    for message in request.conversation_context.recent_messages
                ]
                if request.conversation_context
                else []
            ),
            "reference_context": (
                request.conversation_context.reference_context
                if request.conversation_context
                else {}
            ),
            "pending_skill_proposal": (
                {
                    "scope_type": request.conversation_context.pending_skill_proposal.scope_type,
                    "name": request.conversation_context.pending_skill_proposal.name,
                    "description": request.conversation_context.pending_skill_proposal.description,
                    "instructions_markdown": request.conversation_context.pending_skill_proposal.instructions_markdown,
                }
                if request.conversation_context and request.conversation_context.pending_skill_proposal
                else None
            ),
            "active_markdown_context": {
                "has_markdown": bool(request.active_markdown_context and request.active_markdown_context.markdown.strip()),
                "target": (
                    {
                        "type": request.active_markdown_context.target.type,
                        "start_line": request.active_markdown_context.target.start_line,
                        "end_line": request.active_markdown_context.target.end_line,
                    }
                    if request.active_markdown_context and request.active_markdown_context.target
                    else None
                ),
            },
            "skill_mode": request.skill_mode,
            "skill_scope_type": request.skill_scope_type,
            "skill_authoring_mode": request.skill_authoring_mode,
            "allow_web_search": request.allow_web_search,
            "available_skills": [
                {
                    "id": skill.id,
                    "version_id": skill.version_id,
                    "name": skill.name,
                    "description": skill.description,
                    "capabilities": list(skill.capabilities),
                }
                for skill in request.available_skills
            ],
        }
        route, failures = self._complete_route(payload)
        route = _promote_persistent_edit(route, request)
        route = _promote_grounded_query(route, request)
        failures.extend(_route_failures(route, request))
        failures.extend(_skill_authoring_failures(route, request))
        if not failures:
            return route

        retry_payload = {
            **payload,
            "contract_failures": failures,
            "retry_instruction": "Correct every contract failure and return the required route JSON object again.",
        }
        retried_route, retry_failures = self._complete_route(retry_payload)
        retried_route = _promote_persistent_edit(retried_route, request)
        retried_route = _promote_grounded_query(retried_route, request)
        retry_failures.extend(_route_failures(retried_route, request))
        retry_failures.extend(_skill_authoring_failures(retried_route, request))
        if retry_failures:
            raise AgentTurnRouteContractError(retry_failures)
        return retried_route

    def _complete_route(self, payload: dict[str, object]) -> tuple[AgentTurnRoute, list[str]]:
        try:
            raw = self._client.complete_json(
                self._system_prompt,
                json.dumps(payload, ensure_ascii=False, indent=2),
            )
        except JsonParseError:
            return _fallback_route(), [JSON_OBJECT_CONTRACT_FAILURE]
        return _normalize_route(raw)


def build_agent_turn_router(
    *,
    provider: str | None = None,
    model: str | None = None,
) -> AgentTurnRouterPort:
    resolved_provider, resolved_model = resolve_llm_selection(provider, model)
    api_key = _api_key(resolved_provider)
    if not api_key:
        raise RuntimeError(f"Set {provider_api_key_env(resolved_provider)}.")
    prompt_path = Path(os.environ.get("AGENT_TURN_ROUTER_SYSTEM_PROMPT", str(DEFAULT_AGENT_TURN_ROUTER_PROMPT)))
    return ChatCompletionsTurnRouter(
        ChatCompletionsJsonClient(
            ChatClientConfig(
                api_key=api_key,
                model=resolved_model,
                temperature=None,
                timeout_seconds=_int_env("AGENT_ROUTER_LLM_TIMEOUT_SECONDS", 180),
                max_tokens=_optional_int_env("AGENT_ROUTER_LLM_MAX_TOKENS"),
                json_mode=True,
                provider=resolved_provider,
            )
        ),
        system_prompt=prompt_path.read_text(encoding="utf-8"),
    )


def _local_guard(request: AgentTurnRequest) -> AgentTurnRoute | None:
    lowered = request.message.lower()
    has_pending_proposal = bool(
        request.conversation_context and request.conversation_context.pending_skill_proposal
    )
    if (
        has_pending_proposal
        and (
            PUBLISH_SKILL_PATTERN.fullmatch(lowered.strip())
            or PENDING_SKILL_FOLLOWUP_PATTERN.search(lowered)
        )
    ):
        return AgentTurnRoute(
            action="skill_authoring",
            confidence=1.0,
            reason="explicit approval for pending Skill proposal",
        )
    requests_new_skill = _requests_new_skill(lowered)
    if (
        not requests_new_skill
        and DOCUMENT_DISPLAY_NAME_PATTERN.search(request.message)
        and not re.search(r"(?:h1|heading|본문)", request.message, re.IGNORECASE)
    ):
        return AgentTurnRoute(
            action="folder_organize",
            confidence=1.0,
            reason="document display name change",
        )
    has_template_skill = any("template" in skill.capabilities for skill in request.available_skills)
    if (
        not requests_new_skill
        and not has_template_skill
        and any(marker in lowered for marker in TEMPLATE_DEFERRED_MARKERS)
    ):
        return AgentTurnRoute(
            action="clarify",
            confidence=1.0,
            reason="template/full-document transform is deferred",
            edit_goal="template_transform",
        )
    requests_insert_after = any(marker in lowered for marker in INSERT_AFTER_POSITION_MARKERS) and any(
        marker in lowered for marker in INSERT_AFTER_ACTION_MARKERS
    )
    if requests_insert_after:
        target = request.active_markdown_context.target if request.active_markdown_context else None
        return AgentTurnRoute(
            action="markdown_edit" if target and target.type == "current_section" else "clarify",
            confidence=1.0,
            reason="insert_after request requires a current section target",
            edit_goal="insert_after",
        )
    return None


def _promote_persistent_edit(
    route: AgentTurnRoute,
    request: AgentTurnRequest,
) -> AgentTurnRoute:
    if route.action not in {"markdown_edit", "workspace_workflow"}:
        return route
    if PERSISTENT_EDIT_PATTERN.search(request.message) is None:
        return route
    return replace(
        route,
        action="workspace_workflow",
        edit_goal=route.edit_goal or "other",
    )


def _promote_grounded_query(
    route: AgentTurnRoute,
    request: AgentTurnRequest,
) -> AgentTurnRoute:
    requests_web_retrieval = WEB_RETRIEVAL_PATTERN.search(request.message) is not None
    requests_current_edit = bool(
        request.active_markdown_context
        and CURRENT_MARKDOWN_EDIT_PATTERN.search(request.message)
    )
    requests_document_creation = DOCUMENT_CREATION_PATTERN.search(request.message) is not None
    if (
        requests_web_retrieval
        and request.allow_web_search is not True
        and requests_document_creation
        and route.action in {
            "chat_answer",
            "conversation_reply",
            "markdown_edit",
            "markdown_create",
            "workspace_workflow",
            "clarify",
        }
    ):
        return replace(
            route,
            action="chat_answer",
            edit_goal=None,
            selected_skill_id=None,
            skill_candidates=(),
            requires_grounded_retrieval=False,
        )
    if not (
        _requests_grounded_retrieval(request.message)
        or (
            requests_web_retrieval
            and requests_document_creation
            and request.allow_web_search is True
        )
    ):
        return route
    if requests_current_edit and route.action in {
        "chat_answer",
        "conversation_reply",
        "markdown_edit",
        "markdown_create",
        "workspace_workflow",
        "clarify",
    }:
        return replace(
            route,
            action=(
                "workspace_workflow"
                if PERSISTENT_EDIT_PATTERN.search(request.message)
                else "markdown_edit"
            ),
            edit_goal="other",
            selected_skill_id=None,
            skill_candidates=(),
            requires_grounded_retrieval=True,
        )
    if requests_document_creation and route.action in {
        "chat_answer",
        "conversation_reply",
        "markdown_edit",
        "markdown_create",
        "workspace_workflow",
        "clarify",
    }:
        return replace(
            route,
            action="workspace_workflow",
            edit_goal="create_from_chat",
            selected_skill_id=None,
            skill_candidates=(),
            requires_grounded_retrieval=True,
        )
    if route.action == "markdown_edit":
        return replace(route, requires_grounded_retrieval=True)
    if route.action == "markdown_create":
        return replace(
            route,
            action="workspace_workflow",
            edit_goal="create_from_chat",
            requires_grounded_retrieval=True,
        )
    if route.action == "workspace_workflow" and WORKSPACE_MUTATION_PATTERN.search(request.message):
        return replace(
            route,
            requires_grounded_retrieval=True,
        )
    if route.action not in {"conversation_reply", "clarify", "workspace_workflow"}:
        return route
    return replace(
        route,
        action="chat_answer",
        edit_goal=None,
        selected_skill_id=None,
        skill_candidates=(),
    )


def _requests_new_skill(message: str) -> bool:
    return NEW_SKILL_REQUEST_PATTERN.search(message) is not None


def _requests_grounded_retrieval(message: str) -> bool:
    return bool(
        GROUNDED_RETRIEVAL_PATTERN.search(message)
        or TECHNICAL_PROCESS_QUESTION_PATTERN.search(message)
    )


def _route_failures(route: AgentTurnRoute, request: AgentTurnRequest) -> list[str]:
    if (
        route.action == "clarify"
        and route.edit_goal is None
        and not route.skill_candidates
    ):
        return [
            "clarify requires a supported Markdown target reason or ambiguous Skill candidates; "
            "use conversation_reply when a conversational task needs more user context"
        ]
    if (
        route.action == "chat_answer"
        and CONVERSATION_REFINEMENT_PATTERN.search(request.message)
        and not _requests_grounded_retrieval(request.message)
    ):
        expected_action = (
            "markdown_edit"
            if request.active_markdown_context and request.active_markdown_context.markdown.strip()
            else "conversation_reply"
        )
        return [
            "a format or wording refinement must use "
            f"{expected_action} unless the current message explicitly requests grounded retrieval"
        ]
    return []


def _skill_authoring_failures(route: AgentTurnRoute, request: AgentTurnRequest) -> list[str]:
    if route.action != "skill_authoring":
        return []
    summary = (
        request.conversation_context.recent_conversation_summary
        if request.conversation_context and request.conversation_context.recent_conversation_summary
        else ""
    )
    recent_messages = (
        request.conversation_context.recent_messages
        if request.conversation_context
        else ()
    )
    recent_user_message = next(
        (message.content for message in reversed(recent_messages) if message.role == "user"),
        "",
    )
    intent_context = recent_user_message if recent_messages else summary
    has_pending_proposal = bool(
        request.conversation_context and request.conversation_context.pending_skill_proposal
    )
    if SKILL_NEGATION_PATTERN.search(request.message):
        return ["skill_authoring must not override an explicit current Skill refusal"]
    if not has_pending_proposal and not _requests_new_skill(
        f"{request.message}\n{intent_context}"
    ):
        return ["skill_authoring requires an explicit request to create a new Skill"]
    if COMPLETED_WORK_REQUEST_PATTERN.search(request.message):
        return ["completed work must use skill_draft_proposal instead of skill_authoring"]
    return []


def _normalize_route(value: dict[str, Any]) -> tuple[AgentTurnRoute, list[str]]:
    failures: list[str] = []
    raw_action = value.get("action")
    if not isinstance(raw_action, str) or raw_action not in ALLOWED_ACTIONS:
        failures.append("action must be a supported value")
        action: AgentAction = "chat_answer"
    else:
        action = raw_action

    raw_confidence = value.get("confidence")
    if (
        not isinstance(raw_confidence, (int, float))
        or isinstance(raw_confidence, bool)
        or not 0.0 <= float(raw_confidence) <= 1.0
    ):
        failures.append("confidence must be a number between 0 and 1")
        confidence = 0.0
    else:
        confidence = float(raw_confidence)

    raw_reason = value.get("reason")
    if not isinstance(raw_reason, str) or not raw_reason.strip():
        failures.append("reason must be a non-empty string")
        reason = ""
    else:
        reason = raw_reason.strip()

    if "edit_goal" not in value:
        failures.append("edit_goal is required")
    raw_edit_goal = value.get("edit_goal")
    if raw_edit_goal is not None and not isinstance(raw_edit_goal, str):
        failures.append("edit_goal must be a string or null")
        edit_goal = None
    else:
        edit_goal = _optional_text(raw_edit_goal)

    raw_selected_skill_id = value.get("selected_skill_id")
    if raw_selected_skill_id is not None and not isinstance(raw_selected_skill_id, str):
        failures.append("selected_skill_id must be a string or null")
        selected_skill_id = None
    else:
        selected_skill_id = _optional_text(raw_selected_skill_id)

    raw_skill_candidates = value.get("skill_candidates", [])
    if not isinstance(raw_skill_candidates, list) or not all(
        isinstance(candidate, str) and candidate.strip() for candidate in raw_skill_candidates
    ):
        failures.append("skill_candidates must be an array of non-empty strings")
        skill_candidates: tuple[str, ...] = ()
    else:
        skill_candidates = tuple(candidate.strip() for candidate in raw_skill_candidates)

    return AgentTurnRoute(
        action=action,
        confidence=confidence,
        reason=reason,
        edit_goal=edit_goal,
        selected_skill_id=selected_skill_id,
        skill_candidates=skill_candidates,
    ), failures


def _fallback_route() -> AgentTurnRoute:
    return AgentTurnRoute(
        action="chat_answer",
        confidence=0.0,
        reason="",
    )


def _api_key(provider: str | None = None) -> str | None:
    return api_key_from_env(
        provider=provider,
    )


def _optional_text(value: object) -> str | None:
    text = str(value or "").strip()
    return text or None


def _float_env(name: str, default: float) -> float:
    return float_env(name, default)


def _int_env(name: str, default: int) -> int:
    return int_env(name, default)


def _optional_int_env(name: str) -> int | None:
    return optional_int_env(name)
