-- ai_db 스키마 (python/ai-svc 소유).
-- Flyway가 아닌 pipeline 부트스트랩(AI_DB_MIGRATION_URL)이 적용하며, 전부 멱등(IF NOT EXISTS)이다.

-- wiki_schemas: core_db V4__add_pipeline_schema.sql에서 ai_db로 이전한 테이블 (형상 동일)
CREATE TABLE IF NOT EXISTS wiki_schemas (
    id text PRIMARY KEY,
    workspace_id text NOT NULL,
    user_id text NOT NULL,
    name text NOT NULL,
    raw_markdown text NOT NULL,
    sanitized_global_markdown text NOT NULL DEFAULT '',
    sanitized_query_markdown text NOT NULL DEFAULT '',
    sanitized_ingest_markdown text NOT NULL DEFAULT '',
    sanitized_edit_markdown text NOT NULL DEFAULT '',
    sanitized_concept_markdown text NOT NULL DEFAULT '',
    sanitized_template_markdown text NOT NULL DEFAULT '',
    preview_markdown text NOT NULL DEFAULT '',
    lint_result jsonb NOT NULL DEFAULT '{}'::jsonb,
    status text NOT NULL,
    schema_version text NOT NULL DEFAULT '1.0',
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    activated_at timestamp with time zone
);

CREATE INDEX IF NOT EXISTS idx_wiki_schemas_workspace_user_status
    ON wiki_schemas (workspace_id, user_id, status);

CREATE UNIQUE INDEX IF NOT EXISTS uq_wiki_schemas_one_active_per_workspace_user
    ON wiki_schemas (workspace_id, user_id)
    WHERE status = 'active';

-- document_derived_state: document.edit.event 기반 파생물 stale 추적.
-- stale 여부는 컬럼으로 저장하지 않고 조회 시
-- `ingested_hash IS DISTINCT FROM last_edit_hash`로 계산한다.
CREATE TABLE IF NOT EXISTS document_derived_state (
    document_id text PRIMARY KEY,
    workspace_id text NOT NULL,
    last_edit_revision bigint NOT NULL,
    last_edit_hash varchar(64) NOT NULL,
    last_edited_at timestamptz NOT NULL,
    ingested_hash varchar(64),
    last_ingested_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_document_derived_state_workspace
    ON document_derived_state (workspace_id);
