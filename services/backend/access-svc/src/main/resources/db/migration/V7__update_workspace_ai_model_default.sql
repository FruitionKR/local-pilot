ALTER TABLE workspaces
    ALTER COLUMN ingest_lint_model SET DEFAULT 'gpt-5-nano';

UPDATE workspaces
SET ingest_lint_provider = 'openai',
    ingest_lint_model = 'gpt-5-nano'
WHERE (ingest_lint_provider, ingest_lint_model) NOT IN (
    ('openai', 'gpt-5-nano'),
    ('gemini', 'gemini-2.5-flash-lite'),
    ('claude', 'claude-3-5-haiku-20241022')
);
