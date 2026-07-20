#!/usr/bin/env bash
set -Eeuo pipefail

log() {
  printf '[front-down] %s\n' "$*"
}

stop_frontend() {
  local found="false"
  local pid

  if command -v lsof >/dev/null 2>&1; then
    while IFS= read -r pid; do
      [[ -n "$pid" ]] || continue
      found="true"
      log "프론트엔드 프로세스를 종료합니다: PID $pid"
      kill "$pid" >/dev/null 2>&1 || true
    done < <(lsof -tiTCP:3000 -sTCP:LISTEN 2>/dev/null || true)
  elif command -v fuser >/dev/null 2>&1; then
    if fuser 3000/tcp >/dev/null 2>&1; then
      found="true"
      log "3000 포트의 프론트엔드 프로세스를 종료합니다."
      fuser -k 3000/tcp >/dev/null 2>&1 || true
    fi
  else
    printf '[front-down] ERROR: lsof 또는 fuser 명령이 필요합니다.\n' >&2
    exit 1
  fi

  if [[ "$found" == "false" ]]; then
    log "실행 중인 프론트엔드 프로세스가 없습니다."
    return
  fi

  log "프론트엔드 종료 요청을 완료했습니다."
}

stop_frontend
