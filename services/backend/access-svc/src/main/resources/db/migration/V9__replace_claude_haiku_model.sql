UPDATE workspaces
SET ingest_lint_model = 'claude-haiku-4-5-20251001'
WHERE ingest_lint_provider = 'claude'
  AND ingest_lint_model = 'claude-3-5-haiku-20241022';
