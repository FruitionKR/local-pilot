# 아키텍처

기준일 2026-08-10. 상세 이력·검증 원문: `docs/backlog/msa/`, `docs/backlog/Fruition_AWS_MSA_Architecture.md`

## 1. 서비스 경계 (전부 독립 배포 단위)

```text
frontend (Next.js, Vercel)
  │ /api/* 경로 기반 rewrite (next.config.mjs)
  ├─ /api/auth/*, /api/workspaces, /api/workspaces/{id} ─▶ access-svc
  └─ 그 외 ───────────────────────────────────────────▶ document-svc

services/
├─ frontend/       Next.js (Vercel 배포)
├─ backend/        Gradle 멀티프로젝트 루트 (gradlew·settings.gradle)
│  ├─ access-svc/     Spring, :8081  로그인·OAuth·세션·워크스페이스·멤버·권한 projection 소유
│  ├─ document-svc/   Spring, :8080  문서·채팅·Wiki API·query, core Flyway 소유, stateless
│  └─ java-shared/    라이브러리 모듈  JWT(발급·검증)·공통 예외·Idempotency (앱 아님)
└─ ai/
   ├─ pipeline/    FastAPI, 내부 전용  동기 query·agent·skill·lint + GET /documents (LLM·임베딩)
   │                └ 같은 이미지로 ingest-worker(ai.ingest.command 소비)·
   │                  edit-event-consumer(document.edit.event 소비, ai_db) 실행
   └─ converter/   FastAPI, 내부 전용  PDF→Markdown 변환 (document-svc 변환 큐 경유)

상태 계층: PostgreSQL(access_db·core_db 분리) · MongoDB(문서 편집 원본) · Redis · Kafka · MinIO/S3
```

코드 경계는 컴파일러가 강제한다: `document-svc`(fruition.core)는 `fruition.access`를 import하지 않고, `access-svc`는 `fruition.core`를 import하지 않는다. 두 앱은 서로의 DB repository를 직접 쓰지 않고 내부 API·Redis projection으로만 연결한다. (실측: 교차 import 양방향 0건)

## 2. 서비스 간 통신

| 방향 | 방식 | 용도 |
|---|---|---|
| document → access | `GET /internal/authz/workspaces/{wid}/users/{uid}`, `GET /internal/users/{uid}` (X-Internal-Token) | 권한·표시명 조회 (캐시 miss 시) |
| access → document | `POST /internal/workspaces/{wid}/initial-note` (X-Internal-Token, best-effort, 커밋 후 호출) | 새 워크스페이스 초기 노트 |
| document → ai-svc | Kafka `ai.ingest.command` (key=workspace_id) | 비동기 ingest |
| document → ai-svc | HTTP + X-Internal-Token | query·lint, Wiki 현재 상태 조회, pipeline run 폴링 |
| document → ai-svc | HTTP + `X-Internal-Token` + `X-Agent-Service-Token` + workspace/user context | Agent turn |
| document → converter | HTTP (내부 전용, 큐 worker 경유) | PDF→Markdown 변환 (read timeout 900s) |
| ai-svc → document | 내부 HTTP + X-Internal-Token | ingest 원본 metadata·core 기여 이력 조회 |
| ai-svc → access | `GET /internal/authz/workspaces/{wid}/users/{uid}` + X-Internal-Token | Skill 팀 범위 멤버·owner 확인 |
| 사용자 인증 | 각 앱이 JWT(iss·aud, HS256 공유 시크릿) 로컬 검증 | access 호출 없이 검증 |

## 3. 권한 인가

document-svc는 workspace 멤버십을 DB에서 직접 읽지 않는다:

```text
요청 → document-svc guard.requireMember(wid, uid)
  1. Redis authz:role:{wid}:{uid} 조회 (TTL 300s) → hit(OWNER/MEMBER/NONE) 즉시 판정
  2. miss → access-svc GET /internal/authz/... (connect 2s/read 3s) → TTL 300s 캐시 후 판정
  3. HTTP 실패 → WorkspaceNotFoundException (fail-closed, 404)
```

access-svc는 멤버십 변경 시 projection을 write-through/무효화한다. **access-svc가 죽어도 캐시 warm 상태의 문서 기능은 계속 동작한다**(TTL 내). 실측: access 강제 정지 중 문서 조회 200·업로드 201, cold 캐시는 fail-closed 404. 결정 근거: [adr/0002](adr/0002-choose-auth-strategy.md)

## 4. 데이터 소유

저장소·테이블 상세는 [data-model.md](data-model.md). 요약:

- access-svc → **access_db** (users·oauth·refresh token·workspaces·members·세션) + Redis projection
- document-svc → **core_db** (문서 metadata·폴더·채팅·operation·Wiki revision/기여 이력) + **MongoDB** (본문·revision·outbox, 단일 트랜잭션) + Redis (query run·SSE) + S3/MinIO (원본·snapshot)
- ai-svc → **ai_db** (Wiki 현재 상태·source block·embedding·pipeline run·schema·파생물 stale 추적). Agent/Skill/checkpoint만 전환기 예외로 `core_db`에 동거한다.
- DB 계정 runtime(DML)/migration(DDL) 분리. `ai_runtime`의 core write는 Agent/Skill/checkpoint 테이블과 필요한 sequence로 제한한다. 결정 근거: [adr/0001](adr/0001-choose-primary-database.md), [adr/0005](adr/0005-prepare-wiki-database-boundary.md)

## 5. 이벤트 처리

본문 저장은 Mongo 트랜잭션(본문+revision+write-id+outbox) 후 outbox publisher가 Kafka `document.edit.event`(key=document_id)를 발행한다. ingest 요청은 Spring이 서비스 진입 시 `run_id`를 만들고 core DB의 문서 `processing` 상태·`pipeline_run_id`·operation·`ai_command_outbox`를 한 트랜잭션에 저장한다. AI 파생물 삭제도 같은 outbox를 사용하며 publisher가 Kafka `ai.ingest.command`(key=workspace_id)를 발행한다. 둘 다 at-least-once이며 AI worker는 종료된 `run_id`의 재전달을 실행하지 않는다. document-svc는 `GET /pipeline/runs/{run_id}`를 폴링해 문서 최종 상태를 반영하고 `notify_pending` callback을 재시도한다. ingest topic은 12 partitions, KEDA lag 기반 스케일(min1/max4)을 사용한다. 결정 근거: [adr/0003](adr/0003-choose-event-processing-strategy.md), [adr/0005](adr/0005-prepare-wiki-database-boundary.md)

## 6. 배포

배포 단위 = 이미지 = 폴더. 로컬 compose·kind 검증 그대로 AWS 매핑 (코드 변경 0, env만 교체).

| 로컬 | AWS |
|---|---|
| kind | Amazon EKS (`infra/terraform/eks.tf`) |
| Strimzi Kafka | Strimzi on EKS (또는 MSK) |
| postgres 컨테이너 | Access RDS + Core RDS 2 instance |
| MongoDB 컨테이너 | MongoDB Atlas |
| redis 컨테이너 | ElastiCache |
| minio | S3 |
| 이미지 | ECR (GitHub OIDC push) |
| Secret(YAML) | Secrets Manager + external-secrets |
| frontend | Vercel |

- 매니페스트: `k8s/base` + `k8s/overlays/aws` (ingress·external-secrets·KEDA)
- IaC: `infra/terraform` (EKS·RDS·ElastiCache·S3·ECR·OIDC·Secrets·budgets) — apply는 AWS 계정 준비 후
- 배포 순서: document-svc 먼저(Flyway 및 Agent/Skill/checkpoint 스키마 생성) → access-svc(검증만) → pipeline API/worker. 기존 데이터 환경의 Wiki DB 전환은 [demo-script.md](demo-script.md)의 maintenance cutover 순서를 먼저 따른다. `JWT_SECRET`·`INTERNAL_CALLBACK_TOKEN`은 두 앱 동일 값 필수.
- ALB 경로 규칙 = next.config rewrite 동일 (§1 라우팅)

## 7. 남은 결합 지점 (트리거 대기 — 분할 미비 아님)

| 항목 | 상태 · 트리거 |
|---|---|
| Agent/Skill/checkpoint core_db 동거 | 통합 비동기 실행 전환 PR에서 ai_db로 이전 |
| JWT HS256 공유 시크릿 | 외부 공개·시크릿 유출 리스크 대두 시 RS256+JWKS 전환 |
| Query·Agent·Lint·Restore 동기 AI 호출 | 통합 비동기화 PR에서 Kafka command/result event로 전환 |
| pipeline-runs PVC | S3 아티팩트 이전 완료 시 ingest-worker Spot 노드 활성화 가능 |
