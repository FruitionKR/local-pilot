# Changelog — Infra

로컬 개발 인프라 변경 이력입니다. 날짜 역순으로 기록합니다.

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
