import psycopg


def initialize_wiki_schema(conn: psycopg.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS documents (
            id TEXT PRIMARY KEY,
            filename TEXT NOT NULL,
            mime_type TEXT NOT NULL,
            byte_size BIGINT NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'processing',
            source_uri TEXT NOT NULL,
            extracted_text_uri TEXT,
            content_hash TEXT UNIQUE,
            uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            processed_at TIMESTAMPTZ,
            error_message TEXT
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS wiki_pages (
            id TEXT PRIMARY KEY,
            page_type TEXT NOT NULL,
            title TEXT NOT NULL,
            slug TEXT NOT NULL,
            summary TEXT NOT NULL DEFAULT '',
            markdown_uri TEXT NOT NULL,
            user_id TEXT NOT NULL DEFAULT 'local-user',
            workspace_id TEXT NOT NULL DEFAULT 'local-workspace',
            status TEXT NOT NULL DEFAULT 'active',
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    conn.execute(
        "ALTER TABLE wiki_pages ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT 'local-user'"
    )
    conn.execute(
        "ALTER TABLE wiki_pages ADD COLUMN IF NOT EXISTS workspace_id TEXT NOT NULL DEFAULT 'local-workspace'"
    )
    conn.execute(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS uq_wiki_pages_workspace_type_slug
        ON wiki_pages (user_id, workspace_id, page_type, slug)
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS document_wiki_links (
            document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
            wiki_page_id TEXT NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
            relation_type TEXT NOT NULL,
            confidence DOUBLE PRECISION,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            PRIMARY KEY (document_id, wiki_page_id, relation_type)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS source_blocks (
            document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
            block_id TEXT NOT NULL,
            text TEXT NOT NULL,
            PRIMARY KEY (document_id, block_id)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS wiki_page_links (
            from_page_id TEXT NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
            to_page_id TEXT NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
            link_type TEXT NOT NULL,
            label TEXT,
            confidence DOUBLE PRECISION,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            PRIMARY KEY (from_page_id, to_page_id, link_type)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS pipeline_runs (
            id UUID PRIMARY KEY,
            document_id TEXT REFERENCES documents(id) ON DELETE SET NULL,
            input_source TEXT NOT NULL,
            output_dir TEXT NOT NULL,
            mode TEXT NOT NULL,
            status TEXT NOT NULL,
            manifest JSONB,
            error TEXT,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            finished_at TIMESTAMPTZ
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS wiki_page_embeddings (
            page_id TEXT NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
            embedding_model TEXT NOT NULL,
            representation_hash TEXT NOT NULL,
            embedding_vector DOUBLE PRECISION[] NOT NULL,
            embedding_dimension INTEGER NOT NULL,
            status TEXT NOT NULL DEFAULT 'completed',
            error TEXT,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            PRIMARY KEY (page_id, embedding_model)
        )
        """
    )
    conn.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_wiki_page_embeddings_model_hash
        ON wiki_page_embeddings (embedding_model, representation_hash)
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS wiki_embedding_vectors (
            id TEXT PRIMARY KEY,
            embedding_model TEXT NOT NULL,
            representation_hash TEXT NOT NULL,
            representation_text TEXT NOT NULL,
            embedding_vector DOUBLE PRECISION[],
            embedding_dimension INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'pending',
            error TEXT,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            UNIQUE (embedding_model, representation_hash)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS wiki_embedding_units (
            id TEXT PRIMARY KEY,
            embedding_vector_id TEXT NOT NULL REFERENCES wiki_embedding_vectors(id) ON DELETE RESTRICT,
            page_id TEXT NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
            source_document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
            unit_type TEXT NOT NULL,
            block_refs TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
            text TEXT NOT NULL,
            weight DOUBLE PRECISION NOT NULL DEFAULT 1.0,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    conn.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_wiki_embedding_units_page
        ON wiki_embedding_units (page_id)
        """
    )
    conn.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_wiki_embedding_units_vector
        ON wiki_embedding_units (embedding_vector_id)
        """
    )
