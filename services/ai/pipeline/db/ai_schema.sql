-- ai_db 스키마 (python/ai-svc 소유).
-- Flyway가 아닌 pipeline 부트스트랩(AI_DB_MIGRATION_URL)이 적용하며, 전부 멱등(IF NOT EXISTS)이다.

CREATE TABLE IF NOT EXISTS pipeline_runs (
    id uuid PRIMARY KEY,
    document_id varchar(255),
    user_id varchar(255),
    workspace_id varchar(255),
    input_source text NOT NULL,
    output_dir text NOT NULL,
    mode text NOT NULL,
    status text NOT NULL,
    manifest jsonb,
    error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

ALTER TABLE pipeline_runs ADD COLUMN IF NOT EXISTS user_id varchar(255);
ALTER TABLE pipeline_runs ADD COLUMN IF NOT EXISTS workspace_id varchar(255);
ALTER TABLE pipeline_runs ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_pipeline_runs_document
    ON pipeline_runs (document_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_pipeline_runs_workspace_status
    ON pipeline_runs (workspace_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS wiki_pages (
    id varchar(255) PRIMARY KEY,
    page_type varchar(255) NOT NULL CHECK (page_type IN ('source', 'concept')),
    title varchar(255) NOT NULL,
    slug varchar(255) NOT NULL,
    summary text,
    markdown_uri varchar(255),
    user_id varchar(255) NOT NULL,
    workspace_id varchar(255) NOT NULL,
    status varchar(255) NOT NULL CHECK (status IN ('draft', 'active', 'failed', 'deleted')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_wiki_pages_workspace_type_slug
        UNIQUE (user_id, workspace_id, page_type, slug)
);

CREATE INDEX IF NOT EXISTS idx_wiki_pages_workspace
    ON wiki_pages (workspace_id, status, updated_at DESC);

CREATE TABLE IF NOT EXISTS document_wiki_links (
    document_id varchar(255) NOT NULL,
    wiki_page_id varchar(255) NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
    relation_type varchar(255) NOT NULL CHECK (relation_type IN ('source_of', 'extracted_concept')),
    confidence double precision,
    workspace_id varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (document_id, relation_type, wiki_page_id)
);

CREATE INDEX IF NOT EXISTS idx_document_wiki_links_workspace
    ON document_wiki_links (workspace_id);

CREATE TABLE IF NOT EXISTS wiki_page_links (
    from_page_id varchar(255) NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
    to_page_id varchar(255) NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
    link_type varchar(255) NOT NULL,
    label varchar(255),
    confidence double precision,
    workspace_id varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (from_page_id, link_type, to_page_id)
);

CREATE INDEX IF NOT EXISTS idx_wiki_page_links_workspace
    ON wiki_page_links (workspace_id);

CREATE TABLE IF NOT EXISTS source_blocks (
    document_id varchar(255) NOT NULL,
    block_id varchar(255) NOT NULL,
    text text NOT NULL,
    PRIMARY KEY (block_id, document_id)
);

CREATE TABLE IF NOT EXISTS wiki_page_embeddings (
    page_id varchar(255) NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
    embedding_model text NOT NULL,
    representation_hash text NOT NULL,
    embedding_vector double precision[] NOT NULL,
    embedding_dimension integer NOT NULL,
    status text NOT NULL DEFAULT 'completed',
    error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (page_id, embedding_model)
);

CREATE INDEX IF NOT EXISTS idx_wiki_page_embeddings_model_hash
    ON wiki_page_embeddings (embedding_model, representation_hash);

CREATE TABLE IF NOT EXISTS wiki_embedding_vectors (
    id text PRIMARY KEY,
    embedding_model text NOT NULL,
    representation_hash text NOT NULL,
    representation_text text NOT NULL,
    embedding_vector double precision[],
    embedding_dimension integer NOT NULL DEFAULT 0,
    status text NOT NULL DEFAULT 'pending',
    error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (embedding_model, representation_hash)
);

CREATE TABLE IF NOT EXISTS wiki_embedding_units (
    id text PRIMARY KEY,
    embedding_vector_id text NOT NULL REFERENCES wiki_embedding_vectors(id) ON DELETE RESTRICT,
    page_id varchar(255) NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
    source_document_id varchar(255) NOT NULL,
    unit_type text NOT NULL,
    block_refs text[] NOT NULL DEFAULT ARRAY[]::text[],
    text text NOT NULL,
    weight double precision NOT NULL DEFAULT 1.0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_wiki_embedding_units_page
    ON wiki_embedding_units (page_id);

CREATE INDEX IF NOT EXISTS idx_wiki_embedding_units_vector
    ON wiki_embedding_units (embedding_vector_id);

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
