from __future__ import annotations

from hmac import compare_digest
import logging
import os
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException

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


def require_agent_service_token(
    token: str | None = Header(default=None, alias="X-Agent-Service-Token"),
) -> None:
    expected = os.environ.get("AGENT_INTERNAL_TOKEN")
    if not expected:
        raise HTTPException(status_code=503, detail="Agent service authentication is not configured.")
    if token is None or not compare_digest(token, expected):
        raise HTTPException(status_code=401, detail="Invalid Agent service token.")


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 스키마 버전 관리는 Spring Flyway가 담당하고 pipeline은 준비 상태만 확인한다.
    try:
        database.verify_schema()
        logger.info("[startup] Flyway DB 스키마 확인 완료")
    except Exception:
        logger.exception("[startup] Flyway DB 스키마 확인 실패")
        raise
    yield


app = FastAPI(title="Fruition Pipeline Lab API", version="0.1.0", lifespan=lifespan)
app.include_router(
    agent_router,
    dependencies=[Depends(require_agent_service_token)] if AGENT_SKILLS_ENABLED else None,
)
app.include_router(query_router)
app.include_router(pipeline_router)
app.include_router(wiki_schema_router)
if AGENT_SKILLS_ENABLED:
    internal_dependencies = [Depends(require_agent_service_token)]
    app.include_router(agent_run_router, dependencies=internal_dependencies)
    app.include_router(skill_router, dependencies=internal_dependencies)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/documents/{document_id}")
def get_document(document_id: str) -> dict:
    try:
        document = database.get_document(document_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    if not document:
        raise HTTPException(status_code=404, detail="Document not found")
    return document
