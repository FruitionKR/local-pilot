# 아키텍처

기준일 2026-08-07. 상세 이력·검증 원문: `docs/backlog/msa/`, `docs/backlog/Fruition_AWS_MSA_Architecture.md`

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
│  ├─ document-svc/   Spring, :8080  문서·채팅·Wiki·query, Flyway(스키마) 소유, stateless
│  └─ java-shared/    라이브러리 모듈  JWT(발급·검증)·공통 예외·Idempotency (앱 아님)
└─ ai/
   ├─ pipeline/    FastAPI, 내부 전용  동기 query·agent·lint + GET /documents (LLM·임베딩)
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
| document → ai-svc | HTTP + X-Internal-Token | 동기 query·agent·lint |
| document → converter | HTTP (내부 전용, 큐 worker 경유) | PDF→Markdown 변환 (read timeout 900s) |
| ai-svc → document | HTTP 콜백 + X-Internal-Token | 진행 heartbeat·결과 통지 |
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
- document-svc → **core_db** (문서 metadata·폴더·채팅·Wiki·operation) + **MongoDB** (본문·revision·outbox, 단일 트랜잭션) + Redis (query run·SSE) + S3/MinIO (원본·snapshot)
- ai-svc → **ai_db** (wiki_schemas·파생물 stale 추적) + core_db 동거 4테이블(전환기, ai_runtime 별도 계정)
- DB 계정 runtime(DML)/migration(DDL) 분리, 타 서비스 DB write 불가. 결정 근거: [adr/0001](adr/0001-choose-primary-database.md)

## 5. 이벤트 처리

본문 저장은 Mongo 트랜잭션(본문+revision+write-id+outbox) 후 outbox publisher가 Kafka `document.edit.event`(key=document_id) 발행 — at-least-once, 문서별 순서 보존. ingest는 `ai.ingest.command`(key=workspace_id, 12 partitions) + KEDA lag 기반 스케일(min1/max4). 유실 실측: worker 정지 중 발행 → 기동 후 소비 → lag 0. 결정 근거: [adr/0003](adr/0003-choose-event-processing-strategy.md)

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
- 배포 순서: document-svc 먼저(Flyway 스키마 생성) → access-svc(검증만). `JWT_SECRET`·`INTERNAL_CALLBACK_TOKEN`은 두 앱 동일 값 필수.
- ALB 경로 규칙 = next.config rewrite 동일 (§1 라우팅)

## 7. 남은 결합 지점 (트리거 대기 — 분할 미비 아님)

| 항목 | 상태 · 트리거 |
|---|---|
| ai 테이블 4개(pipeline_runs·임베딩 3종) core_db 동거 | 계정 격리로 안전 확보. AI 독립 스케일 필요 시 ai_db 이전. 차단 지점 실측: `docs/backlog/issue/ai/2026-08-07.md` |
| JWT HS256 공유 시크릿 | 외부 공개·시크릿 유출 리스크 대두 시 RS256+JWKS 전환 |
| query 결과 HTTP 콜백 | pipeline 재시작 중 유실이 실제 문제 될 때 Kafka result topic 전환 (참조: `feat/msa-kafka-publisher-consumers` 브랜치 318b991) |
| pipeline-runs PVC | S3 아티팩트 이전 완료 시 ingest-worker Spot 노드 활성화 가능 |
