-- 이미 저장된 폐기 Gemini 모델만 공식 대체 모델로 변환한다.
UPDATE workspaces
SET ingest_lint_model = 'gemini-3.1-flash-lite'
WHERE ingest_lint_provider = 'gemini'
  AND ingest_lint_model = 'gemini-2.5-flash-lite';
