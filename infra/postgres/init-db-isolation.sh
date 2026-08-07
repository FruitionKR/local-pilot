#!/usr/bin/env bash
set -Eeuo pipefail

validate_identifier() {
  local value="$1"
  local label="$2"

  [[ "$value" =~ ^[a-z_][a-z0-9_]*$ ]] || {
    printf '[db-init] ERROR: %s는 PostgreSQL 식별자 형식이어야 합니다: %s\n' "$label" "$value" >&2
    exit 1
  }
}

create_role() {
  local role="$1"
  local password="$2"

  psql --username "$POSTGRES_USER" --dbname postgres --set=ON_ERROR_STOP=1 \
    --set=role="$role" --set=password="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', :'role', :'password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role')
\gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', :'role', :'password')
\gexec
SQL
}

create_database() {
  local database="$1"
  local migration_role="$2"
  local runtime_role="$3"

  psql --username "$POSTGRES_USER" --dbname postgres --set=ON_ERROR_STOP=1 \
    --set=database="$database" --set=migration_role="$migration_role" --set=runtime_role="$runtime_role" <<'SQL'
SELECT format('CREATE DATABASE %I OWNER %I', :'database', :'migration_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'database')
\gexec
SELECT format('ALTER DATABASE %I OWNER TO %I', :'database', :'migration_role')
\gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'database')
\gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'database', :'migration_role')
\gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'database', :'runtime_role')
\gexec
SQL

  psql --username "$POSTGRES_USER" --dbname "$database" --set=ON_ERROR_STOP=1 \
    --set=migration_role="$migration_role" --set=runtime_role="$runtime_role" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SELECT format('ALTER SCHEMA public OWNER TO %I', :'migration_role')
\gexec
SELECT format('GRANT USAGE, CREATE ON SCHEMA public TO %I', :'migration_role')
\gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'runtime_role')
\gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', :'runtime_role')
\gexec
SELECT format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO %I', :'runtime_role')
\gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'migration_role', :'runtime_role')
\gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I', :'migration_role', :'runtime_role')
\gexec
SQL
}

for variable_name in \
  ACCESS_DB_NAME ACCESS_DB_RUNTIME_USER ACCESS_DB_MIGRATION_USER \
  CORE_DB_NAME CORE_DB_RUNTIME_USER CORE_DB_MIGRATION_USER \
  AI_DB_NAME AI_DB_RUNTIME_USER AI_DB_MIGRATION_USER; do
  validate_identifier "${!variable_name}" "$variable_name"
done

create_role "$ACCESS_DB_RUNTIME_USER" "$ACCESS_DB_RUNTIME_PASSWORD"
create_role "$ACCESS_DB_MIGRATION_USER" "$ACCESS_DB_MIGRATION_PASSWORD"
create_role "$CORE_DB_RUNTIME_USER" "$CORE_DB_RUNTIME_PASSWORD"
create_role "$CORE_DB_MIGRATION_USER" "$CORE_DB_MIGRATION_PASSWORD"
create_role "$AI_DB_RUNTIME_USER" "$AI_DB_RUNTIME_PASSWORD"
create_role "$AI_DB_MIGRATION_USER" "$AI_DB_MIGRATION_PASSWORD"

create_database "$ACCESS_DB_NAME" "$ACCESS_DB_MIGRATION_USER" "$ACCESS_DB_RUNTIME_USER"
create_database "$CORE_DB_NAME" "$CORE_DB_MIGRATION_USER" "$CORE_DB_RUNTIME_USER"
create_database "$AI_DB_NAME" "$AI_DB_MIGRATION_USER" "$AI_DB_RUNTIME_USER"

# 전환기: ai 테이블(pipeline_runs·임베딩)이 core_db에 동거하므로 ai_runtime에 core_db DML을 허용한다.
# 물리 ai_db 이전이 끝나면 이 블록을 제거할 것 (docs/msa/current-architecture.md §4 참조).
psql --username "$POSTGRES_USER" --dbname postgres --set=ON_ERROR_STOP=1 \
  --set=database="$CORE_DB_NAME" --set=role="$AI_DB_RUNTIME_USER" <<'SQL'
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'database', :'role')
\gexec
SQL
psql --username "$POSTGRES_USER" --dbname "$CORE_DB_NAME" --set=ON_ERROR_STOP=1 \
  --set=migration_role="$CORE_DB_MIGRATION_USER" --set=role="$AI_DB_RUNTIME_USER" <<'SQL'
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'role')
\gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', :'role')
\gexec
SELECT format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO %I', :'role')
\gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', :'migration_role', :'role')
\gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO %I', :'migration_role', :'role')
\gexec
SQL

printf '[db-init] access/core/ai database와 runtime/migration 계정 구성을 완료했습니다.\n'
