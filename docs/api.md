# Fruition API

> 상태: 현재 구현 기준
> 기준: `backend/src/main/java/**` controller와 `llmPipeline/app/**` route

이 문서는 현재 공개 API와 내부 pipeline API의 진입점이다. 필드별 상세 예시와 오류 코드는 [도메인별 API spec](./spec/api/)과 [Backend–llmPipeline 상세 계약](./spec/llmpipeline-backend-api-contract.md)을 참고한다. 문서와 코드가 충돌하면 controller annotation, DTO validation, 통합 테스트를 우선한다.

## 1. 공통 규칙

### Base URL

```text
Spring Backend: http://localhost:8080/api
FastAPI Pipeline: http://localhost:8000
```

### 사용자 인증

사용자 요청은 다음 헤더를 사용한다.

```http
Authorization: Bearer {access_token}
```

현재 Spring Security는 `/api/auth/me`와 `/api/workspaces/**`를 명시적으로 인증하고, Workspace 하위 service는 membership을 다시 확인한다. membership이 없거나 대상 Workspace가 없으면 `404 WORKSPACE_NOT_FOUND`로 응답해 리소스 존재 여부를 숨긴다.

### 내부 서비스 인증

Spring과 FastAPI 사이의 요청은 사용자 Bearer token과 별도로 다음 헤더를 사용한다.

```http
X-Internal-Token: {INTERNAL_CALLBACK_TOKEN}
```

Agent Skill 기능을 활성화하면 Agent 관리 경로에는 다음 헤더가 추가된다.

```http
X-Agent-Service-Token: {AGENT_INTERNAL_TOKEN}
```

프론트엔드는 FastAPI를 직접 호출하지 않는다.

현재 `llmPipeline` middleware는 내부 route에 `X-Internal-Token`을 요구하지만, Spring의 일부 `RestClient` requester에서 해당 header 주입이 확인되지 않는다. 따라서 token이 설정된 환경에서는 backend→pipeline 요청이 `401`이 될 수 있으며, 이 문서의 header는 목표 계약과 직접 호출 시 검증 기준으로 사용한다. 반대로 Spring의 문서 heartbeat·Query event callback은 현재 flat endpoint로 token 검사가 없고, AI operation result callback만 header를 검증한다.

### 오류 형식

Spring 제품 API의 대표 오류 형식은 다음과 같다.

```json
{
  "error": {
    "code": "DOCUMENT_NOT_FOUND",
    "message": "문서를 찾을 수 없습니다."
  }
}
```

주요 상태 코드는 `400` 입력 검증, `401` 인증, `404` 리소스·Workspace 은닉, `409` 멱등성·version 충돌, `413` 크기 초과, `415` 파일 형식, `422` 처리 계약 위반, `502/503` pipeline 장애다.

### Versioning

현재 URL에 `/v1` prefix는 없다. 호환 가능한 필드는 additive change로 추가하고, breaking change는 controller DTO·상세 계약·프론트 client를 함께 변경한다.

## 2. Spring 공개 API

### Auth

인증 없이 호출하는 API:

```text
POST /api/auth/email-verifications
POST /api/auth/email-verifications/{verification_id}/confirm
POST /api/auth/password-reset
POST /api/auth/signup
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
POST /api/auth/oauth/exchange
GET  /oauth2/authorization/{provider}
```

인증된 사용자 API:

```text
GET /api/auth/me
```

비밀번호는 BCrypt hash로 저장하고 refresh token·email verification 값은 hash만 저장한다.

### Workspace

```text
POST   /api/workspaces
GET    /api/workspaces
PATCH  /api/workspaces/{workspace_id}
DELETE /api/workspaces/{workspace_id}
GET    /api/workspaces/trash
POST   /api/workspaces/{workspace_id}/restore
```

현재 Workspace member role은 `owner` 중심이며, 일반 멤버 초대·권한 변경 API는 구현 범위에 포함되지 않는다.

### Documents and folders

```text
POST   /api/workspaces/{workspace_id}/documents
POST   /api/workspaces/{workspace_id}/documents/markdown
GET    /api/workspaces/{workspace_id}/documents
GET    /api/workspaces/{workspace_id}/documents/{document_id}
GET    /api/workspaces/{workspace_id}/documents/{document_id}/original
GET    /api/workspaces/{workspace_id}/documents/{document_id}/export
GET    /api/workspaces/{workspace_id}/documents/{document_id}/blocks
PATCH  /api/workspaces/{workspace_id}/documents/{document_id}/rename
DELETE /api/workspaces/{workspace_id}/documents/{document_id}
POST   /api/workspaces/{workspace_id}/documents/{document_id}/restore
POST   /api/workspaces/{workspace_id}/documents/{document_id}/duplicate
PUT    /api/workspaces/{workspace_id}/documents/{document_id}/content
POST   /api/workspaces/{workspace_id}/documents/{document_id}/ingest
GET    /api/workspaces/{workspace_id}/documents/{document_id}/versions
GET    /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}
GET    /api/workspaces/{workspace_id}/documents/{document_id}/diff
POST   /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}/restore
PATCH  /api/workspaces/{workspace_id}/documents/{document_id}/position

POST   /api/workspaces/{workspace_id}/folders
PATCH  /api/workspaces/{workspace_id}/folders/{folder_id}
PATCH  /api/workspaces/{workspace_id}/folders/{folder_id}/position
GET    /api/workspaces/{workspace_id}/folders/{folder_id}/children
DELETE /api/workspaces/{workspace_id}/folders/{folder_id}
POST   /api/workspaces/{workspace_id}/folders/{folder_id}/restore
GET    /api/workspaces/{workspace_id}/navigation
GET    /api/workspaces/{workspace_id}/navigation/breadcrumb
GET    /api/workspaces/{workspace_id}/navigation/search
GET    /api/workspaces/{workspace_id}/document-tree
GET    /api/workspaces/{workspace_id}/assets/{asset_id}/content
```

업로드는 PDF 또는 Markdown을 허용한다. Markdown은 편집 상태·version·처리 queue와 연결될 수 있고, PDF는 현재 원본 저장 흐름이 중심이다. 업로드·Markdown 생성·content save에는 `Idempotency-Key`와 `base_version`을 사용할 수 있다.

### Edit lock

```text
POST   /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock
POST   /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock/heartbeat
DELETE /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock
```

### Wiki

```text
GET   /api/workspaces/{workspace_id}/wiki/graph
GET   /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}
PATCH /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/rename
GET   /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/diff
```

Graph response는 `nodes`와 `edges`를 포함하며, 답변 하이라이트는 Query response의 `graph_context`·`traversal_paths`를 사용한다.

### Chat and Query

```text
POST   /api/workspaces/{workspace_id}/chat/sessions
GET    /api/workspaces/{workspace_id}/chat/sessions
DELETE /api/workspaces/{workspace_id}/chat/sessions/{session_id}
GET    /api/workspaces/{workspace_id}/chat/sessions/{session_id}/messages
POST   /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki
POST   /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki/preview

POST   /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query
POST   /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query/runs
GET    /api/query/runs/{request_id}
GET    /api/query/runs/{request_id}/events
POST   /api/query/runs/{request_id}/events/callback
```

동기 Query response의 핵심 필드는 다음과 같다.

```json
{
  "user_message": {},
  "assistant_message": {},
  "related_pages": [],
  "evidence_snippets": [],
  "graph_context": { "nodes": [], "edges": [] },
  "traversal_paths": []
}
```

`evidence_snippets[].rank`는 답변의 citation 순서와 대응하며, `source_document_id`와 `source_block_ids`로 원문 block을 다시 조회한다.

### Schema, maintenance, Agent

```text
POST /api/workspaces/{workspace_id}/wiki-schema/preview
POST /api/workspaces/{workspace_id}/wiki-schema/drafts
POST /api/workspaces/{workspace_id}/wiki-schema/{schema_id}/activate
GET  /api/workspaces/{workspace_id}/wiki-schema/active

POST /api/workspaces/{workspace_id}/wiki/maintenance/lint
POST /api/workspaces/{workspace_id}/agent/turn

GET  /api/workspaces/{workspace_id}/ai-operation-logs
GET  /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}
GET  /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}/restore-preview
POST /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}/restore
```

AI operation log 목록은 `type`, `status`, `cursor`, `size` filtering/pagination을 지원한다. `size` 기본값은 20, 최대값은 100이다.

## 3. Spring 내부 callback API

프론트엔드가 호출하지 않고 FastAPI가 호출하는 경로다.

```text
PATCH /api/documents/{document_id}/status
POST  /api/documents/{document_id}/pipeline-events
POST  /api/ai-operations/{operation_id}/result
```

`pipeline-events`는 진행 stage·heartbeat를 전달하고, `ai-operations/{operation_id}/result`는 operation 결과 artifact와 hash를 멱등하게 반영한다. 후자는 `X-Internal-Token`을 검증한다.

## 4. FastAPI internal API

FastAPI는 `http://localhost:8000`에서 실행되지만 제품 외부에 공개하는 API가 아니다. 현재 route group은 다음과 같다.

| 경로 | 책임 |
|---|---|
| `GET /health` | pipeline readiness 응답 |
| `GET /documents/{document_id}` | 공용 DB 문서 조회 |
| `POST /query` | Wiki retrieval·graph traversal·answer 생성 |
| `POST /pipeline/runs` | 일반 Wiki ingestion 실행 |
| `POST /pipeline/reingest-runs` | 기존 문서 재ingest |
| `POST /chat-wiki/runs` | 채팅 export ingestion |
| `PATCH /wiki/pages/{wiki_page_id}/rename` | Wiki 페이지 제목과 선택적 slug 변경 |
| `POST /wiki/maintenance/lint` | Wiki lint 및 선택적 수정 |
| `POST /wiki/ingest-restore-runs` | ingest 결과 복구 재조립 |
| `POST /wiki/lint-restore-runs` | lint 결과 복구 재조립 |
| `GET /pipeline/runs/{run_id}` | pipeline 실행 상태 조회 |
| `POST /pipeline/runs/{run_id}/result-callback/retry` | 결과 callback 재시도 |
| `GET /pipeline/runs/{run_id}/logs` | pipeline 실행 로그 조회 |
| `/wiki-schema/*` | Schema preview·draft·activate·active 조회 |
| `/agent/*` | Markdown Agent turn |
| `/agent/runs/*`, `/skills/*` | `AGENT_SKILLS_ENABLED=true`일 때만 활성화 |

`/query`, `/pipeline`, `/chat-wiki`, `/wiki/*`, `/wiki-schema`, `/documents/*`에는 `X-Internal-Token` 검사가 적용된다. Agent Skill route에는 별도 service token이 추가된다.

## 5. 상세 문서

- Auth: [docs/spec/api/auth.md](./spec/api/auth.md)
- Document: [docs/spec/api/document.md](./spec/api/document.md)
- Wiki: [docs/spec/api/wiki.md](./spec/api/wiki.md)
- Chat: [docs/spec/api/chat.md](./spec/api/chat.md)
- Query: [docs/spec/api/query.md](./spec/api/query.md)
- AI operation log: [docs/spec/api/ai-operation-log.md](./spec/api/ai-operation-log.md)
- Backend–llmPipeline 상세 계약: [docs/spec/llmpipeline-backend-api-contract.md](./spec/llmpipeline-backend-api-contract.md)
- OpenAPI UI: `http://localhost:8080/swagger-ui.html`
