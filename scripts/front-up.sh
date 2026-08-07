#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/services/frontend"
FRONTEND_PID=""

log() {
  printf '[front-up] %s\n' "$*"
}

fail() {
  printf '[front-up] ERROR: %s\n' "$*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "'$1' 명령을 찾을 수 없습니다."
}

cleanup() {
  if [[ -n "${FRONTEND_PID:-}" ]] && kill -0 "$FRONTEND_PID" >/dev/null 2>&1; then
    kill "$FRONTEND_PID" >/dev/null 2>&1 || true
  fi
}

wait_for_frontend() {
  for _ in $(seq 1 60); do
    if curl -fsS "http://localhost:3000" >/dev/null 2>&1; then
      log "프론트엔드 응답 확인: http://localhost:3000"
      return
    fi

    kill -0 "$FRONTEND_PID" >/dev/null 2>&1 || fail "프론트엔드 프로세스가 시작 중 종료되었습니다."
    sleep 1
  done

  fail "프론트엔드 응답을 확인하지 못했습니다: http://localhost:3000"
}

main() {
  need_cmd curl
  need_cmd node
  need_cmd npm

  if curl -fsS "http://localhost:3000" >/dev/null 2>&1; then
    log "프론트엔드가 이미 실행 중입니다: http://localhost:3000"
    return
  fi

  if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
    log "프론트엔드 의존성이 없어 npm install을 실행합니다."
    (cd "$FRONTEND_DIR" && npm install)
  fi

  trap cleanup INT TERM EXIT

  log "프론트엔드를 시작합니다."
  (
    cd "$FRONTEND_DIR"
    npm run dev
  ) &
  FRONTEND_PID="$!"

  wait_for_frontend
  log "종료하려면 Ctrl-C를 누르거나 scripts/front-down.sh를 실행하세요."
  wait "$FRONTEND_PID"
}

main "$@"
