from __future__ import annotations

from hmac import compare_digest
import logging
import os
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from app.modules.agent.interfaces.http.routes import router as agent_router
from app.modules.agent_run.interfaces.http.routes import router as agent_run_router
from app.modules.query.interfaces.http.routes import router as query_router
from app.modules.skill.interfaces.http.routes import router as skill_router
from app.modules.wiki_ingestion.interfaces.http.routes import router as pipeline_router
from app.modules.wiki_ingestion.interfaces.http.schemas import (
    CHAT_APPEND_SEMANTIC_PROMPT,
    CHAT_SEMANTIC_PROMPT,
    DOCUMENT_SEMANTIC_PROMPT,
    ChatWikiRunIn,
    PipelineRunIn,
    PipelineRunOut,
    ReingestRunIn,
)
from app.modules.wiki_schema.interfaces.http.routes import router as wiki_schema_router
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database

logger = logging.getLogger(__name__)
AGENT_SKILLS_ENABLED = os.environ.get("AGENT_SKILLS_ENABLED", "false").lower() in {
    "1",
    "true",
    "yes",
    "on",
}


def require_internal_token(
    token: str | None = Header(default=None, alias="X-Internal-Token"),
) -> None:
    expected = os.environ.get("INTERNAL_CALLBACK_TOKEN")
    if not expected:
        raise HTTPException(status_code=503, detail="Internal service authentication is not configured.")
    if token is None or not compare_digest(token, expected):
        raise HTTPException(status_code=401, detail="Invalid internal service token.")


def require_agent_service_token(
    token: str | None = Header(default=None, alias="X-Agent-Service-Token"),
) -> None:
    expected = os.environ.get("AGENT_INTERNAL_TOKEN")
    if not expected:
        raise HTTPException(status_code=503, detail="Agent service authentication is not configured.")
    if token is None or not compare_digest(token, expected):
        raise HTTPException(status_code=401, detail="Invalid Agent service token.")


def require_internal_token(
    token: str | None = Header(default=None, alias="X-Internal-Token"),
) -> None:
    """backend와 공유하는 내부 토큰 검증. 미설정이면 전체 거부(fail-closed)한다."""
    expected = os.environ.get("INTERNAL_CALLBACK_TOKEN")
    if not expected:
        raise HTTPException(status_code=503, detail="Internal token authentication is not configured.")
    if token is None or not compare_digest(token, expected):
        raise HTTPException(status_code=401, detail="Invalid internal token.")


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 스키마 버전 관리는 Spring Flyway가 담당하고 pipeline은 준비 상태만 확인한다.
    try:
        database.verify_schema()
        logger.info("[startup] Flyway DB 스키마 확인 완료")
    except Exception:
        logger.exception("[startup] Flyway DB 스키마 확인 실패")
        raise
    # ai_db는 python이 소유한다 — AI_DB_MIGRATION_URL이 있으면 멱등 DDL 적용 후 검증.
    try:
        database.ensure_ai_schema()
        logger.info("[startup] ai_db 스키마 확인 완료")
    except Exception:
        logger.exception("[startup] ai_db 스키마 확인 실패")
        raise
    yield


app = FastAPI(title="Fruition Pipeline Lab API", version="0.1.0", lifespan=lifespan)


# 내부 토큰이 필요한 route의 경로 패턴. include_internal_router가 채우므로
# 라우터 등록과 middleware 검사 대상이 갈라지지 않는다.
INTERNAL_TOKEN_ROUTE_PATTERNS: list[Any] = []


def include_internal_router(router, dependencies) -> None:
    """내부 토큰이 필요한 라우터를 등록하고 그 경로를 middleware 검사 대상에 함께 넣는다."""
    app.include_router(router, dependencies=dependencies)
    INTERNAL_TOKEN_ROUTE_PATTERNS.extend(route.path_regex for route in router.routes)


@app.middleware("http")
async def authenticate_internal_request(request: Request, call_next):
    """본문 파싱(422)보다 내부 토큰 검증(401/503)이 먼저 판정되도록 middleware에서 막는다."""
    path = request.url.path
    if any(pattern.match(path) for pattern in INTERNAL_TOKEN_ROUTE_PATTERNS):
        try:
            require_internal_token(request.headers.get("X-Internal-Token"))
        except HTTPException as exc:
            return JSONResponse(
                status_code=exc.status_code,
                content={"detail": exc.detail},
            )
    return await call_next(request)


internal_token_dependencies = [Depends(require_internal_token)]
# agent turn은 Skill 기능이 켜져 있어도 내부 토큰이 필요하다 (그 위에 agent service token이 더 붙는다).
include_internal_router(
    agent_router,
    [Depends(require_agent_service_token)] if AGENT_SKILLS_ENABLED else internal_token_dependencies,
)
include_internal_router(query_router, internal_token_dependencies)
include_internal_router(pipeline_router, internal_token_dependencies)
include_internal_router(wiki_schema_router, internal_token_dependencies)
if AGENT_SKILLS_ENABLED:
    # agent run·skill 관리 API는 내부 토큰이 아니라 agent service token으로만 보호한다.
    agent_service_dependencies = [Depends(require_agent_service_token)]
    app.include_router(agent_run_router, dependencies=agent_service_dependencies)
    app.include_router(skill_router, dependencies=agent_service_dependencies)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/documents/{document_id}", dependencies=internal_token_dependencies)
def get_document(document_id: str) -> dict:
    try:
        document = database.get_document(document_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    if not document:
        raise HTTPException(status_code=404, detail="Document not found")
    return document


# app에 직접 붙인 route는 include_internal_router를 타지 않으므로 여기서 검사 대상에 넣는다.
INTERNAL_TOKEN_ROUTE_PATTERNS.append(app.routes[-1].path_regex)
