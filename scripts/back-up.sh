#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INFRA_DIR="$ROOT_DIR/infra"
BACKEND_DIR="$ROOT_DIR/backend"
ENV_FILE="$INFRA_DIR/.env"
ENV_EXAMPLE="$INFRA_DIR/.env.example"
COMPOSE_FILE="$INFRA_DIR/docker-compose.dev.yml"
BACKEND_PID=""

log() {
  printf '[back-up] %s\n' "$*"
}

fail() {
  printf '[back-up] ERROR: %s\n' "$*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "'$1' 명령을 찾을 수 없습니다. docs/local-runbook.md의 요구사항을 확인하세요."
}

cleanup() {
  if [[ -n "${BACKEND_PID:-}" ]] && kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
    kill "$BACKEND_PID" >/dev/null 2>&1 || true
  fi
}

ensure_env_file() {
  if [[ -f "$ENV_FILE" ]]; then
    return
  fi

  [[ -f "$ENV_EXAMPLE" ]] || fail "환경변수 예시 파일이 없습니다: $ENV_EXAMPLE"
  cp "$ENV_EXAMPLE" "$ENV_FILE"
  log "infra/.env가 없어 infra/.env.example에서 복사했습니다."
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

  docker info >/dev/null 2>&1 || fail "Docker daemon에 연결할 수 없습니다."
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
      return
    fi
  done

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$candidate" ]] && java_home_version "$candidate"; then
      printf '%s\n' "$candidate"
      return
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
      return
    fi
  done

  return 1
}

wait_for_postgres() {
  local status

  for _ in $(seq 1 60); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' fruition-postgresql-dev 2>/dev/null || true)"
    if [[ "$status" == "healthy" || "$status" == "running" ]]; then
      log "PostgreSQL 컨테이너 상태 확인: $status"
      return
    fi
    sleep 1
  done

  fail "PostgreSQL 컨테이너가 준비되지 않았습니다."
}

wait_for_backend() {
  for _ in $(seq 1 60); do
    if curl -fsS "http://localhost:8080/actuator/health" >/dev/null 2>&1; then
      log "백엔드 응답 확인: http://localhost:8080/actuator/health"
      return
    fi

    kill -0 "$BACKEND_PID" >/dev/null 2>&1 || fail "백엔드 프로세스가 시작 중 종료되었습니다."
    sleep 1
  done

  fail "백엔드 응답을 확인하지 못했습니다: http://localhost:8080/actuator/health"
}

main() {
  need_cmd curl
  ensure_env_file
  ensure_docker

  if curl -fsS "http://localhost:8080/actuator/health" >/dev/null 2>&1; then
    log "백엔드가 이미 실행 중입니다: http://localhost:8080"
    return
  fi

  local java21_home
  java21_home="$(find_java21_home)" || fail "Java 21을 찾지 못했습니다. JAVA_HOME_21을 지정하거나 JDK 21을 설치하세요."

  log "PostgreSQL과 MinIO를 시작합니다."
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d
  wait_for_postgres

  trap cleanup INT TERM EXIT

  log "백엔드를 시작합니다. Java 21: $java21_home"
  (
    cd "$BACKEND_DIR"
    ./gradlew bootRun -Porg.gradle.java.installations.paths="$java21_home"
  ) &
  BACKEND_PID="$!"

  wait_for_backend
  log "종료하려면 Ctrl-C를 누르거나 scripts/back-down.sh를 실행하세요."
  wait "$BACKEND_PID"
}

main "$@"
