#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/docker-compose.dev.yml"

REMOVE_VOLUMES="false"

log() {
  printf '[dev-down] %s\n' "$*"
}

fail() {
  printf '[dev-down] ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: scripts/dev-down.sh [--volumes]

Options:
  --volumes   PostgreSQL, MinIO 로컬 볼륨까지 삭제합니다.
  -h, --help  도움말을 출력합니다.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --volumes)
      REMOVE_VOLUMES="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "알 수 없는 옵션입니다: $1"
      ;;
  esac
done

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "'$1' 명령을 찾을 수 없습니다."
}

configure_docker_host() {
  if docker info >/dev/null 2>&1; then
    return
  fi

  local colima_socket="$HOME/.colima/default/docker.sock"
  if [[ -S "$colima_socket" ]]; then
    export DOCKER_HOST="unix://$colima_socket"
  fi
}

stop_port_processes() {
  local port pid

  if ! command -v lsof >/dev/null 2>&1 && ! command -v fuser >/dev/null 2>&1; then
    log "lsof 또는 fuser가 없어 포트 기반 앱 프로세스 종료는 건너뜁니다."
    return
  fi

  for port in 3000 8080; do
    if command -v lsof >/dev/null 2>&1; then
      while IFS= read -r pid; do
        [[ -n "$pid" ]] || continue
        log "포트 $port 사용 프로세스 종료: PID $pid"
        kill "$pid" >/dev/null 2>&1 || true
      done < <(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
    else
      log "포트 $port 사용 프로세스를 fuser로 종료합니다."
      fuser -k "${port}/tcp" >/dev/null 2>&1 || true
    fi
  done
}

down_infra() {
  local args=(-f "$COMPOSE_FILE" down)

  if [[ "$REMOVE_VOLUMES" == "true" ]]; then
    args+=(-v)
  fi

  if docker info >/dev/null 2>&1; then
    log "PostgreSQL과 MinIO 컨테이너를 종료합니다."
    docker compose "${args[@]}"
  else
    log "Docker daemon에 연결할 수 없어 인프라 종료는 건너뜁니다."
  fi
}

main() {
  need_cmd docker

  configure_docker_host
  stop_port_processes
  down_infra

  if [[ "$REMOVE_VOLUMES" == "true" ]]; then
    log "전체 종료 완료: 앱 프로세스와 인프라 컨테이너, 로컬 볼륨을 정리했습니다."
  else
    log "전체 종료 완료: 앱 프로세스와 인프라 컨테이너를 정리했습니다. 로컬 볼륨은 유지됩니다."
  fi
}

main "$@"
