# AI API

[API 문서](../README.md) / [document-svc](README.md)

사용자용 AI 모델 설정과 AI 작업·변환·ingest Gateway API다. 모델 설정은 access-svc,
변환은 converter, ingest·Wiki 복구는 Kafka를 통해 각 소유 서비스에 전달한다.

- API 수: 9

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/ai-models`](#summary-get-api-ai-models) | 선택할 수 있는 provider/model 조합을 반환합니다. API key는 노출하지 않습니다. |
| [`GET /api/workspaces/{workspace_id}/ai-model-settings`](#summary-get-api-workspaces-workspace-id-ai-model-settings) | ingest·lint 작업에 쓰는 provider/model 설정을 반환합니다. OWNER와 MEMBER 모두 조회할 수 있습니다. |
| [`PUT /api/workspaces/{workspace_id}/ai-model-settings`](#summary-put-api-workspaces-workspace-id-ai-model-settings) | ingest·lint에 쓸 provider/model을 바꿉니다. OWNER만 호출할 수 있고, 활성 model catalog에 있는 조합만 허용합니다. |
| [`GET /api/workspaces/{workspace_id}/ai-operation-logs`](#summary-get-api-workspaces-workspace-id-ai-operation-logs) | 최신순으로 반환합니다. 문서 편집은 실제 변경에 성공한 작업만 포함하며, 로그 테이블만 읽고 diff를 계산하지 않습니다. |
| [`GET /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}`](#summary-get-api-workspaces-workspace-id-ai-operation-logs-operation-id) | 그 작업이 바꾼 리소스를 함께 반환합니다. 줄 수는 저장된 값이라 계산이 없습니다. |
| [`POST /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}/restore`](#summary-post-api-workspaces-workspace-id-ai-operation-logs-operation-id-restore) | 복구 대상에 따라 처리 방식이 다릅니다. 문서 편집 복구는 즉시 완료되어 200을 반환하고, Wiki 복구는 queued 상태로 등록되어 202를 반환합니다. 미리보기와 같은 계산을 다시 하고 Wiki에 반영합니다. 받치는 기여가 남지 않은 페이지는 삭제하고, 되돌릴 버전이 그대로 있는 페이지는 그 내용으로 복원하며, 남은 조각을 합쳐야 하는 페이지는 llmPipeline에 재작성을 맡깁니다. 재작성이 있으면 status가 rebuilding으로 돌아오며 결과는 로그 상세로 확인합니다. ingest 되돌리기는 Wiki만 되돌리고 원문 문서는 건드리지 않습니다. |
| [`GET /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}/restore-preview`](#summary-get-api-workspaces-workspace-id-ai-operation-logs-operation-id-restore-preview) | 이 작업을 되돌리면 무엇이 삭제·복원·재작성되는지 계산합니다. 지목한 작업과 그 이후 같은 문서의 작업을 전부 걷어내며, 그 과정에서 만들어진 페이지는 삭제됩니다. 문서 편집 복구는 canonical 편집 revision을 확인하며, 응답의 preview_token은 복구 실행에 그대로 전달해야 합니다. |
| [`POST /api/workspaces/{workspace_id}/documents/{document_id}/convert-markdown`](#summary-post-api-workspaces-workspace-id-documents-document-id-convert-markdown) | PDF 원본 문서를 Markdown 문서로 변환합니다. 변환 결과를 담을 편집 가능 placeholder 문서를 즉시 만들어 반환하고, 실제 변환은 백그라운드에서 진행됩니다. |
| [`POST /api/workspaces/{workspace_id}/documents/{document_id}/ingest`](#summary-post-api-workspaces-workspace-id-documents-document-id-ingest) | 편집 가능 Markdown 문서를 최신 편집본으로 다시 Wiki 파이프라인에 넣습니다. 편집본을 원본으로 승격한 뒤 재처리합니다. |

## 한눈에 보기

<a id="summary-get-api-ai-models"></a>
### `GET /api/ai-models`

| 항목 | 내용 |
|---|---|
| 목적 | 선택할 수 있는 provider/model 조합을 반환합니다. API key는 노출하지 않습니다. |
| 입력 | 없음 |
| 출력 | `200` 조회 성공 — `ModelsResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-ai-models"></a>
### `GET /api/ai-models` 상세

#### 1. Method + Path

`GET /api/ai-models`

#### 2. 목적

선택할 수 있는 provider/model 조합을 반환합니다. API key는 노출하지 않습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

- 없음

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`ModelsResponse`)

```json
{
  "models": [
    {
      "display_name": "GPT-5 nano",
      "model": "gpt-5-nano",
      "provider": "openai"
    }
  ]
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/ai-models" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "models": [
    {
      "display_name": "GPT-5 nano",
      "model": "gpt-5-nano",
      "provider": "openai"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/ai/AiModelCatalogController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: list_4`)

[↑ 요약으로 돌아가기](#summary-get-api-ai-models)

</details>

<a id="summary-get-api-workspaces-workspace-id-ai-model-settings"></a>
### `GET /api/workspaces/{workspace_id}/ai-model-settings`

| 항목 | 내용 |
|---|---|
| 목적 | ingest·lint 작업에 쓰는 provider/model 설정을 반환합니다. OWNER와 MEMBER 모두 조회할 수 있습니다. |
| 입력 | **Path** — `workspace_id`: `string` |
| 출력 | `200` 조회 성공 — `SettingsResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다.<br>그 밖의 조건은 상세 권한 규칙 참고 |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-ai-model-settings"></a>
### `GET /api/workspaces/{workspace_id}/ai-model-settings` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/ai-model-settings`

#### 2. 목적

ingest·lint 작업에 쓰는 provider/model 설정을 반환합니다. OWNER와 MEMBER 모두 조회할 수 있습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`SettingsResponse`)
- `can_update`: 호출자가 이 설정을 변경할 수 있는지(워크스페이스 OWNER 여부). MEMBER는 `false`를 받고 UI는 읽기 전용으로 표시한다.

```json
{
  "can_update": true,
  "ingest_lint": {
    "model": "gemini-3.1-flash-lite",
    "provider": "gemini"
  }
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/ai-model-settings" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "can_update": true,
  "ingest_lint": {
    "model": "gemini-3.1-flash-lite",
    "provider": "gemini"
  }
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/ai/WorkspaceAiModelSettingsController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: get`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-ai-model-settings)

</details>

<a id="summary-put-api-workspaces-workspace-id-ai-model-settings"></a>
### `PUT /api/workspaces/{workspace_id}/ai-model-settings`

| 항목 | 내용 |
|---|---|
| 목적 | ingest·lint에 쓸 provider/model을 바꿉니다. OWNER만 호출할 수 있고, 활성 model catalog에 있는 조합만 허용합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `SettingsRequest` |
| 출력 | `200` 변경 성공 — `SettingsResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다.<br>그 밖의 조건은 상세 권한 규칙 참고 |
| 주요 오류 | `400` catalog에 없는 provider/model 조합 — `ErrorResponse`<br>`403` OWNER가 아님 — `ErrorResponse`<br>`404` 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-put-api-workspaces-workspace-id-ai-model-settings"></a>
### `PUT /api/workspaces/{workspace_id}/ai-model-settings` 상세

#### 1. Method + Path

`PUT /api/workspaces/{workspace_id}/ai-model-settings`

#### 2. 목적

ingest·lint에 쓸 provider/model을 바꿉니다. OWNER만 호출할 수 있고, 활성 model catalog에 있는 조합만 허용합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`SettingsRequest`)

```json
{
  "ingest_lint": {
    "model": "gpt-5-nano",
    "provider": "openai"
  }
}
```

#### 5. Response body

- HTTP `200`: 변경 성공
- Content-Type: `*/*` (`SettingsResponse`)

```json
{
  "can_update": true,
  "ingest_lint": {
    "model": "gpt-5-nano",
    "provider": "openai"
  }
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | catalog에 없는 provider/model 조합 | `ErrorResponse` |
| `403` | OWNER가 아님 | `ErrorResponse` |
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "details": [
      {
        "field": "email",
        "reason": "email은 필수입니다."
      }
    ],
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.
- 워크스페이스 OWNER 권한이 필요하다.

#### 9. 예시 요청/응답

```bash
curl -X PUT "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/ai-model-settings" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"ingest_lint":{"model":"gpt-5-nano","provider":"openai"}}'
```

```json
{
  "can_update": true,
  "ingest_lint": {
    "model": "gpt-5-nano",
    "provider": "openai"
  }
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/ai/WorkspaceAiModelSettingsController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: update`)

[↑ 요약으로 돌아가기](#summary-put-api-workspaces-workspace-id-ai-model-settings)

</details>

<a id="summary-get-api-workspaces-workspace-id-ai-operation-logs"></a>
### `GET /api/workspaces/{workspace_id}/ai-operation-logs`

| 항목 | 내용 |
|---|---|
| 목적 | 최신순으로 반환합니다. 일반 목록에서는 진행 중 상태를 제외하고, `status=processing` 명시 조회는 활성 작업 탐지에 사용합니다. 로그 테이블만 읽으며 diff를 계산하지 않습니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Query** — `type`(선택): `string`, `status`(선택): `string`, `cursor`(선택): `string`, `size`(선택): `integer` |
| 출력 | `200` 조회 성공 — `OperationLogListResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>페이지네이션: `cursor`, `size`<br>필터링: `type`, `status`, `cursor`, `size`<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-ai-operation-logs"></a>
### `GET /api/workspaces/{workspace_id}/ai-operation-logs` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/ai-operation-logs`

#### 2. 목적

최신순으로 반환합니다. 문서 편집은 실제 변경에 성공한 작업만 포함합니다. `status`를 생략한 일반 로그 목록은 `processing`·`applying`·`notify_pending`·`rebuilding` 작업을 제외하며, `status=processing`을 명시하면 활성 Ingest·Lint 탐지에 사용할 수 있습니다. 필터는 페이지네이션 전에 DB query에서 적용하고, 로그 테이블만 읽으며 diff를 계산하지 않습니다.

`target_display_name`은 작업 시작 시점 snapshot이다. Ingest 로그 제목과 원본 문서 표시는 현재 문서 이름을 다시 조회하지 않고 이 값을 사용한다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| query | `type` | `string` | 아니요 | 작업 유형 |
| query | `status` | `string` | 아니요 | 상태 |
| query | `cursor` | `string` | 아니요 | 이전 응답의 next_cursor |
| query | `size` | `integer` | 아니요 | 페이지 크기. 기본 20, 최대 100 |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`OperationLogListResponse`)

```json
{
  "logs": [
    {
      "changed_resource_count": 3,
      "completed_at": "2026-08-14T10:00:00Z",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "operation_id": "op_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "operation_type": "ingest",
      "restored_from": "string",
      "status": "succeeded",
      "summary": "string",
      "target_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "target_display_name": "설계문서"
    }
  ],
  "next_cursor": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: `cursor`, `size`
- 필터링: `type`, `status`, `cursor`, `size`

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/ai-operation-logs?type=<value>&status=<value>&cursor=<value>&size=1" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "logs": [
    {
      "changed_resource_count": 3,
      "completed_at": "2026-08-14T10:00:00Z",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "operation_id": "op_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "operation_type": "ingest",
      "restored_from": "string",
      "status": "succeeded",
      "summary": "string",
      "target_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "target_display_name": "설계문서"
    }
  ],
  "next_cursor": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/aihistory/controller/OperationQueryController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: list_3`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-ai-operation-logs)

</details>

<a id="summary-get-api-workspaces-workspace-id-ai-operation-logs-operation-id"></a>
### `GET /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 그 작업이 바꾼 리소스를 함께 반환합니다. 줄 수는 저장된 값이라 계산이 없습니다. |
| 입력 | **Path** — `workspace_id`: `string`, `operation_id`: `string` |
| 출력 | `200` 조회 성공 — `OperationLogDetailResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 작업 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-ai-operation-logs-operation-id"></a>
### `GET /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}`

#### 2. 목적

그 작업이 바꾼 리소스를 함께 반환합니다. 줄 수는 저장된 값이라 계산이 없습니다.

`changes[].resource_display_name`은 변경 시점 snapshot이다. Lint는 workspace 작업 operation 하나를 유지하면서 실제로 수정한 Wiki 페이지를 `resource_type=wiki_page` child entry로 반환하며, 이후 페이지 rename/delete에도 이 이름은 유지된다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `operation_id` | `string` | 예 | 작업 식별자 |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`OperationLogDetailResponse`)

```json
{
  "changed_resource_count": 3,
  "changes": [
    {
      "additions": 12,
      "after_revision": 3,
      "before_revision": 2,
      "change_summary": "string",
      "change_type": "updated",
      "deletions": 4,
      "diff_too_large": true,
      "hunks": [
        {
          "lines": [
            {
              "content": "string",
              "new_line": 10,
              "old_line": 10,
              "type": "CONTEXT"
            }
          ],
          "new_lines": 5,
          "new_start": 10,
          "old_lines": 3,
          "old_start": 10
        }
      ],
      "id": 1,
      "resource_id": "string",
      "resource_display_name": "Wiki 페이지 제목"
    }
  ],
  "completed_at": "2026-08-14T10:00:00Z",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "operation_id": "op_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "operation_type": "ingest",
  "restore": {
    "plan": {
      "delete_count": 1,
      "pages": [
        {
          "action": "rebuild",
          "contribution_count": 2,
          "page_id": "string"
        }
      ],
      "rebuild_count": 3,
      "restore_count": 2
    },
    "result": {
      "deleted_count": 1,
      "failed_count": 0,
      "rebuilt_count": 3,
      "removed_link_count": 4,
      "restored_count": 2,
      "restored_link_count": 2
    }
  },
  "restored_from": "string",
  "status": "succeeded",
  "summary": "string",
  "target_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "target_display_name": "설계문서"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 작업 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/ai-operation-logs/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "changed_resource_count": 3,
  "changes": [
    {
      "additions": 12,
      "after_revision": 3,
      "before_revision": 2,
      "change_summary": "string",
      "change_type": "updated",
      "deletions": 4,
      "diff_too_large": true,
      "hunks": [
        {
          "lines": [
            {
              "content": "string",
              "new_line": 10,
              "old_line": 10,
              "type": "CONTEXT"
            }
          ],
          "new_lines": 5,
          "new_start": 10,
          "old_lines": 3,
          "old_start": 10
        }
      ],
      "id": 1,
      "resource_id": "string",
      "resource_display_name": "Wiki 페이지 제목"
    }
  ],
  "completed_at": "2026-08-14T10:00:00Z",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "operation_id": "op_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "operation_type": "ingest",
  "restore": {
    "plan": {
      "delete_count": 1,
      "pages": [
        {
          "action": "rebuild",
          "contribution_count": 2,
          "page_id": "string"
        }
      ],
      "rebuild_count": 3,
      "restore_count": 2
    },
    "result": {
      "deleted_count": 1,
      "failed_count": 0,
      "rebuilt_count": 3,
      "removed_link_count": 4,
      "restored_count": 2,
      "restored_link_count": 2
    }
  },
  "restored_from": "string",
  "status": "succeeded",
  "summary": "string",
  "target_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "target_display_name": "설계문서"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/aihistory/controller/OperationQueryController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: detail`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-ai-operation-logs-operation-id)

</details>

<a id="summary-post-api-workspaces-workspace-id-ai-operation-logs-operation-id-restore"></a>
### `POST /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}/restore`

| 항목 | 내용 |
|---|---|
| 목적 | 복구 대상에 따라 처리 방식이 다릅니다. 문서 편집 복구는 즉시 완료되어 200을 반환하고, Wiki 복구는 queued 상태로 등록되어 202를 반환합니다. 미리보기와 같은 계산을 다시 하고 Wiki에 반영합니다. 받치는 기여가 남지 않은 페이지는 삭제하고, 되돌릴 버전이 그대로 있는 페이지는 그 내용으로 복원하며, 남은 조각을 합쳐야 하는 페이지는 llmPipeline에 재작성을 맡깁니다. 재작성이 있으면 status가 rebuilding으로 돌아오며 결과는 로그 상세로 확인합니다. ingest 되돌리기는 Wiki만 되돌리고 원문 문서는 건드리지 않습니다. |
| 입력 | **Path** — `workspace_id`: `string`, `operation_id`: `string`<br>**Body** — `RestoreExecuteRequest` |
| 출력 | `200` 문서 편집 복구 즉시 완료 — `RestoreExecuteResponse`<br>`202` Wiki 복구 queued 등록 — `RestoreExecuteResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 되돌릴 수 없는 작업이거나 대상이 없음 — `ErrorResponse`<br>`404` 작업 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` 미리보기 이후 대상이 변경됨 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-ai-operation-logs-operation-id-restore"></a>
### `POST /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}/restore` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}/restore`

#### 2. 목적

복구 대상에 따라 처리 방식이 다릅니다. 문서 편집 복구는 즉시 완료되어 200을 반환하고, Wiki 복구는 queued 상태로 등록되어 202를 반환합니다. 미리보기와 같은 계산을 다시 하고 Wiki에 반영합니다. 받치는 기여가 남지 않은 페이지는 삭제하고, 되돌릴 버전이 그대로 있는 페이지는 그 내용으로 복원하며, 남은 조각을 합쳐야 하는 페이지는 llmPipeline에 재작성을 맡깁니다. 재작성이 있으면 status가 rebuilding으로 돌아오며 결과는 로그 상세로 확인합니다. ingest 되돌리기는 Wiki만 되돌리고 원문 문서는 건드리지 않습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `operation_id` | `string` | 예 | - |

- Content-Type: `application/json` (`RestoreExecuteRequest`)

```json
{
  "preview_token": "string"
}
```

#### 5. Response body

- HTTP `200`: 문서 편집 복구 즉시 완료
- HTTP `202`: Wiki 복구 작업이 대기열에 등록됨
- Content-Type: `*/*` (`RestoreExecuteResponse`)

```json
{
  "delete_count": 1,
  "operation_id": "op_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "rebuild_count": 3,
  "rebuilding": true,
  "restore_count": 2,
  "restored_from": "op_8d4f1e6c3b0a97d25e4f831b9f4c7e2a",
  "run_id": "string",
  "status": "rebuilding"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 되돌릴 수 없는 작업이거나 대상이 없음 | `ErrorResponse` |
| `404` | 작업 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 미리보기 이후 대상이 변경됨 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "details": [
      {
        "field": "email",
        "reason": "email은 필수입니다."
      }
    ],
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/ai-operation-logs/<value>/restore" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"preview_token":"<value>"}'
```

```json
{
  "delete_count": 1,
  "operation_id": "op_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "rebuild_count": 3,
  "rebuilding": true,
  "restore_count": 2,
  "restored_from": "op_8d4f1e6c3b0a97d25e4f831b9f4c7e2a",
  "run_id": "string",
  "status": "rebuilding"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/aihistory/controller/OperationQueryController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: restore_2`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-ai-operation-logs-operation-id-restore)

</details>

<a id="summary-get-api-workspaces-workspace-id-ai-operation-logs-operation-id-restore-preview"></a>
### `GET /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}/restore-preview`

| 항목 | 내용 |
|---|---|
| 목적 | 이 작업을 되돌리면 무엇이 삭제·복원·재작성되는지 계산합니다. 지목한 작업과 그 이후 같은 문서의 작업을 전부 걷어내며, 그 과정에서 만들어진 페이지는 삭제됩니다. 문서 편집 복구는 canonical 편집 revision을 확인하며, 응답의 preview_token은 복구 실행에 그대로 전달해야 합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `operation_id`: `string` |
| 출력 | `200` 계산 성공 — `RestorePreviewResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 작업 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-ai-operation-logs-operation-id-restore-preview"></a>
### `GET /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}/restore-preview` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}/restore-preview`

#### 2. 목적

이 작업을 되돌리면 무엇이 삭제·복원·재작성되는지 계산합니다. 지목한 작업과 그 이후 같은 문서의 작업을 전부 걷어내며, 그 과정에서 만들어진 페이지는 삭제됩니다. 문서 편집 복구는 canonical 편집 revision을 확인하며, 응답의 preview_token은 복구 실행에 그대로 전달해야 합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `operation_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 계산 성공
- Content-Type: `*/*` (`RestorePreviewResponse`)

```json
{
  "delete_count": 1,
  "document": {
    "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
    "from_version": 5,
    "to_version": 3
  },
  "operation_id": "op_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "pages": [
    {
      "action": "rebuild",
      "contribution_count": 2,
      "page_id": "string",
      "target_revision": 4
    }
  ],
  "preview_token": "string",
  "rebuild_count": 3,
  "restore_count": 2
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 작업 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/ai-operation-logs/<value>/restore-preview" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "delete_count": 1,
  "document": {
    "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
    "from_version": 5,
    "to_version": 3
  },
  "operation_id": "op_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "pages": [
    {
      "action": "rebuild",
      "contribution_count": 2,
      "page_id": "string",
      "target_revision": 4
    }
  ],
  "preview_token": "string",
  "rebuild_count": 3,
  "restore_count": 2
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/aihistory/controller/OperationQueryController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: restorePreview`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-ai-operation-logs-operation-id-restore-preview)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-document-id-convert-markdown"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/convert-markdown`

| 항목 | 내용 |
|---|---|
| 목적 | PDF 원본 문서를 Markdown 문서로 변환합니다. 변환 결과를 담을 편집 가능 placeholder 문서를 즉시 만들어 반환하고, 실제 변환은 백그라운드에서 진행됩니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string`<br>**Header** — `Idempotency-Key`: `string` |
| 출력 | `202` 변환 요청 접수 및 placeholder 문서 생성 — `DocumentUploadResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` PDF 원본 문서가 아니거나 잘못된 Idempotency-Key — `ErrorResponse`<br>`404` 문서 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Idempotency-Key 충돌 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-document-id-convert-markdown"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/convert-markdown` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/convert-markdown`

#### 2. 목적

PDF 원본 문서를 Markdown 문서로 변환합니다. 변환 결과를 담을 편집 가능 placeholder 문서를 즉시 만들어 반환하고, 실제 변환은 백그라운드에서 진행됩니다. worker는 실행 시점의 workspace `ingest_lint` provider/model을 converter에 전달하며 converter는 선택 provider의 API key로 복원합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | PDF 원본 문서 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Body: 없음

#### 5. Response body

- HTTP `202`: 변환 요청 접수 및 placeholder 문서 생성
- Content-Type: `*/*` (`DocumentUploadResponse`)

```json
{
  "byte_size": 482913,
  "current_version": 1,
  "document_role": "EDITABLE",
  "editable": false,
  "filename": "설계문서.pdf",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "mime_type": "application/pdf",
  "source_uri": "string",
  "status": "uploaded",
  "uploaded_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | PDF 원본 문서가 아니거나 잘못된 Idempotency-Key | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Idempotency-Key 충돌 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "details": [
      {
        "field": "email",
        "reason": "email은 필수입니다."
      }
    ],
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/convert-markdown" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>'
```

```json
{
  "byte_size": 482913,
  "current_version": 1,
  "document_role": "EDITABLE",
  "editable": false,
  "filename": "설계문서.pdf",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "mime_type": "application/pdf",
  "source_uri": "string",
  "status": "uploaded",
  "uploaded_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: convertMarkdown`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-document-id-convert-markdown)

</details>

<a id="summary-post-api-workspaces-workspace-id-documents-document-id-ingest"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/ingest`

| 항목 | 내용 |
|---|---|
| 목적 | 편집 가능 Markdown 문서를 최신 편집본으로 다시 Wiki 파이프라인에 넣습니다. 편집본을 원본으로 승격한 뒤 재처리합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `document_id`: `string` |
| 출력 | `202` 재처리 큐 등록됨 — `DocumentIngestResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 편집 가능한 Markdown 문서가 아님 — `ErrorResponse`<br>`403` 문서 소유자가 아님 — `ErrorResponse`<br>`404` 문서 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` 이미 처리 중인 문서 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-documents-document-id-ingest"></a>
### `POST /api/workspaces/{workspace_id}/documents/{document_id}/ingest` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/ingest`

#### 2. 목적

편집 가능 Markdown 문서를 최신 편집본으로 다시 Wiki 파이프라인에 넣습니다. 편집본을 원본으로 승격한 뒤 재처리합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `202`: 재처리 큐 등록됨
- Content-Type: `*/*` (`DocumentIngestResponse`)

```json
{
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "run_id": "string",
  "status": "uploaded"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 편집 가능한 Markdown 문서가 아님 | `ErrorResponse` |
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 이미 처리 중인 문서 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "details": [
      {
        "field": "email",
        "reason": "email은 필수입니다."
      }
    ],
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/ingest" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "run_id": "string",
  "status": "uploaded"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: ingest`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-documents-document-id-ingest)

</details>
