# 아키텍처

기준일 2026-08-14. 상세 이력·검증 원문: `docs/backlog/msa/`, `docs/backlog/Fruition_AWS_MSA_Architecture.md`

## 1. 서비스 경계 (전부 독립 배포 단위)

```text
frontend (Next.js, Vercel)
  │ /api/* 경로 기반 rewrite (next.config.mjs)
  ├─ /api/auth/*, 워크스페이스 자체 CRUD·휴지통·복구 ─▶ access-svc
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

OAuth 로그인은 provider 왕복 동안에만 `IF_REQUIRED` 세션을 사용한다. 성공 시 access-svc는 Redis에 1회용 교환 코드를 저장하고 프론트 `/oauth/callback`으로 전달한다. 성공·실패 handler는 응답을 redirect하기 전에 handshake 세션을 즉시 폐기하며, 이후 인증은 프론트가 교환한 JWT만 사용한다. 인증 컨텍스트는 세션에 저장하지 않는다(`RequestAttributeSecurityContextRepository`) — 세션은 OAuth handshake의 `AUTHORIZATION_REQUEST` 보관에만 쓰이며, 병렬 요청이 `SPRING_SECURITY_CONTEXT`를 동시에 INSERT해 발생하던 500을 차단한다.

## 3. LLM 설정 전달

지원 조합은 `openai/gpt-5-nano`(`reasoning_effort=medium`), `gemini/gemini-3.1-flash-lite`(`low`), `claude/claude-sonnet-5`(extended thinking 없음)뿐이다. 요청에서 provider/model을 함께 생략하는 공통 기본값은 `openai/gpt-5-nano`이고, 새 workspace의 Ingest·Lint 및 PDF 복원 기본값은 `gemini/gemini-3.1-flash-lite`다. Ingest·Lint command, PDF 변환과 Skill author/publish/update는 workspace 설정을 snapshot하고, Query·Markdown Agent·Agent 경로는 chat/request 설정을 snapshot한다. provider/model은 사용자 설정·API·DB·Kafka payload에서 오며 env override는 없다.

ai-svc와 converter는 선택 provider의 `OPENAI_API_KEY`·`GEMINI_API_KEY`·`ANTHROPIC_API_KEY`만 secret env에서 읽고 base URL은 provider별로 고정한다. API key는 backend·Kafka payload/event·log에 넣지 않는다. live provider 호출은 선택 provider key가 필요하고 mock 통합 테스트는 key 없이 실행한다.

## 4. 권한 인가

document-svc는 workspace 멤버십을 DB에서 직접 읽지 않는다:

```text
요청 → document-svc guard.requireMember(wid, uid)
  1. Redis authz:role:{wid}:{uid} 조회 (TTL 300s) → hit(OWNER/MEMBER/NONE) 즉시 판정
  2. miss → access-svc GET /internal/authz/... (connect 2s/read 3s) → TTL 300s 캐시 후 판정
  3. HTTP 실패 → WorkspaceNotFoundException (fail-closed, 404)
```

projection 적재는 document-svc가 miss 시 내부 API 판정 결과를 캐시하는 방식이고, access-svc는 멤버십이 변하는 지점(삭제·복구 등)에서 무효화만 담당한다. **access-svc가 죽어도 캐시 warm 상태의 문서 기능은 계속 동작한다**(TTL 내). 실측: access 강제 정지 중 문서 조회 200·업로드 201, cold 캐시는 fail-closed 404. 결정 근거: [adr/0002](adr/0002-choose-auth-strategy.md)

## 5. 데이터 소유

저장소·테이블 상세는 [data-model.md](data-model.md). 요약:

- access-svc → **access_db** (users·oauth·refresh token·workspaces·members·세션·workspace AI 모델 설정) + Redis projection
- document-svc → **core_db** (문서 metadata·폴더·채팅·operation·Wiki revision/기여 이력·질의 모델 snapshot·본문·편집 revision·write receipt·content version·asset/reference·Agent 적용 감사·`document_edit_outbox`) + Redis (query run·SSE) + S3/MinIO (원본·snapshot). 문서 편집 관련 PostgreSQL 변경은 하나의 transaction으로 일관성을 보장하며, fresh cutover에서 import·fallback·dual-write를 사용하지 않는다. V39 당시 기존 `document_edit` 감사 행은 `document_restore_blocked`로 복구를 차단하고 Wiki ingest/lint와 새 작업은 보존한다. 결정 근거: [adr/0016](adr/0016-consolidate-document-body-into-postgres.md). Skill은 저장하지 않고 JWT 인가와 참조 문서 read 경계만 담당한다.
- ai-svc → **ai_db** (Wiki 현재 상태·source block·embedding·pipeline run·schema·파생물 stale 추적·Agent·Skill·LangGraph checkpoint).
- DB 계정 runtime(DML)/migration(DDL) 분리. `ai_runtime`은 core DB DML 권한과 연결 설정을 갖지 않는다. Markdown Agent 요청 시 document-svc는 core의 좁은 적용 예약 projection과 outbox만 원자 저장하고, AI run 상태는 scope가 포함된 내부 API로 조회한다. 결정 근거: [adr/0001](adr/0001-choose-primary-database.md), [adr/0005](adr/0005-prepare-wiki-database-boundary.md)

## 6. 이벤트 처리

본문 저장은 PostgreSQL transaction에서 본문·편집 revision·write receipt·content version·asset/reference·Agent 적용 감사와 `document_edit_outbox`를 함께 기록한 뒤 outbox publisher가 Kafka `document.edit.event`(key=document_id)를 발행한다. event JSON은 `event_id`, `event_type`, `schema_version`, `document_id`, `workspace_id`, `revision`, `content_hash`, `created_at` 필드를 유지한다. publisher는 `created_at, event_id` 순으로 최대 100건을 처리하고 첫 실패에서 해당 cycle을 중단한다. Kafka 전송 후 표시 전에 장애가 나면 중복될 수 있어 at-least-once이며, consumer는 더 큰 revision만 반영해 중복·역순 event를 흡수한다. 현재 document-svc와 edit-event-consumer는 각각 1 replica 전제다. 결정 근거: [adr/0016](adr/0016-consolidate-document-body-into-postgres.md). AI 작업은 Spring이 `run_id`와 필요 시 `operation_id`를 먼저 만들고 domain 상태와 `ai_command_outbox`를 같은 core DB 트랜잭션에 저장한 뒤 발행한다. Query·ingest·lint command에는 적용할 `provider`와 `model` snapshot도 포함한다. Query worker는 pipeline의 단계 이벤트를 `status=progress`인 Kafka `ai.task.event`로 즉시 발행하고, document-svc는 Redis에서 `event_id`를 선점해 중복을 제거한 뒤 `query.log` SSE로 중계한다. 단계 이벤트와 최종 결과는 같은 `run_id` Kafka key를 사용해 순서를 유지한다. 단계 이벤트는 화면 피드백 용도라 양쪽 모두 유실을 허용한다. worker는 발행이 실패해도 질의를 계속하고, document-svc는 중계 실패를 로그만 남긴다 — 여기서 예외를 올리면 무한 재시도가 같은 파티션의 최종 결과까지 막기 때문이다. Agent 결과는 `markdown_edit`·`markdown_create`의 canonical Markdown을 검증하고, `chat_answer`·`clarify`·`reject`는 Markdown이 없는 정상 비수정 결과로 반영한다. `folder_organize`·`workspace_workflow` 자율 action도 허용하며 그 밖의 action은 거절한다. AI worker는 최종 결과도 전달받은 `run_id`로 `ai.task.event`에 보낸다. `log_callback_url`은 Wiki 생성 `pipeline.log` 진행 로그 전송에만 사용하며 Query와 HTTP result callback에는 사용하지 않는다. document-svc는 `ai_task_result_receipts`로 최종 결과를 멱등 반영하며 ingest는 AI run 폴링으로 event 유실도 복구한다. 기존 AI 작업 로그 조회/결과 경로는 LLM 설정을 받지 않는다.

ingest Kafka key는 `document_id`라 같은 문서의 순서는 유지하면서 같은 workspace의 서로 다른 문서 LLM·분석을 병렬 처리한다. ingest worker는 Wiki 저장 후 `post_ingest` maintenance command 발행까지 성공해야 원본 offset을 commit한다. 후속 run은 결정적 UUID와 `pipeline_runs.manifest` checkpoint로 중복을 흡수하고 Meaning Cluster 판단→source-grounded Source+Concept retrieval 평가를 순차 실행한다. Query가 semantic 검색 모드이면 변경 page와 Source block·Concept evidence unit을 같은 BGE-M3 모델로 임베딩하고, keyword·unit vector 후보를 함께 검색한 뒤 graph를 탐색한다. evidence selector는 source ref 중복을 제거한 전역 상위 근거만 반환하며 일반 Query는 최대 8개, 단일 주장 post-ingest 평가는 최대 3개를 사용한다. `text-only`·`bm25`·`lexical` 모드에서는 대상 수를 skip으로 checkpoint하고 모델을 로드하지 않는다. 평가 질문과 기대 사실은 저장된 원문 `source_blocks`에서 비동기로 최대 3개 만들고, 실제 원문 인용 여부·중복·행정 메타데이터 여부는 코드로 검증한다. 각 질문은 실제 Query와 같은 Source·Concept 후보 검색·graph 탐색·evidence selector를 실행하되 답변 LLM은 호출하지 않는다. 한 번의 batch evaluator가 질문 정합성과 검색 evidence의 answerability·recall·precision·원문 provenance·모순 여부를 함께 판단하며 gold source ref hit/rank도 결정적으로 기록한다. evaluator가 case를 하나라도 누락하면 결과를 저장하지 않고 후속 run을 재시도한다. 유효 문항이 0개일 때만 재시도하고 3개 미만이면 실행을 실패시키지 않고 `needs_review`로 남긴다. 동기 source→normalized 평가와 비동기 retrieval 3문항이 모두 통과하면 `ready`, 누락·잘못된 근거·최종 실행 실패는 `needs_review`로 남긴다. 현재 Wiki page는 버전 없이 제자리 갱신되므로 `ready`는 진단 상태이며 Query 노출을 차단하지 않는다. 일시 실패는 최대 3회 재전달한다. ingest와 lint `materialize=true`는 Concept 최종 read→merge→object write→DB commit만 `(user_id, workspace_id)` PostgreSQL transaction advisory lock으로 공유 직렬화한다. 기존 ingest Redis short lock은 유지하고 `(user_id, workspace_id, page_type, slug)` unique + `INSERT ... ON CONFLICT ... RETURNING id`가 중복 생성을 차단한다. Concept index cache는 commit 후 무효화하며, source revision/content hash와 page `updated_at`가 오래된 ingest·embedding 결과를 차단한다. workload별 worker는 별도 consumer group과 KEDA lag 기준을 사용한다. 결정 근거: [adr/0003](adr/0003-choose-event-processing-strategy.md), [adr/0005](adr/0005-prepare-wiki-database-boundary.md), [adr/0006](adr/0006-async-ai-tasks-and-parallel-ingest.md)

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
- 실제 배포 단위 검증은 `compose.infra.yml` + `compose.ai.yml` + `compose.converter.yml` + `compose.containerized.yml`을 함께 구성한다. document-svc가 `core_db` Flyway를 먼저 적용한 뒤 access-svc와 pipeline API/worker를 기동하며, AI 저장소 maintenance cutover는 [script.md](script.md) 절차를 따른다. `JWT_SECRET`·`INTERNAL_CALLBACK_TOKEN`은 두 앱 동일 값 필수.
- ALB는 `api.<domain>`을 document-svc, `access.<domain>`을 access-svc로 host 라우팅한다.
  공개 `/api/**`의 서비스 분기는 Vercel `next.config.mjs` rewrite가 담당한다.
- actuator는 업무 포트가 아니라 관리 포트로 분리한다(로컬 8082·8083, k8s는 configmap `MANAGEMENT_PORT`로 8082 통일).
  ALB는 업무 포트만 라우팅하므로 `/actuator/prometheus`가 인터넷에 열리지 않는다. probe와 ALB healthcheck만 관리 포트를 본다.

## 8. 남은 결합 지점 (트리거 대기 — 분할 미비 아님)

| 항목 | 상태 · 트리거 |
|---|---|
| JWT HS256 공유 시크릿 | 외부 공개·시크릿 유출 리스크 대두 시 RS256+JWKS 전환 |
| pipeline-runs PVC | S3 아티팩트 이전 완료 시 ingest-worker Spot 노드 활성화 가능 |
