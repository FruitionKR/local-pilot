# 아키텍처

기준일 2026-08-14. 상세 이력·검증 원문: `docs/backlog/msa/`, `docs/backlog/Fruition_AWS_MSA_Architecture.md`

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
│  ├─ document-svc/   Spring, :8080  문서·채팅·Wiki·Skill gateway·query, core Flyway 소유, stateless
│  └─ java-shared/    라이브러리 모듈  JWT(발급·검증)·공통 예외·Idempotency (앱 아님)
└─ ai/
   ├─ pipeline/    FastAPI, 내부 전용  동기 query·skill·Wiki 조회 + GET /documents (LLM·임베딩)
   │                └ 같은 이미지로 ingest/query/agent/maintenance Kafka worker와
   │                  edit-event-consumer(document.edit.event 소비, ai_db) 실행
   └─ converter/   FastAPI, 내부 전용  PDF→Markdown 변환 (document-svc 변환 큐 경유)

상태 계층: PostgreSQL(access_db·core_db·ai_db 분리) · Redis · Kafka · MinIO/S3
```

코드 경계는 컴파일러가 강제한다: `document-svc`(fruition.core)는 `fruition.access`를 import하지 않고, `access-svc`는 `fruition.core`를 import하지 않는다. 두 앱은 서로의 DB repository를 직접 쓰지 않고 내부 API·Redis projection으로만 연결한다. (실측: 교차 import 양방향 0건)

## 2. 서비스 간 통신

- Query 요청은 질의별 `allow_web_search`를 필수로 전달한다. `document-svc`는 사용자 전역 설정을 조회하지 않고 요청값을 메시지와 run에 snapshot으로 남긴 뒤 Query HTTP/Kafka payload에 전달한다.

| 방향 | 방식 | 용도 |
|---|---|---|
| document → access | `GET /internal/authz/workspaces/{wid}/users/{uid}`, `GET /internal/users/{uid}`, `GET·PUT /internal/workspaces/{wid}/ai-model-settings` (X-Internal-Token) | 권한·표시명·workspace AI 모델 설정 조회/변경 |
| access → document | `POST /internal/workspaces/{wid}/initial-note` (X-Internal-Token, best-effort, 커밋 후 호출) | 새 워크스페이스 초기 노트 |
| document → ai-svc | Kafka `ai.ingest.command`(key=document_id), `ai.query.command`, `ai.agent.command`, `ai.maintenance.command` | 비동기 ingest·Query·Agent·Lint·Restore |
| document → ai-svc | HTTP + X-Internal-Token | 동기 Query, Wiki 현재 상태 조회, pipeline run 폴링, Agent Tool run·승인 인자 인가 |
| document → ai-svc | HTTP + X-Agent-Service-Token | JWT Skill 관리 요청 중계 |
| document → ai-svc | `GET /agent/runs/{run_id}` + X-Agent-Service-Token | Skill draft source를 workspace/user scope로 canonical 조회 |
| document → converter | HTTP (내부 전용, 큐 worker 경유) | PDF→Markdown 변환 (read timeout 900s) |
| ai-svc → document | Kafka `ai.task.event` | Query 단계 진행 이벤트와 AI 작업 최종 결과 전달 |
| ai-svc → document | 내부 HTTP + X-Internal-Token | ingest 원본 metadata·core 기여 이력 조회 |
| ai-svc → document | `POST /internal/agent/tools/{read|execute}/{tool}` + X-Agent-Service-Token | P0 문서·폴더 조회와 승인된 변경 실행. document-svc가 core_db 소유 경계에서 실제 처리 |
| ai-svc → document | `POST /internal/agent/skill-authoring/references/read` + X-Agent-Service-Token | Skill 참조 scope·role 검증, EDITABLE 최신 PostgreSQL Markdown 조회; ORIGINAL은 ai-svc가 ai_db source block 조립 |
| ai-svc → access | `GET /internal/authz/workspaces/{wid}/users/{uid}` + X-Internal-Token | Skill 팀 범위 멤버·owner 확인 |
| 사용자 인증 | 각 앱이 JWT(iss·aud, HS256 공유 시크릿) 로컬 검증 | access 호출 없이 검증 |

Java 서비스는 요청 단위 로그를 `X-Request-ID`로 잇는다. 요청에 유효한 값이 오면 그대로 쓰고 없으면 새로 만들며, 응답 헤더로 되돌려준다. 이 값은 MDC `requestId`, JWT 주체는 `userId`, Kafka 발행·소비 경계의 `run_id`는 `flowId`로 남아 모든 로그 줄에 함께 출력된다. `flowId`는 Kafka 메시지 헤더로 전파하지 않으므로 ai-svc worker 로그와는 `run_id` 값으로 대조한다.

## 3. LLM 설정 전달

지원 조합은 `openai/gpt-5-nano`(기본, `reasoning_effort=minimal`), `gemini/gemini-3.1-flash-lite`(`low`), `claude/claude-haiku-4-5-20251001`(extended thinking 없음)뿐이다. Ingest·Lint command와 Skill author/publish/update는 workspace 설정을 snapshot하고, Query·Markdown Agent·Agent 경로는 chat/request 설정을 snapshot한다. provider/model은 사용자 설정·API·DB·Kafka payload에서 오며 env override는 없다.

ai-svc는 선택 provider의 `OPENAI_API_KEY`·`GEMINI_API_KEY`·`ANTHROPIC_API_KEY`만 secret env에서 읽고 base URL은 provider별로 고정한다. API key는 backend·Kafka payload/event·log에 넣지 않는다. live provider 호출은 선택 provider key가 필요하고 mock 통합 테스트는 key 없이 실행한다.

## 4. 권한 인가

document-svc는 workspace 멤버십을 DB에서 직접 읽지 않는다:

```text
요청 → document-svc guard.requireMember(wid, uid)
  1. Redis authz:role:{wid}:{uid} 조회 (TTL 300s) → hit(OWNER/MEMBER/NONE) 즉시 판정
  2. miss → access-svc GET /internal/authz/... (connect 2s/read 3s) → TTL 300s 캐시 후 판정
  3. HTTP 실패 → WorkspaceNotFoundException (fail-closed, 404)
```

access-svc는 멤버십 변경 시 projection을 write-through/무효화한다. **access-svc가 죽어도 캐시 warm 상태의 문서 기능은 계속 동작한다**(TTL 내). 실측: access 강제 정지 중 문서 조회 200·업로드 201, cold 캐시는 fail-closed 404. 결정 근거: [adr/0002](adr/0002-choose-auth-strategy.md)

## 5. 데이터 소유

저장소·테이블 상세는 [data-model.md](data-model.md). 요약:

- access-svc → **access_db** (users·oauth·refresh token·workspaces·members·세션·workspace AI 모델 설정) + Redis projection
- document-svc → **core_db** (문서 metadata·폴더·채팅·operation·Wiki revision/기여 이력·질의 모델 snapshot·본문·편집 revision·write receipt·content version·asset/reference·Agent 적용 감사·`document_edit_outbox`) + Redis (query run·SSE) + S3/MinIO (원본·snapshot). 문서 편집 관련 PostgreSQL 변경은 하나의 transaction으로 일관성을 보장하며, fresh cutover에서 import·fallback·dual-write를 사용하지 않는다. V39 당시 기존 `document_edit` 감사 행은 `document_restore_blocked`로 복구를 차단하고 Wiki ingest/lint와 새 작업은 보존한다. 결정 근거: [adr/0016](adr/0016-consolidate-document-body-into-postgres.md). Skill은 저장하지 않고 JWT 인가와 참조 문서 read 경계만 담당한다.
- ai-svc → **ai_db** (Wiki 현재 상태·source block·embedding·pipeline run·schema·파생물 stale 추적·Agent·Skill·LangGraph checkpoint).
- DB 계정 runtime(DML)/migration(DDL) 분리. `ai_runtime`은 core DB DML 권한과 연결 설정을 갖지 않는다. Markdown Agent 요청 시 document-svc는 core의 좁은 적용 예약 projection과 outbox만 원자 저장하고, AI run 상태는 scope가 포함된 내부 API로 조회한다. 결정 근거: [adr/0001](adr/0001-choose-primary-database.md), [adr/0005](adr/0005-prepare-wiki-database-boundary.md)

## 6. 이벤트 처리

본문 저장은 PostgreSQL transaction에서 본문·편집 revision·write receipt·content version·asset/reference·Agent 적용 감사와 `document_edit_outbox`를 함께 기록한 뒤 outbox publisher가 Kafka `document.edit.event`(key=document_id)를 발행한다. event JSON은 `event_id`, `event_type`, `schema_version`, `document_id`, `workspace_id`, `revision`, `content_hash`, `created_at` 필드를 유지한다. publisher는 `created_at, event_id` 순으로 최대 100건을 처리하고 첫 실패에서 해당 cycle을 중단한다. Kafka 전송 후 표시 전에 장애가 나면 중복될 수 있어 at-least-once이며, consumer는 더 큰 revision만 반영해 중복·역순 event를 흡수한다. 현재 document-svc와 edit-event-consumer는 각각 1 replica 전제다. 결정 근거: [adr/0016](adr/0016-consolidate-document-body-into-postgres.md). AI 작업은 Spring이 `run_id`와 필요 시 `operation_id`를 먼저 만들고 domain 상태와 `ai_command_outbox`를 같은 core DB 트랜잭션에 저장한 뒤 발행한다. Query·ingest·lint command에는 적용할 `provider`와 `model` snapshot도 포함한다. Query worker는 pipeline의 단계 이벤트를 `status=progress`인 Kafka `ai.task.event`로 즉시 발행하고, document-svc는 Redis에서 `event_id`를 선점해 중복을 제거한 뒤 `query.log` SSE로 중계한다. 단계 이벤트와 최종 결과는 같은 `run_id` Kafka key를 사용해 순서를 유지한다. Agent 결과는 `markdown_edit`·`markdown_create`의 canonical Markdown을 검증하고, `chat_answer`·`clarify`·`reject`는 Markdown이 없는 정상 비수정 결과로 반영한다. `folder_organize`·`workspace_workflow` 자율 action도 허용하며 그 밖의 action은 거절한다. AI worker는 최종 결과도 전달받은 `run_id`로 `ai.task.event`에 보낸다. `log_callback_url`은 Wiki 생성 `pipeline.log` 진행 로그 전송에만 사용하며 Query와 HTTP result callback에는 사용하지 않는다. document-svc는 `ai_task_result_receipts`로 최종 결과를 멱등 반영하며 ingest는 AI run 폴링으로 event 유실도 복구한다. 기존 AI 작업 로그 조회/결과 경로는 LLM 설정을 받지 않는다.

ingest Kafka key는 `document_id`라 같은 문서의 순서는 유지하면서 같은 workspace의 서로 다른 문서 LLM·분석을 병렬 처리한다. ingest와 lint `materialize=true`는 Concept 최종 read→merge→object write→DB commit만 `(user_id, workspace_id)` PostgreSQL transaction advisory lock으로 공유 직렬화한다. 기존 ingest Redis short lock은 유지하고 `(user_id, workspace_id, page_type, slug)` unique + `INSERT ... ON CONFLICT ... RETURNING id`가 중복 생성을 차단한다. Concept index cache는 commit 후 무효화하며, source revision/content hash와 page `updated_at`가 오래된 ingest·embedding 결과를 차단한다. workload별 worker는 별도 consumer group과 KEDA lag 기준을 사용한다. 결정 근거: [adr/0003](adr/0003-choose-event-processing-strategy.md), [adr/0005](adr/0005-prepare-wiki-database-boundary.md), [adr/0006](adr/0006-async-ai-tasks-and-parallel-ingest.md)

## 7. 배포

배포 단위 = 이미지 = 폴더. 로컬 compose·kind 검증 그대로 AWS 매핑 (코드 변경 0, env만 교체).

| 로컬 | AWS |
|---|---|
| kind | Amazon EKS (`infra/terraform/eks.tf`) |
| Strimzi Kafka | Strimzi on EKS (또는 MSK) |
| postgres 컨테이너 | Access RDS + Core RDS 2 instance |
| redis 컨테이너 | ElastiCache |
| minio | S3 |
| 이미지 | ECR (GitHub OIDC push) |
| Secret(YAML) | Secrets Manager + external-secrets |
| frontend | Vercel |

- 매니페스트: `k8s/base` + `k8s/overlays/aws` (ingress·external-secrets·KEDA)
- IaC: `infra/terraform` (EKS·RDS·ElastiCache·S3·ECR·OIDC·Secrets·budgets) — apply는 AWS 계정 준비 후
- 실제 배포 단위 검증은 `docker-compose.dev.yml` + `docker-compose.pipeline.yml` + `docker-compose.converter.yml` + `docker-compose.deploy.yml`을 함께 구성한다. document-svc가 `core_db` Flyway를 먼저 적용한 뒤 access-svc와 pipeline API/worker를 기동하며, AI 저장소 maintenance cutover는 [demo-script.md](demo-script.md) 절차를 따른다. `JWT_SECRET`·`INTERNAL_CALLBACK_TOKEN`은 두 앱 동일 값 필수.
- ALB 경로 규칙 = next.config rewrite 동일 (§1 라우팅)

## 8. 남은 결합 지점 (트리거 대기 — 분할 미비 아님)

| 항목 | 상태 · 트리거 |
|---|---|
| JWT HS256 공유 시크릿 | 외부 공개·시크릿 유출 리스크 대두 시 RS256+JWKS 전환 |
| pipeline-runs PVC | S3 아티팩트 이전 완료 시 ingest-worker Spot 노드 활성화 가능 |
