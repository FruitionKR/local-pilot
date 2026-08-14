UPDATE workspaces
SET ingest_lint_model = 'claude-sonnet-5'
WHERE ingest_lint_provider = 'claude'
  AND ingest_lint_model = 'claude-haiku-4-5-20251001';
