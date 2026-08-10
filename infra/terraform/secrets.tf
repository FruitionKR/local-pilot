# 앱 시크릿 원본 (k8s/base/secret.yaml 평문 대체).
# ExternalSecrets Operator가 이 secret을 읽어 fruition namespace의 fruition-secret으로 동기화한다.
# 초기값만 Terraform이 넣고 이후 값 관리는 콘솔/CLI — ignore_changes로 덮어쓰지 않는다.
# DB 계정 비밀번호는 init-db-isolation.sh 실행 시 여기 값과 동일하게 넣어야 한다 (README 절차).
resource "aws_secretsmanager_secret" "app" {
  name = "${var.project}/app"
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id

  secret_string = jsonencode({
    # --- PostgreSQL (access RDS / core RDS 분리) ---
    ACCESS_DB_RUNTIME_PASSWORD   = random_password.db_role["access_runtime"].result
    ACCESS_DB_MIGRATION_PASSWORD = random_password.db_role["access_migration"].result
    CORE_DB_RUNTIME_PASSWORD     = random_password.db_role["core_runtime"].result
    CORE_DB_MIGRATION_PASSWORD   = random_password.db_role["core_migration"].result
    AI_DB_RUNTIME_PASSWORD       = random_password.db_role["ai_runtime"].result
    AI_DB_MIGRATION_PASSWORD     = random_password.db_role["ai_migration"].result
    # ai-svc runtime 저장소는 core RDS 인스턴스의 ai_db에 격리한다.
    AI_DATABASE_URL     = "postgresql://ai_runtime:${random_password.db_role["ai_runtime"].result}@${aws_db_instance.core.address}:5432/ai_db"
    AI_DB_MIGRATION_URL = "postgresql://ai_migration:${random_password.db_role["ai_migration"].result}@${aws_db_instance.core.address}:5432/ai_db"
    # --- MongoDB (문서 편집 상태 원본 — Atlas 연결 문자열로 교체) ---
    DOCUMENT_MONGODB_URI = "CHANGE_ME_MONGODB_ATLAS_URI"
    # --- 스토리지·인증 ---
    S3_ACCESS_KEY           = aws_iam_access_key.app.id
    S3_SECRET_KEY           = aws_iam_access_key.app.secret
    JWT_SECRET              = "CHANGE_ME_32BYTES_MIN"
    INTERNAL_CALLBACK_TOKEN = "CHANGE_ME"
    AGENT_INTERNAL_TOKEN    = "CHANGE_ME"
    LLM_API_KEY             = "CHANGE_ME"
    LANGSMITH_API_KEY       = ""
    TAVILY_API_KEY          = ""
  })

  lifecycle {
    ignore_changes = [secret_string]
  }
}
