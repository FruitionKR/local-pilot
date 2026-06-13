# Changelog — Infra

로컬 개발 인프라 변경 이력입니다. 날짜 역순으로 기록합니다.

---

## [Unreleased] — feat/wiki-graph-query-engine

### fix: llmPipeline Dockerfile 모듈 경로 갱신

**배경**

`llmPipeline` 코드가 `fruition_lab` flat package에서 `app/modules/*` bounded context 구조로 변경됐지만, Dockerfile은 여전히 삭제된 `fruition_lab` 디렉터리를 이미지에 복사하고 있었습니다. 이 때문에 WSL Docker에서 `pipeline-api` 이미지를 rebuild할 때 `COPY fruition_lab` 단계에서 실패했습니다.

**변경된 것**

- `llmPipeline/Dockerfile`에서 `COPY fruition_lab ./fruition_lab`를 제거했습니다.
- 현재 FastAPI와 query 모듈이 사용하는 `app/` 디렉터리를 이미지에 복사하도록 변경했습니다.
- `sentence-transformers` 계열 큰 wheel 다운로드 중 pip read timeout이 발생하지 않도록 `PIP_DEFAULT_TIMEOUT=300`을 설정했습니다.
- 기본 `requirements.txt`에서 `sentence-transformers`를 제거하고, BGE-M3 embedding 실행용 의존성은 `requirements-embedding.txt`로 분리했습니다.
- 로컬 compose의 `pipeline-api` 기본값을 `QUERY_EMBEDDING_MODE=text-only`로 설정해 가벼운 query 플로우 테스트가 가능하도록 했습니다.

**검증**

- WSL Docker에서 `docker compose --env-file infra/.env -f infra/docker-compose.dev.yml -f infra/docker-compose.pipeline.yml up -d --build pipeline-api` 통과.
- rebuild 후 `GET /health`와 OpenAPI `/query` route 등록을 확인했습니다.

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
