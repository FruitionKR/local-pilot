ALTER TABLE workspaces
    ADD COLUMN ingest_lint_provider varchar(32) NOT NULL DEFAULT 'openai',
    ADD COLUMN ingest_lint_model varchar(128) NOT NULL DEFAULT 'gpt-4.1-mini';
