#!/usr/bin/env bash

runtime_pid_file() {
  local name="$1"
  printf '%s/%s.pid\n' "$RUNTIME_DIR" "$name"
}

runtime_port_in_use() {
  local port="$1"

  if command -v lsof >/dev/null 2>&1; then
    lsof -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
    return
  fi
  if command -v nc >/dev/null 2>&1; then
    nc -z 127.0.0.1 "$port" >/dev/null 2>&1
    return
  fi
  curl --silent --output /dev/null --connect-timeout 1 "http://127.0.0.1:$port/" >/dev/null 2>&1
}

runtime_process_matches() {
  local pid="$1"
  local owner_script="$2"
  local command

  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" >/dev/null 2>&1 || return 1
  command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  [[ "$command" == *"$owner_script"* ]]
}

runtime_is_running() {
  local name="$1"
  local owner_script="$2"
  local file pid

  file="$(runtime_pid_file "$name")"
  [[ -f "$file" ]] || return 1
  read -r pid < "$file" || true

  if runtime_process_matches "$pid" "$owner_script"; then
    return 0
  fi

  rm -f "$file"
  return 1
}

runtime_register() {
  local name="$1"
  local owner_script="$2"
  local file

  if runtime_is_running "$name" "$owner_script"; then
    return 1
  fi

  mkdir -p "$RUNTIME_DIR"
  file="$(runtime_pid_file "$name")"
  printf '%s\n' "$$" > "$file"
}

runtime_unregister() {
  local name="$1"
  local file pid

  file="$(runtime_pid_file "$name")"
  [[ -f "$file" ]] || return 0
  read -r pid < "$file" || true
  if [[ "$pid" == "$$" ]]; then
    rm -f "$file"
  fi
}

runtime_stop() {
  local name="$1"
  local owner_script="$2"
  local label="$3"
  local prefix="$4"
  local file pid

  file="$(runtime_pid_file "$name")"
  if ! runtime_is_running "$name" "$owner_script"; then
    printf '[%s] 관리 중인 %s 프로세스가 없습니다.\n' "$prefix" "$label"
    return 0
  fi

  read -r pid < "$file"
  printf '[%s] %s supervisor를 종료합니다: PID %s\n' "$prefix" "$label" "$pid"
  kill -TERM "$pid"

  for _ in $(seq 1 10); do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      rm -f "$file"
      return 0
    fi
    sleep 1
  done

  printf '[%s] ERROR: %s supervisor가 종료되지 않았습니다: PID %s\n' "$prefix" "$label" "$pid" >&2
  return 1
}
