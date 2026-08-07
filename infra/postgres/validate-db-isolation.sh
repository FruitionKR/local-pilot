#!/bin/sh
set -eu

log() {
  printf '[db-isolation] %s\n' "$*"
}

fail() {
  printf '[db-isolation] ERROR: %s\n' "$*" >&2
  exit 1
}

validate_identifier() {
  value="$1"
  label="$2"

  case "$value" in
    ''|[0-9]*|*[!a-z0-9_]*) fail "$label 값이 PostgreSQL 식별자 형식이 아닙니다: $value" ;;
  esac
}

psql_as() {
  password="$1"
  user="$2"
  database="$3"
  shift 3
  PGPASSWORD="$password" psql --host "$POSTGRES_HOST" --port "$POSTGRES_PORT" \
    --username "$user" --dbname "$database" --set=ON_ERROR_STOP=1 "$@"
}

assert_role_restricted() {
  role="$1"
  flags="$(psql_as "$POSTGRES_ADMIN_PASSWORD" "$POSTGRES_ADMIN_USER" postgres \
    --tuples-only --no-align --field-separator='|' \
    --command="SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication FROM pg_roles WHERE rolname = '$role'")"
  [ -n "$flags" ] || fail "$role 계정이 없습니다. 기존 PostgreSQL volume이면 로컬 데이터를 확인한 뒤 volume을 초기화하세요."
  [ "$flags" = 'f|f|f|f' ] || fail "$role 계정 권한이 제한되지 않았습니다: $flags"
}

create_probe() {
  database="$1"
  migration_user="$2"
  migration_password="$3"
  psql_as "$migration_password" "$migration_user" "$database" \
    --command='DROP TABLE IF EXISTS public.__db_isolation_probe; CREATE TABLE public.__db_isolation_probe (id integer PRIMARY KEY, value text NOT NULL);' \
    >/dev/null
}

drop_probe() {
  database="$1"
  migration_user="$2"
  migration_password="$3"
  psql_as "$migration_password" "$migration_user" "$database" \
    --command='DROP TABLE IF EXISTS public.__db_isolation_probe;' >/dev/null 2>&1 || true
}

cleanup() {
  drop_probe "$ACCESS_DB_NAME" "$ACCESS_DB_MIGRATION_USER" "$ACCESS_DB_MIGRATION_PASSWORD"
  drop_probe "$CORE_DB_NAME" "$CORE_DB_MIGRATION_USER" "$CORE_DB_MIGRATION_PASSWORD"
  drop_probe "$AI_DB_NAME" "$AI_DB_MIGRATION_USER" "$AI_DB_MIGRATION_PASSWORD"
}

assert_own_dml() {
  database="$1"
  runtime_user="$2"
  runtime_password="$3"

  psql_as "$runtime_password" "$runtime_user" "$database" \
    --command="INSERT INTO public.__db_isolation_probe (id, value) VALUES (1, '$runtime_user'); UPDATE public.__db_isolation_probe SET value = 'verified' WHERE id = 1; DELETE FROM public.__db_isolation_probe WHERE id = 1;" \
    >/dev/null || fail "$runtime_user 계정의 $database DML이 실패했습니다."

  if psql_as "$runtime_password" "$runtime_user" "$database" \
    --command='CREATE TABLE public.__db_runtime_ddl_probe (id integer);' >/dev/null 2>&1; then
    psql_as "$runtime_password" "$runtime_user" "$database" \
      --command='DROP TABLE public.__db_runtime_ddl_probe;' >/dev/null 2>&1 || true
    fail "$runtime_user 계정이 $database public schema에서 CREATE를 수행했습니다."
  fi
}

assert_cross_write_denied() {
  source_user="$1"
  source_password="$2"
  target_database="$3"

  if psql_as "$source_password" "$source_user" "$target_database" \
    --command="INSERT INTO public.__db_isolation_probe (id, value) VALUES (99, '$source_user');" >/dev/null 2>&1; then
    fail "$source_user 계정이 다른 서비스 DB인 $target_database에 썼습니다."
  fi
}

assert_public_create_revoked() {
  database="$1"
  has_public_create="$(psql_as "$POSTGRES_ADMIN_PASSWORD" "$POSTGRES_ADMIN_USER" "$database" \
    --tuples-only --no-align \
    --command="SELECT EXISTS (SELECT 1 FROM pg_namespace n CROSS JOIN LATERAL aclexplode(n.nspacl) acl WHERE n.nspname = 'public' AND acl.grantee = 0 AND acl.privilege_type = 'CREATE')")"
  [ "$has_public_create" = 'f' ] || fail "$database public schema의 PUBLIC CREATE 권한이 남아 있습니다."
}

trap cleanup EXIT

for variable_name in \
  ACCESS_DB_NAME ACCESS_DB_RUNTIME_USER ACCESS_DB_MIGRATION_USER \
  CORE_DB_NAME CORE_DB_RUNTIME_USER CORE_DB_MIGRATION_USER \
  AI_DB_NAME AI_DB_RUNTIME_USER AI_DB_MIGRATION_USER; do
  eval "value=\${$variable_name}"
  validate_identifier "$value" "$variable_name"
done

for role in \
  "$ACCESS_DB_RUNTIME_USER" "$ACCESS_DB_MIGRATION_USER" \
  "$CORE_DB_RUNTIME_USER" "$CORE_DB_MIGRATION_USER" \
  "$AI_DB_RUNTIME_USER" "$AI_DB_MIGRATION_USER"; do
  assert_role_restricted "$role"
done

create_probe "$ACCESS_DB_NAME" "$ACCESS_DB_MIGRATION_USER" "$ACCESS_DB_MIGRATION_PASSWORD"
create_probe "$CORE_DB_NAME" "$CORE_DB_MIGRATION_USER" "$CORE_DB_MIGRATION_PASSWORD"
create_probe "$AI_DB_NAME" "$AI_DB_MIGRATION_USER" "$AI_DB_MIGRATION_PASSWORD"

assert_own_dml "$ACCESS_DB_NAME" "$ACCESS_DB_RUNTIME_USER" "$ACCESS_DB_RUNTIME_PASSWORD"
assert_own_dml "$CORE_DB_NAME" "$CORE_DB_RUNTIME_USER" "$CORE_DB_RUNTIME_PASSWORD"
assert_own_dml "$AI_DB_NAME" "$AI_DB_RUNTIME_USER" "$AI_DB_RUNTIME_PASSWORD"

assert_public_create_revoked "$ACCESS_DB_NAME"
assert_public_create_revoked "$CORE_DB_NAME"
assert_public_create_revoked "$AI_DB_NAME"

assert_cross_write_denied "$ACCESS_DB_RUNTIME_USER" "$ACCESS_DB_RUNTIME_PASSWORD" "$CORE_DB_NAME"
assert_cross_write_denied "$ACCESS_DB_MIGRATION_USER" "$ACCESS_DB_MIGRATION_PASSWORD" "$CORE_DB_NAME"
assert_cross_write_denied "$CORE_DB_RUNTIME_USER" "$CORE_DB_RUNTIME_PASSWORD" "$AI_DB_NAME"
assert_cross_write_denied "$CORE_DB_MIGRATION_USER" "$CORE_DB_MIGRATION_PASSWORD" "$AI_DB_NAME"
assert_cross_write_denied "$AI_DB_RUNTIME_USER" "$AI_DB_RUNTIME_PASSWORD" "$ACCESS_DB_NAME"
assert_cross_write_denied "$AI_DB_MIGRATION_USER" "$AI_DB_MIGRATION_PASSWORD" "$ACCESS_DB_NAME"

log 'runtime/migration 비superuser, own DML 성공, runtime CREATE 거부, 타 DB write 거부를 확인했습니다.'
