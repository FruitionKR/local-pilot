#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INFRA_DIR="$ROOT_DIR/infra"
SERVICES_DIR="$ROOT_DIR/services/backend"
FRONTEND_DIR="$ROOT_DIR/services/frontend"
ENV_FILE="$INFRA_DIR/.env"
ENV_EXAMPLE="$INFRA_DIR/.env.example"
COMPOSE_FILE="$INFRA_DIR/compose.infra.yml"
PIPELINE_COMPOSE_FILE="$INFRA_DIR/compose.ai.yml"
RUNTIME_DIR="$ROOT_DIR/.runtime"

DOCUMENT_PID=""
ACCESS_PID=""
FRONTEND_PID=""

source "$ROOT_DIR/scripts/lib/runtime.sh"

log() {
  printf '[dev-up] %s\n' "$*"
}

fail() {
  printf '[dev-up] ERROR: %s\n' "$*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "'$1' 명령을 찾을 수 없습니다. docs/script.md의 요구사항을 확인하세요."
}

cleanup() {
  if [[ -n "${FRONTEND_PID:-}" ]] && kill -0 "$FRONTEND_PID" >/dev/null 2>&1; then
    kill "$FRONTEND_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "${ACCESS_PID:-}" ]] && kill -0 "$ACCESS_PID" >/dev/null 2>&1; then
    kill "$ACCESS_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "${DOCUMENT_PID:-}" ]] && kill -0 "$DOCUMENT_PID" >/dev/null 2>&1; then
    kill "$DOCUMENT_PID" >/dev/null 2>&1 || true
  fi
  runtime_unregister "dev"
}

shutdown() {
  trap - INT TERM EXIT
  cleanup
  exit 0
}

trap shutdown INT TERM
trap cleanup EXIT

ensure_env_file() {
  if [[ -f "$ENV_FILE" ]]; then
    return
  fi

  [[ -f "$ENV_EXAMPLE" ]] || fail "환경변수 예시 파일이 없습니다: $ENV_EXAMPLE"
  cp "$ENV_EXAMPLE" "$ENV_FILE"
  log "infra/.env가 없어 infra/.env.example에서 복사했습니다. LLM 기능을 쓰려면 API 키를 채워야 합니다."
}

ensure_docker() {
  need_cmd docker

  if docker info >/dev/null 2>&1; then
    return
  fi

  local colima_socket="$HOME/.colima/default/docker.sock"
  if [[ -S "$colima_socket" ]]; then
    export DOCKER_HOST="unix://$colima_socket"
    if docker info >/dev/null 2>&1; then
      log "Colima Docker socket을 사용합니다: $colima_socket"
      return
    fi
  fi

  if command -v colima >/dev/null 2>&1; then
    log "Docker daemon에 연결할 수 없어 Colima를 시작합니다."
    colima start
  fi

  if docker info >/dev/null 2>&1; then
    return
  fi

  if [[ -S "$colima_socket" ]]; then
    export DOCKER_HOST="unix://$colima_socket"
  fi

  docker info >/dev/null 2>&1 || fail "Docker daemon에 연결할 수 없습니다. Docker Desktop 또는 Colima가 실행 중인지 확인하세요."
}

java_home_version() {
  local home="$1"
  [[ -x "$home/bin/java" ]] || return 1
  "$home/bin/java" -version 2>&1 | head -n 1 | grep -Eq 'version "21\.|openjdk version "21\.'
}

find_java21_home() {
  local candidate

  for candidate in "${JAVA_HOME_21:-}" "${JAVA_HOME:-}"; do
    if [[ -n "$candidate" ]] && java_home_version "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$candidate" ]] && java_home_version "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi

  local common_paths=(
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
    "/usr/lib/jvm/temurin-21-jdk-amd64"
    "/usr/lib/jvm/java-21-openjdk"
    "/usr/lib/jvm/java-21-openjdk-amd64"
  )

  for candidate in "${common_paths[@]}"; do
    if [[ -d "$candidate" ]] && java_home_version "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

wait_for_url() {
  local url="$1"
  local label="$2"
  local attempts="${3:-60}"
  local pid="${4:-}"

  for _ in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      log "$label 응답 확인: $url"
      return 0
    fi
    if [[ -n "$pid" ]] && ! kill -0 "$pid" >/dev/null 2>&1; then
      fail "$label 프로세스가 시작 중 종료되었습니다."
    fi
    sleep 1
  done

  fail "$label 응답을 확인하지 못했습니다: $url"
}

wait_for_postgres() {
  local status

  for _ in $(seq 1 60); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' fruition-postgresql-dev 2>/dev/null || true)"
    if [[ "$status" == "healthy" || "$status" == "running" ]]; then
      log "PostgreSQL 컨테이너 상태 확인: $status"
      return 0
    fi
    sleep 1
  done

  fail "PostgreSQL 컨테이너가 준비되지 않았습니다."
}

cleanup_stale_pipeline_orphans() {
  local container_ids

  container_ids="$(docker ps -aq \
    --filter "label=com.docker.compose.project=fruition-mvp-dev" \
    --filter "label=com.docker.compose.service=pipeline-api" \
    --filter "status=created" \
    --filter "status=exited" \
    --filter "status=dead")"

  if [[ -z "$container_ids" ]]; then
    return
  fi

  log "중지된 pipeline-api 컨테이너를 정리합니다."
  docker rm $container_ids >/dev/null
}

start_infra() {
  log "PostgreSQL과 MinIO를 시작합니다."
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d
  wait_for_postgres
}

start_pipeline() {
  log "Pipeline API를 시작합니다."
  cleanup_stale_pipeline_orphans
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$PIPELINE_COMPOSE_FILE" build pipeline-api
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -f "$PIPELINE_COMPOSE_FILE" up -d --no-build pipeline-api ingest-worker query-task-worker agent-task-worker maintenance-task-worker edit-event-consumer pipeline-agent-worker
  wait_for_url "http://localhost:8000/health" "Pipeline API" 120
}

start_backend() {
  local java21_home="$1"

  # flyway migration은 document-svc가 소유하므로 document-svc를 먼저 띄운다.
  log "document-svc를 시작합니다. Java 21: $java21_home"
  (
    cd "$SERVICES_DIR"
    SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}" \
      ./gradlew :document-svc:bootRun -Porg.gradle.java.installations.paths="$java21_home"
  ) &
  DOCUMENT_PID="$!"

  wait_for_url "http://localhost:8080/actuator/health" "document-svc" 60 "$DOCUMENT_PID"

  log "access-svc를 시작합니다. Java 21: $java21_home"
  (
    cd "$SERVICES_DIR"
    SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}" \
      ./gradlew :access-svc:bootRun -Porg.gradle.java.installations.paths="$java21_home"
  ) &
  ACCESS_PID="$!"

  wait_for_url "http://localhost:8081/actuator/health" "access-svc" 60 "$ACCESS_PID"
}

start_frontend() {
  need_cmd node
  need_cmd npm

  if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
    log "프론트엔드 의존성이 없어 npm install을 실행합니다."
    (cd "$FRONTEND_DIR" && npm install)
  fi

  log "프론트엔드를 시작합니다."
  (
    cd "$FRONTEND_DIR"
    npm run dev
  ) &
  FRONTEND_PID="$!"

  wait_for_url "http://localhost:3000" "프론트엔드" 60 "$FRONTEND_PID"
}

ensure_ports_available() {
  local port

  for port in 3000 8000 8080 8081; do
    if runtime_port_in_use "$port"; then
      fail "다른 실행 환경이 이미 포트를 사용 중입니다: $port. 해당 환경을 먼저 종료하세요."
    fi
  done
}

main() {
  "$ROOT_DIR/scripts/bootstrap.sh"

  need_cmd curl
  ensure_env_file
  ensure_docker

  if runtime_is_running "dev" "dev-up.sh"; then
    log "이 프로젝트의 통합 개발 환경이 이미 실행 중입니다."
    return
  fi
  ensure_ports_available

  local java21_home
  java21_home="$(find_java21_home)" || fail "Java 21을 찾지 못했습니다. JAVA_HOME_21을 지정하거나 JDK 21을 설치하세요."

  runtime_register "dev" "dev-up.sh" || fail "통합 개발 환경 supervisor 상태를 등록하지 못했습니다."

  start_infra
  start_backend "$java21_home"
  start_pipeline
  start_frontend

  cat <<'INFO'

[dev-up] 로컬 개발 서버가 실행 중입니다.
  - Frontend:     http://localhost:3000
  - Document-svc: http://localhost:8080
  - Access-svc:   http://localhost:8081
  - Pipeline:     http://localhost:8000
  - Swagger:      http://localhost:8080/swagger-ui.html
  - MinIO:        http://localhost:9001

[dev-up] 종료하려면 Ctrl-C를 누르세요. PostgreSQL/MinIO/pipeline 컨테이너는 유지됩니다.
INFO

  wait "$DOCUMENT_PID" "$ACCESS_PID" "$FRONTEND_PID"
}

main "$@"
