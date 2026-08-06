#!/usr/bin/env bash
set -Eeuo pipefail

log() {
  printf '[back-down] %s\n' "$*"
}

stop_backend() {
  local found="false"
  local pid
  local port

  if command -v lsof >/dev/null 2>&1; then
    # document-svc(8080)·access-svc(8081) 둘 다 종료한다.
    for port in 8080 8081; do
      while IFS= read -r pid; do
        [[ -n "$pid" ]] || continue
        found="true"
        log "백엔드 프로세스를 종료합니다: PID $pid (port $port)"
        kill "$pid" >/dev/null 2>&1 || true
      done < <(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)
    done
  elif command -v fuser >/dev/null 2>&1; then
    for port in 8080 8081; do
      if fuser "$port"/tcp >/dev/null 2>&1; then
        found="true"
        log "$port 포트의 백엔드 프로세스를 종료합니다."
        fuser -k "$port"/tcp >/dev/null 2>&1 || true
      fi
    done
  else
    printf '[back-down] ERROR: lsof 또는 fuser 명령이 필요합니다.\n' >&2
    exit 1
  fi

  if [[ "$found" == "false" ]]; then
    log "실행 중인 백엔드 프로세스가 없습니다."
    return
  fi

  log "백엔드 종료 요청을 완료했습니다. PostgreSQL과 MinIO는 유지됩니다."
}

stop_backend
