# Workspaces API

[API 문서](../README.md) / [access-svc](README.md)

워크스페이스 관리와 서비스 간 인가·AI 모델 설정 API다.

- API 수: 10

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces`](#summary-get-api-workspaces) | 로그인한 사용자가 소유한 워크스페이스 목록을 반환합니다. |
| [`POST /api/workspaces`](#summary-post-api-workspaces) | 로그인한 사용자 소유의 워크스페이스를 생성합니다. |
| [`GET /api/workspaces/trash`](#summary-get-api-workspaces-trash) | 소유자가 삭제한 워크스페이스를 반환합니다. |
| [`PATCH /api/workspaces/{workspace_id}`](#summary-patch-api-workspaces-workspace-id) | 로그인한 사용자가 소유한 워크스페이스의 이름을 변경합니다. |
| [`DELETE /api/workspaces/{workspace_id}`](#summary-delete-api-workspaces-workspace-id) | 소유한 워크스페이스를 하위 데이터 변경 없이 소프트 삭제합니다. |
| [`POST /api/workspaces/{workspace_id}/restore`](#summary-post-api-workspaces-workspace-id-restore) | 소프트 삭제한 워크스페이스와 기존 하위 데이터의 접근을 복구합니다. |
| [`GET /internal/authz/workspaces/{workspace_id}/users/{user_id}`](#summary-get-internal-authz-workspaces-workspace-id-users-user-id) | 워크스페이스에서 사용자의 활성 역할을 조회합니다. |
| [`GET /internal/users/{user_id}`](#summary-get-internal-users-user-id) | 워크스페이스에서 사용자의 활성 역할을 조회합니다. |
| [`GET /internal/workspaces/{workspace_id}/ai-model-settings`](#summary-get-internal-workspaces-workspace-id-ai-model-settings) | 내부 서비스가 사용자의 표시 이름을 조회합니다. |
| [`PUT /internal/workspaces/{workspace_id}/ai-model-settings`](#summary-put-internal-workspaces-workspace-id-ai-model-settings) | 내부 서비스가 사용자의 표시 이름을 조회합니다. |

## 한눈에 보기

<a id="summary-get-api-workspaces"></a>
### `GET /api/workspaces`

| 항목 | 내용 |
|---|---|
| 목적 | 로그인한 사용자가 소유한 워크스페이스 목록을 반환합니다. |
| 입력 | 없음 |
| 출력 | `200` 조회 성공 — `WorkspaceListResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces"></a>
### `GET /api/workspaces` 상세

#### 1. Method + Path

`GET /api/workspaces`

#### 2. 목적

로그인한 사용자가 소유한 워크스페이스 목록을 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

- 없음

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`WorkspaceListResponse`)

```json
{
  "workspaces": [
    {
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "ws_9d47a0e9a6324341b47562553b75f92a",
      "name": "내 워크스페이스",
      "updated_at": "2026-08-13T04:25:24.371948Z"
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
curl -X GET "$ACCESS/api/workspaces" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "workspaces": [
    {
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "ws_9d47a0e9a6324341b47562553b75f92a",
      "name": "내 워크스페이스",
      "updated_at": "2026-08-13T04:25:24.371948Z"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/WorkspaceController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: list`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces)

</details>

<a id="summary-post-api-workspaces"></a>
### `POST /api/workspaces`

| 항목 | 내용 |
|---|---|
| 목적 | 로그인한 사용자 소유의 워크스페이스를 생성합니다. |
| 입력 | **Body** — `WorkspaceCreateRequest` |
| 출력 | `201` 생성 성공 — `WorkspaceResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다. |
| 주요 오류 | `400` 잘못된 요청 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces"></a>
### `POST /api/workspaces` 상세

#### 1. Method + Path

`POST /api/workspaces`

#### 2. 목적

로그인한 사용자 소유의 워크스페이스를 생성합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`WorkspaceCreateRequest`)

```json
{
  "name": "내 워크스페이스"
}
```

#### 5. Response body

- HTTP `201`: 생성 성공
- Content-Type: `*/*` (`WorkspaceResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "ws_9d47a0e9a6324341b47562553b75f92a",
  "name": "내 워크스페이스",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 | `ErrorResponse` |

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

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/workspaces" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"name":"내 워크스페이스"}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "ws_9d47a0e9a6324341b47562553b75f92a",
  "name": "내 워크스페이스",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/WorkspaceController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: create`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces)

</details>

<a id="summary-get-api-workspaces-trash"></a>
### `GET /api/workspaces/trash`

| 항목 | 내용 |
|---|---|
| 목적 | 소유자가 삭제한 워크스페이스를 반환합니다. |
| 입력 | 없음 |
| 출력 | `200` 성공 — `WorkspaceTrashResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-trash"></a>
### `GET /api/workspaces/trash` 상세

#### 1. Method + Path

`GET /api/workspaces/trash`

#### 2. 목적

소유자가 삭제한 워크스페이스를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

- 없음

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`WorkspaceTrashResponse`)

```json
{
  "workspaces": [
    {
      "deleted_at": "2026-08-13T04:25:24.371948Z",
      "deleted_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
      "id": "ws_9d47a0e9a6324341b47562553b75f92a",
      "name": "내 워크스페이스"
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
curl -X GET "$ACCESS/api/workspaces/trash" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "workspaces": [
    {
      "deleted_at": "2026-08-13T04:25:24.371948Z",
      "deleted_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
      "id": "ws_9d47a0e9a6324341b47562553b75f92a",
      "name": "내 워크스페이스"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/WorkspaceController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: trash`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-trash)

</details>

<a id="summary-patch-api-workspaces-workspace-id"></a>
### `PATCH /api/workspaces/{workspace_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 로그인한 사용자가 소유한 워크스페이스의 이름을 변경합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `WorkspaceRenameRequest` |
| 출력 | `200` 변경 성공 — `WorkspaceResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-patch-api-workspaces-workspace-id"></a>
### `PATCH /api/workspaces/{workspace_id}` 상세

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}`

#### 2. 목적

로그인한 사용자가 소유한 워크스페이스의 이름을 변경합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | 워크스페이스 ID |

- Content-Type: `application/json` (`WorkspaceRenameRequest`)

```json
{
  "name": "이름 바꾼 워크스페이스"
}
```

#### 5. Response body

- HTTP `200`: 변경 성공
- Content-Type: `*/*` (`WorkspaceResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "ws_9d47a0e9a6324341b47562553b75f92a",
  "name": "내 워크스페이스",
  "updated_at": "2026-08-13T04:25:24.371948Z"
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
curl -X PATCH "$ACCESS/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"name":"이름 바꾼 워크스페이스"}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "ws_9d47a0e9a6324341b47562553b75f92a",
  "name": "내 워크스페이스",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/WorkspaceController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: rename`)

[↑ 요약으로 돌아가기](#summary-patch-api-workspaces-workspace-id)

</details>

<a id="summary-delete-api-workspaces-workspace-id"></a>
### `DELETE /api/workspaces/{workspace_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 소유한 워크스페이스를 하위 데이터 변경 없이 소프트 삭제합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `Idempotency-Key`(선택): `string` |
| 출력 | `200` 삭제 성공 — `WorkspaceLifecycleResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 Idempotency-Key — `ErrorResponse`<br>`404` 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` Idempotency-Key 충돌 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-delete-api-workspaces-workspace-id"></a>
### `DELETE /api/workspaces/{workspace_id}` 상세

#### 1. Method + Path

`DELETE /api/workspaces/{workspace_id}`

#### 2. 목적

소유한 워크스페이스를 하위 데이터 변경 없이 소프트 삭제합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | 워크스페이스 ID |
| header | `Idempotency-Key` | `string` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 삭제 성공
- Content-Type: `*/*` (`WorkspaceLifecycleResponse`)

```json
{
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "ws_9d47a0e9a6324341b47562553b75f92a"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Idempotency-Key | `ErrorResponse` |
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
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
curl -X DELETE "$ACCESS/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>'
```

```json
{
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "ws_9d47a0e9a6324341b47562553b75f92a"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/WorkspaceController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: delete`)

[↑ 요약으로 돌아가기](#summary-delete-api-workspaces-workspace-id)

</details>

<a id="summary-post-api-workspaces-workspace-id-restore"></a>
### `POST /api/workspaces/{workspace_id}/restore`

| 항목 | 내용 |
|---|---|
| 목적 | 소프트 삭제한 워크스페이스와 기존 하위 데이터의 접근을 복구합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `Idempotency-Key`(선택): `string` |
| 출력 | `200` 복구 성공 — `WorkspaceLifecycleResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `400` 잘못된 Idempotency-Key — `ErrorResponse`<br>`404` 삭제 workspace 또는 소유권을 찾을 수 없음 — `ErrorResponse`<br>`409` Idempotency-Key 충돌 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-restore"></a>
### `POST /api/workspaces/{workspace_id}/restore` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/restore`

#### 2. 목적

소프트 삭제한 워크스페이스와 기존 하위 데이터의 접근을 복구합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 복구 성공
- Content-Type: `*/*` (`WorkspaceLifecycleResponse`)

```json
{
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "ws_9d47a0e9a6324341b47562553b75f92a"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Idempotency-Key | `ErrorResponse` |
| `404` | 삭제 workspace 또는 소유권을 찾을 수 없음 | `ErrorResponse` |
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
curl -X POST "$ACCESS/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/restore" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>'
```

```json
{
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "ws_9d47a0e9a6324341b47562553b75f92a"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/WorkspaceController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: restore`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-restore)

</details>

<a id="summary-get-internal-authz-workspaces-workspace-id-users-user-id"></a>
### `GET /internal/authz/workspaces/{workspace_id}/users/{user_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스의 AI 모델 설정을 조회합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `user_id`: `string`<br>**Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string` |
| 출력 | `200` 성공 — `object` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `401` 내부 인증 토큰 누락 또는 불일치 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-internal-authz-workspaces-workspace-id-users-user-id"></a>
### `GET /internal/authz/workspaces/{workspace_id}/users/{user_id}` 상세

#### 1. Method + Path

`GET /internal/authz/workspaces/{workspace_id}/users/{user_id}`

#### 2. 목적

워크스페이스의 AI 모델 설정을 조회합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `user_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `string` | 예 (인증 계층 검증) | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*`

```json
{
}
```

#### 6. Error response

- HTTP `401`: 내부 인증 토큰 누락 또는 불일치

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$ACCESS/internal/authz/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/users/<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/InternalAuthzController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: role`)

[↑ 요약으로 돌아가기](#summary-get-internal-authz-workspaces-workspace-id-users-user-id)

</details>

<a id="summary-get-internal-users-user-id"></a>
### `GET /internal/users/{user_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스의 AI 모델 설정을 조회합니다. |
| 입력 | **Path** — `user_id`: `string`<br>**Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string` |
| 출력 | `200` 성공 — `object` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `401` 내부 인증 토큰 누락 또는 불일치 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-internal-users-user-id"></a>
### `GET /internal/users/{user_id}` 상세

#### 1. Method + Path

`GET /internal/users/{user_id}`

#### 2. 목적

워크스페이스의 AI 모델 설정을 조회합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `user_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `string` | 예 (인증 계층 검증) | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*`

```json
{
}
```

#### 6. Error response

- HTTP `401`: 내부 인증 토큰 누락 또는 불일치

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$ACCESS/internal/users/<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/WorkspaceController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: user`)

[↑ 요약으로 돌아가기](#summary-get-internal-users-user-id)

</details>

<a id="summary-get-internal-workspaces-workspace-id-ai-model-settings"></a>
### `GET /internal/workspaces/{workspace_id}/ai-model-settings`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스의 AI 모델 설정을 변경합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string` |
| 출력 | `200` 성공 — `object` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `401` 내부 인증 토큰 누락 또는 불일치 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-internal-workspaces-workspace-id-ai-model-settings"></a>
### `GET /internal/workspaces/{workspace_id}/ai-model-settings` 상세

#### 1. Method + Path

`GET /internal/workspaces/{workspace_id}/ai-model-settings`

#### 2. 목적

워크스페이스의 AI 모델 설정을 변경합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `string` | 예 (인증 계층 검증) | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*`

```json
{
}
```

#### 6. Error response

- HTTP `401`: 내부 인증 토큰 누락 또는 불일치

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$ACCESS/internal/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/ai-model-settings" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/InternalWorkspaceAiModelController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: get`)

[↑ 요약으로 돌아가기](#summary-get-internal-workspaces-workspace-id-ai-model-settings)

</details>

<a id="summary-put-internal-workspaces-workspace-id-ai-model-settings"></a>
### `PUT /internal/workspaces/{workspace_id}/ai-model-settings`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스의 AI 모델 설정을 변경합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string`<br>**Body** — `WorkspaceAiModelRequest` |
| 출력 | `200` 성공 — `object` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `401` 내부 인증 토큰 누락 또는 불일치 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-put-internal-workspaces-workspace-id-ai-model-settings"></a>
### `PUT /internal/workspaces/{workspace_id}/ai-model-settings` 상세

#### 1. Method + Path

`PUT /internal/workspaces/{workspace_id}/ai-model-settings`

#### 2. 목적

워크스페이스의 AI 모델 설정을 변경합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `string` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`WorkspaceAiModelRequest`)

```json
{
  "ingest_lint": {
    "model": "gpt-5-nano",
    "provider": "openai"
  }
}
```

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*`

```json
{
}
```

#### 6. Error response

- HTTP `401`: 내부 인증 토큰 누락 또는 불일치

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X PUT "$ACCESS/internal/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/ai-model-settings" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"ingest_lint":{"model":"gpt-5-nano","provider":"openai"}}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/InternalWorkspaceAiModelController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: update`)

[↑ 요약으로 돌아가기](#summary-put-internal-workspaces-workspace-id-ai-model-settings)

</details>
