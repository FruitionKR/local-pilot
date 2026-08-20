ALTER TABLE workspaces
    ALTER COLUMN ingest_lint_provider SET DEFAULT 'gemini',
    ALTER COLUMN ingest_lint_model SET DEFAULT 'gemini-3.1-flash-lite';
