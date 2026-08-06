# 현행 아키텍처

## 1. 서비스 경계 (전부 독립 배포 단위)

```text
frontend (Next.js, Vercel)
  │ /api/* 경로 기반 rewrite (next.config.mjs)
  ├─ /api/auth/*, /api/workspaces, /api/workspaces/{id} ─▶ access-svc
  └─ 그 외 ───────────────────────────────────────────▶ document-svc

services/
├─ access-svc/     Spring, :8081  로그인·OAuth·세션·워크스페이스·멤버·권한 projection 소유
├─ document-svc/   Spring, :8080  문서·채팅·Wiki·query, Flyway(스키마) 소유, stateless
├─ java-shared/    라이브러리 모듈  JWT(발급·검증)·공통 예외·Idempotency (앱 아님)
└─ ai-svc/
   ├─ pipeline/    FastAPI, 내부 전용  동기 query·agent·lint + GET /documents (LLM·임베딩)
   ├─ ingest-worker  Kafka consumer   ai.ingest.command 소비 → 문서/Wiki ingest
   └─ converter/   FastAPI, 내부 전용  파일 변환 (제품 경로 미연결)

상태 계층: PostgreSQL · Redis · Kafka · MinIO/S3   (AWS: RDS · ElastiCache · MSK · S3)
```

코드 경계는 컴파일러가 강제한다: `document-svc`(fruition.core)는 `fruition.access`를 import하지 않고, `access-svc`는 `fruition.core`를 import하지 않는다. 두 앱은 서로의 DB repository를 직접 쓰지 않고 내부 API·Redis projection으로만 연결한다.

## 2. 서비스 간 통신

| 방향 | 방식 | 용도 |
|---|---|---|
| document → access | `GET /internal/authz/workspaces/{wid}/users/{uid}`, `GET /internal/users/{uid}` (X-Internal-Token) | 권한·표시명 조회 (캐시 miss 시) |
| access → document | `POST /internal/workspaces/{wid}/initial-note` (X-Internal-Token, best-effort, 커밋 후 호출) | 새 워크스페이스 초기 노트 |
| document → ai-svc | Kafka `ai.ingest.command` (key=workspace_id) | 비동기 ingest |
| document → ai-svc | HTTP + X-Internal-Token | 동기 query·agent·lint |
| ai-svc → document | HTTP 콜백 + X-Internal-Token | 진행 heartbeat·결과 통지 |
| 사용자 인증 | 각 앱이 JWT(iss·aud, HS256 공유 시크릿) 로컬 검증 | access 호출 없이 검증 |

## 3. 권한 인가 (핵심 설계)

document-svc는 workspace 멤버십을 DB에서 직접 읽지 않는다:

```text
요청 → document-svc guard.requireMember(wid, uid)
  1. Redis authz:role:{wid}:{uid} 조회 (TTL 300s)
     · hit(OWNER/MEMBER/NONE) → 즉시 판정
  2. miss → access-svc GET /internal/authz/... (connect 2s/read 3s)
     · 결과를 TTL 300s로 캐시 후 판정
  3. HTTP 실패 → WorkspaceNotFoundException (fail-closed, 404)
```

access-svc는 멤버십 변경 시 projection을 write-through/무효화한다(생성 시 put, 삭제·복구 시 evict). 이 구조 덕에 **access-svc가 죽어도 캐시 warm 상태의 문서 기능은 계속 동작한다**(TTL 내). 실검증은 [`verification.md`](verification.md) 참조.

## 4. 데이터 소유

| 영역 | 소유 | 저장소 |
|---|---|---|
| users·oauth·refresh token·workspaces·members | access-svc | PostgreSQL (같은 DB, schema 분리는 후속) |
| 권한 projection·OAuth 교환 코드 | access-svc | Redis |
| 문서·폴더·채팅·Wiki·operation·버전 이력 | document-svc | PostgreSQL (Flyway 소유) |
| query run 상태·SSE 이벤트 | document-svc | Redis (list replay + pub/sub) |
| 문서 원본·snapshot | document-svc | S3/MinIO |
| pipeline run·실행 로그·임베딩 | ai-svc | PostgreSQL + 볼륨 |

Idempotency 테이블은 전환기 동안 java-shared로 두 앱이 공유한다(같은 DB). 물리 DB 분할(Access RDS / Core RDS)은 후속 단계.

## 5. 남은 목표 (문서 대비)

- 물리 DB 분할 (현재 같은 PostgreSQL, schema·계정 분리 → 별도 RDS)
- RS256 + JWKS (현재 HS256 공유 시크릿)
- query·result를 Kafka result topic으로 (현재 result·heartbeat는 HTTP 콜백)
- converter 제품 경로 연결, 임베딩 worker 분리
- Terraform·Secrets Manager (AWS 실배포)
