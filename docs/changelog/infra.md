# Changelog — Infra

로컬 개발 인프라 변경 이력입니다. 날짜 역순으로 기록합니다.

---

## 2026-07-01

### chore: LangSmith tracing과 evaluator graph 환경변수 추가

**배경**

로컬 pipeline API에서 LangSmith tracing과 query evaluator graph 재시도 횟수를 같은 compose 설정으로 관리해야 했습니다.

**변경된 것**

- `infra/.env.example`에 `LANGSMITH_TRACING`, `LANGSMITH_API_KEY`, `LANGSMITH_PROJECT`, `LANGSMITH_ENDPOINT`, `QUERY_EVALUATOR_MODE`, `QUERY_EVALUATOR_MAX_ATTEMPTS` 예시를 추가했습니다.
- `infra/docker-compose.pipeline.yml`에서 LangSmith와 query evaluator 환경변수를 pipeline container로 전달하도록 추가했습니다.

**검증**

- `docker compose --env-file infra/.env -f infra/docker-compose.dev.yml -f infra/docker-compose.pipeline.yml up -d --build pipeline-api`로 pipeline container 재빌드 확인.
- 컨테이너 내부에서 `LANGSMITH_TRACING=true`, `QUERY_EVALUATOR_MODE=llm`, `QUERY_EVALUATOR_MAX_ATTEMPTS=2` 반영 확인.

## 2026-06-28

### chore: pipeline query 보강 설정 추가

**배경**

Query evaluator, web search 보강, 내부 근거 관련도 기준을 로컬 pipeline API에서 환경변수로 제어할 수 있어야 했습니다.

**변경된 것**

- `infra/docker-compose.pipeline.yml` — `QUERY_EVALUATOR_MODE`, `QUERY_WEB_SEARCH_MODE`, `QUERY_WEB_SEARCH_MAX_RESULTS`, `QUERY_WEB_SEARCH_TIMEOUT_SECONDS`, `QUERY_MIN_INTERNAL_RELEVANCE_SCORE` 환경변수 추가
- `infra/docker-compose.pipeline.yml` — `TAVILY_API_KEY`를 외부 환경변수로 주입할 수 있게 추가

**검증**

- `PYTHONPATH=llmPipeline llmPipeline/.venv/bin/python -m pytest llmPipeline/tests -q`

---

## 2026-06-26

### chore: 로컬 bootstrap 스크립트 추가

**배경**

처음 저장소를 실행하는 컴퓨터에서 필요한 프로젝트 의존성을 매번 수동으로 확인하고 설치해야 했습니다.

**변경된 것**

- `scripts/bootstrap.sh` — 필수 명령과 Node.js/npm/Java 버전을 확인하고, `infra/.env` 생성과 프론트엔드 의존성 설치를 자동화
- `scripts/bootstrap.sh` — `--with-python` 옵션으로 `llmPipeline/.venv` 생성과 `llmPipeline/requirements.txt` 설치 지원
- `scripts/dev-up.sh` — 실행 시작 시 bootstrap을 먼저 호출하도록 변경
- `docs/local-runbook.md` — bootstrap 사용법과 Python 가상환경 옵션 문서화

**검증**

- `bash -n scripts/bootstrap.sh`
- `bash -n scripts/dev-up.sh`

---

## 2026-06-18

### chore: dev-up pipeline API 포함

**배경**

채팅 질의와 문서 업로드 후 처리는 Spring 백엔드가 `localhost:8000`의 pipeline API를 호출해야 합니다. 기존 `scripts/dev-up.sh`는 PostgreSQL, MinIO, 백엔드, 프론트엔드만 실행해 `POST /api/query`가 503으로 실패하고 업로드 문서가 `processing`에 머물 수 있었습니다.

또한 `infra/docker-compose.pipeline.yml`로 실행했던 `pipeline-api` 컨테이너가 중지 상태로 남으면, 기본 `scripts/dev-up.sh` 실행 시 Docker Compose가 orphan container 경고를 출력했습니다.

**변경된 것**

- `scripts/dev-up.sh` — `infra/docker-compose.dev.yml`와 `infra/docker-compose.pipeline.yml`을 함께 실행하고 `http://localhost:8000/health`까지 확인하도록 변경
- `scripts/dev-up.sh` — `fruition-mvp-dev` project의 중지된 `pipeline-api` 컨테이너만 `docker compose up` 전에 정리하도록 추가
- `scripts/dev-down.sh` — pipeline API compose 파일과 `8000` 포트 종료를 포함하도록 변경
- `docs/local-runbook.md` — 자동 실행/종료 스크립트가 pipeline API를 포함한다는 내용으로 갱신

**검증**

- `bash -n scripts/dev-up.sh`
- `bash -n scripts/dev-down.sh`
- `./scripts/dev-up.sh` 실행 시 orphan container 경고 없이 PostgreSQL/MinIO, pipeline API, 백엔드, 프론트엔드 기동 확인
- `./scripts/dev-down.sh`로 앱 프로세스와 PostgreSQL/MinIO/pipeline API 컨테이너 종료 확인

---

## 2026-06-16

### chore: Discord PR 알림 설정 문서화

**배경**

팀이 새 PR을 Discord에서 바로 확인할 수 있도록 GitHub Webhook 기반 알림 설정
절차를 문서화했습니다.

**추가된 것**

- `docs/development.md` — GitHub repository webhook과 Discord Webhook `/github` endpoint 설정 절차 문서화
- `docs/development.md` — GitHub Webhook 방식의 이벤트 및 대상 브랜치 필터 제한과 Webhook URL 보안 주의사항 문서화

**검증**

- GitHub와 Discord 공식 문서의 repository webhook 설정 절차 확인
- 애플리케이션 기능 코드 변경 없음

---

## 2026-06-13

### docs: 로컬 실행 가이드와 실행 스크립트 추가

**배경**

새 환경에서도 프론트엔드, 백엔드, 로컬 인프라를 같은 순서로 실행하고 확인할 수 있도록 요구사항과 절차를 한곳에 정리했습니다.

**추가된 것**

- `docs/local-runbook.md` — Docker, Java 21, Node.js 요구사항과 수동/자동 실행 순서 문서화
- `scripts/dev-up.sh` — `infra/.env` 준비, PostgreSQL/MinIO 기동, 백엔드/프론트엔드 실행, HTTP 응답 확인 자동화
- `scripts/dev-down.sh` — 프론트엔드/백엔드 프로세스와 로컬 인프라 컨테이너 전체 종료 자동화

**검증**

- `bash -n scripts/dev-up.sh`
- `bash -n scripts/dev-down.sh`
- `./scripts/dev-up.sh`로 PostgreSQL/MinIO, 백엔드, 프론트엔드 기동 및 HTTP 응답 확인
- `./scripts/dev-down.sh`로 앱 프로세스와 PostgreSQL/MinIO 컨테이너 종료 확인

---

## 2026-06-11

### docs: changelog 및 이슈 추적 규칙 추가

**배경**

커밋 시점마다 변경사항이 changelog에 누락되지 않도록 작업 지침을 보강하고, backend와 llmPipeline 통합 과정에서 남은 이슈를 별도 문서로 분리했습니다.

**추가된 것**

- `AGENTS.md` — 커밋 전 관련 changelog 갱신 규칙 추가
- `CLAUDE.md` — 커밋 전 관련 changelog 갱신 규칙 추가
- `docs/issue/2026-06-11.md` — backend / AI Pipeline 담당 영역별 미해결 이슈 정리

**검증**

- 문서 내용 확인
- `git status --short`로 변경 파일 확인

---

## 2026-06-09

### chore: 로컬 Docker 개발 환경 추가 (`8453cf1`)

**추가된 것**

- `infra/docker-compose.dev.yml` — PostgreSQL 16 + MinIO 컨테이너 구성
- `infra/minio-init` — 버킷 자동 생성 컨테이너 (`fruition-storage`)
- `infra/.env` / `infra/.env.example` — 환경변수 단일 관리
- `backend/build.gradle` — `bootRun` 태스크에서 `infra/.env` 자동 로드

**로컬 서비스 구성**

| 서비스 | 포트 |
|---|---|
| PostgreSQL | `5432` |
| MinIO API | `9000` |
| MinIO 콘솔 | `9001` |

---

*커밋 단위 이력은 `git log` 로 확인하세요.*
