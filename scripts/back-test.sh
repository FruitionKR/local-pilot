#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$ROOT_DIR/services/backend"

source "$ROOT_DIR/scripts/lib/runtime.sh"

java21_home="$(find_java21_home)" || {
  printf '[back-test] ERROR: Java 21을 찾지 못했습니다. JAVA_HOME_21을 지정하거나 JDK 21을 설치하세요.\n' >&2
  exit 1
}

if [[ $# -eq 0 ]]; then
  set -- :java-shared:test :access-svc:test :document-svc:test --no-daemon
fi

printf '[back-test] Java 21: %s\n' "$java21_home"
cd "$BACKEND_DIR"
JAVA_HOME="$java21_home" exec ./gradlew "$@" \
  -Porg.gradle.java.installations.paths="$java21_home"
