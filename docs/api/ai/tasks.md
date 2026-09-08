# AI 작업 취소·복구

[API 문서](../README.md) / [ai-svc](README.md)

취소는 후속 실행을 중단하고, 해당 작업이 이미 저장한 변경을 역순으로 복구합니다.
`cancelled`는 Python과 backend의 복구 확인이 모두 끝난 상태입니다. 취소 요청의 HTTP 성공만으로
복구 완료를 판단하지 않습니다. 다른 작업의 후속 변경과 충돌하면 그 값을 보존하고 `rollback_failed`로 남깁니다.

## 공개 API

### 1. Method + Path

- `POST /api/workspaces/{workspaceId}/ai/tasks/{id}/cancel`
- `GET /api/workspaces/{workspaceId}/ai/tasks/{id}`

### 2. 목적

Query, Agent turn, 문서·채팅 수집, 수집 후처리, Wiki lint·복구, PDF 변환,
Skill 작성·게시·수정 작업의 취소와 복구 상태를 관리합니다.
Agent turn 취소는 이미 완료한 하위 Agent run과 해당 턴의 Skill 게시도 포함합니다.
일반 자율 Agent run을 직접 다루는 계약은 [Agent API](agent.md)를 따릅니다.

### 3. Auth 필요 여부

Bearer access token. 해당 workspace 구성원이면서 작업을 시작한 사용자여야 합니다.

### 4. Request body

없음. `id`는 시작 API가 반환한 작업 ID입니다. 변환은 `convert:{document_id}`입니다.
동기 Query와 Skill 작성·게시·수정은 시작 요청의 선택 query parameter `run_id`로 ID를 미리 지정할 수 있습니다.
`run_id`는 영문·숫자·`:`·`_`·`-`의 1~120자입니다. 동기 Query는 이미 사용한 ID를 거절합니다.

### 5. Response body

```json
{"id":"query_example","status":"rolling_back","error_code":""}
```

| 상태 | 의미 |
|---|---|
| `running`, `completed` | 정상 작업 기록 |
| `cancel_requested` | 취소를 저장하고 실행 중인 작업이 멈추기를 기다림 |
| `rolling_back` | AI DB·객체 저장소와 업무 DB를 복구 중 |
| `cancelled` | 복구 확인 완료 |
| `rollback_failed` | 후속 변경 충돌·불명확한 실행 결과 등으로 복구를 완료하지 못함 |

### 6. Error response

인증 실패 `401`, 권한 실패 `403`, 다른 actor의 작업 또는 없는 작업 `404`.
복구 실패는 상태의 `error_code`로 확인합니다. 연결 장애는 `rollback_retry_pending`을 남기고 자동 재시도합니다.
`rollback_failed`는 같은 취소 요청으로 재시도할 수 있으며 이미 복구한 단계는 재실행하지 않습니다.

### 7. Pagination / filtering

없음. 작업 하나를 ID로 조회합니다.

### 8. 권한·일관성 규칙

- backend는 취소 상태를 먼저 커밋해 늦은 결과와 추가 저장을 막습니다.
- Python은 실행 중인 handler·하위 작업이 끝난 뒤 AI DB와 객체 저장소의 변경 기록을 역순 복구합니다.
- Agent 도구는 변경 전 snapshot으로 역작업을 만들고, 원래 승인과 연결된 정확한 인자·별도 멱등키로 실행합니다.
- Python이 업무 DB의 미복구 변경 ID를 역순 지정하고, backend는 각 행의 현재 값·참조를 검사해 한 변경씩 원자적으로 복구합니다.
- 본문을 복구하면 새 revision과 outbox 이벤트를 생성하고 AI 파생 상태에도 동기 반영합니다. 삭제된 생성 문서는 운영 tombstone으로 늦은 편집 이벤트를 흡수합니다.
- 모든 단계가 끝난 뒤에만 `cancelled`와 Query `query.cancelled` SSE를 전달합니다. 늦은 완료 이벤트는 재생되지 않습니다.
- PDF 변환 취소는 HTTP 작업 중단을 전달하고 converter 하위 프로세스 종료를 기다린 뒤 placeholder·생성 파일을 복구합니다.
- 문서·폴더 역작업은 이름·내용·부모·순서를 복원합니다. 버전 번호와 운영 감사·멱등 기록은 되감지 않습니다.
- 새 폴더에 다른 항목이 생겼거나 게시한 Skill 버전을 다른 Agent가 사용한 경우 등은 강제 삭제하지 않습니다.
- 전송한 도구 요청의 성공 여부가 불명확하면 복구 완료를 선언하지 않습니다.

### 9. 예시 요청/응답

```http
POST /api/workspaces/ws_example/ai/tasks/query_example/cancel
Authorization: Bearer <access-token>
```

```json
{"id":"query_example","status":"cancelled","error_code":""}
```

### 10. 구현 파일

- Backend: `core/aitask/service/AiTaskCancellationService.java`, `V47__add_ai_task_rollback_journal.sql`
- Python: `app/modules/task_cancellation/`, `app/modules/agent_run/application/rollback_agent_run.py`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml`, `api-specs/pipeline/openapi.yaml`

## 내부 API

공개 취소 API가 아래 API를 호출합니다. 사용자 클라이언트에서 직접 호출하지 않습니다.

| Method + Path | 인증 | 입력 | 출력·목적 |
|---|---|---|---|
| `POST /internal/ai/tasks/{run_id}/cancel` | `X-Internal-Token` | `workspace_id`, `user_id`, 선택 `command` | `200`: AI 취소 상태. 전달 전 command도 등록해 실행 차단 |
| `GET /internal/ai/tasks/{run_id}` | `X-Internal-Token` | query `workspace_id`, `user_id` | `200`: AI 복구 상태 |
| `POST /internal/ai/tasks/{run_id}/rollback-backend` | `X-Internal-Token` | 같은 actor와 선택 `command` | `204`: Python이 업무 변경을 역순 복구. AI 복구 미완료·충돌 `409` |
| `POST /internal/ai/tasks/documents/{document_id}/cancel` | `X-Internal-Token` | `workspace_id`, `user_id` | `200`: `cleanup_complete`로 연결된 수집·Wiki 복구 완료 확인 |
| `POST /internal/agent/tools/rollback/{id}/changes` | `X-Agent-Service-Token` | `workspace_id`, `user_id` | `200`: 미복구 변경 ID 배열, 내림차순 |
| `POST /internal/agent/tools/rollback/{id}/finalize-edits` | `X-Agent-Service-Token` | 같은 actor | `200`: 본문 복구 revision·hash 이벤트 배열. 후속 변경 충돌 `409` |
| `POST /internal/agent/tools/rollback/{id}/changes/{changeId}` | `X-Agent-Service-Token` | 같은 actor | `200`: 한 변경 복구. 충돌 `409` |

취소 command의 ID·actor는 경로·body와 같아야 하며 기존 command와 내용이 다르면 `409`입니다.
내부 상태 응답의 `error_code`는 오류가 없을 때 `null`입니다. 목록 pagination은 없습니다.

## 검증 범위

- “현재까지 업로드 한 문서, 알맞은 폴더 이름 생성해서 주제별로 정리해 줘”를 기준으로
  메모리 Workspace에 기존 폴더 2개·문서 6개를 준비하고, 폴더 생성·문서 이동 9단계의 각 중단 지점에서 원래 구조 복원을 검증합니다.
- 별도 PostgreSQL schema에서 실제 DB trigger·역순 복구·동시 취소·부모/자식·Skill 게시/수정을 검증합니다.
- Backend 통합 테스트는 실제 PostgreSQL·Redis와 Python 경계를 대체한 client를 사용합니다.
- Converter 테스트는 실제 하위 프로세스 종료와 연결 중단 시 정리를 검증합니다.
- 로컬 서비스 E2E는 실제 Gemini 모델·공개 API·Kafka worker·PostgreSQL·Redis를 연결합니다.
  같은 입력으로 승인 전 변경 0회, 마지막 이동 중 취소, 생성한 폴더 2개와 이동한 문서 6개의
  역작업 8회, 원래 이름·부모·순서 복원과 `cancelled`를 확인했습니다. Query 취소 후 말풍선 제거도 확인했습니다.
  모델이 생성한 계획은 실행마다 달라질 수 있습니다. 입력·계획·복구 결과 JSON을 별도 파일로 남깁니다.
- 이 서비스 E2E가 PDF 변환·Skill·Wiki 수집의 모든 중단 지점이나 MinIO 객체 복구까지 검증한 것은 아닙니다.
  해당 경계는 별도 통합·단위 테스트로 검증합니다. 추가 수동 통합 검증에서는 실제 backend·MinIO로
  본문 편집/복구(revision 1→2→3)와 PostgreSQL 변경 기록에 따른 MinIO 원본 객체 복원을 확인했습니다.
  실행법은 [스크립트 문서](../../script.md#ai-작업-취소-e2e)를 따릅니다.

이 보장은 작업 기록을 생성하는 공개 제품 경로에 적용됩니다. 운영자가 직접 호출하는 내부 단발
`/query`, `/pipeline/**`, Wiki 유지보수 HTTP와 기존 기록 없는 작업은 공통 취소 대상으로 자동 등록되지 않습니다.
배포 전 실행 중인 구버전 작업을 종료하고 큐를 비워야 합니다. 기존 실행의 역작업을 추측해 만들지 않습니다.
