# Invitations API

[API 문서](../README.md) / [access-svc](README.md)

이메일 주소로 워크스페이스에 초대하고, 받은 사람이 수락해 멤버가 되는 API다.

- API 수: 5

## 설계 전제

계정은 `(email, provider)` 단위로 분리돼 있다. 같은 `a@b.com`이라도 일반 가입 계정과 구글 계정은
서로 다른 계정이므로, **이메일만으로는 어느 계정을 멤버로 넣을지 정할 수 없다.**

그래서 초대는 계정이 아니라 **이메일 주소**를 향한다. 어느 계정이 멤버가 될지는 수락하는 사람이
어느 계정으로 로그인해 있느냐로 정해진다. 수락 시 로그인 계정의 이메일이 초대받은 주소와 같은지
검증하며, 이 검증이 없으면 링크가 유출되는 순간 아무 계정이나 워크스페이스에 들어올 수 있다.

토큰은 발급 원문을 메일 링크로만 전달하고 DB에는 SHA-256 해시만 저장한다.
대기 중 초대는 `(workspace_id, email)`당 1건이며, 같은 주소를 다시 초대하면 새 행이 아니라
기존 초대의 토큰과 만료가 갱신된다(옛 링크는 그 시점에 무효).

## API 목차

| API | 목적 |
|---|---|
| [`POST /api/workspaces/{workspace_id}/invitations`](#summary-post-invitations) | 이메일 주소로 초대 링크를 발송합니다. |
| [`GET /api/workspaces/{workspace_id}/invitations`](#summary-get-invitations) | 아직 수락되지 않은 초대를 반환합니다. |
| [`DELETE /api/workspaces/{workspace_id}/invitations/{invitation_id}`](#summary-delete-invitations) | 대기 중인 초대를 취소해 링크를 무효화합니다. |
| [`GET /api/invitations/{token}`](#summary-get-invitation-preview) | 초대 링크 화면이 로그인 전에 부르는 미리보기입니다. |
| [`POST /api/invitations/{token}/accept`](#summary-post-invitation-accept) | 로그인한 계정을 워크스페이스 멤버로 편입합니다. |

## 한눈에 보기

<a id="summary-post-invitations"></a>
### `POST /api/workspaces/{workspace_id}/invitations`

| 항목 | 내용 |
|---|---|
| 목적 | 이메일 주소로 초대 링크를 발송합니다. 대기 중 초대가 있으면 새 링크로 재발송합니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `WorkspaceInvitationCreateRequest` |
| 출력 | `201` 초대 발송 성공 — `WorkspaceInvitationResponse` |
| 조건 | 인증 필요<br>호출자의 역할이 `OWNER`여야 한다. |
| 주요 오류 | `403` OWNER 권한 없음<br>`404` 워크스페이스를 찾을 수 없음<br>`409` 이미 워크스페이스 멤버임<br>`502` 초대 메일 발송 실패 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/invitations`

#### 2. 목적

이메일 주소로 초대 링크를 발송한다. 같은 주소로 대기 중 초대가 있으면 새 행을 만들지 않고
토큰·만료·역할을 갱신해 재발송한다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | 워크스페이스 ID |
| body | `email` | `string` | 예 | 초대할 이메일 주소(255자 이하). 서버가 trim·소문자화한다 |
| body | `role` | `string` | 예 | 수락 시 부여할 역할. `OWNER` 또는 `MEMBER` |

```json
{
  "email": "member@example.com",
  "role": "MEMBER"
}
```

#### 5. Response body

- HTTP `201`: 초대 발송 성공

```json
{
  "invitation_id": "inv_3c1d8e2f4a5b4c6d8e9f0a1b2c3d4e5f",
  "email": "member@example.com",
  "role": "MEMBER",
  "expires_at": "2026-09-14T04:25:24.371948Z",
  "invited_at": "2026-09-07T04:25:24.371948Z"
}
```

응답에 토큰은 없다. 토큰은 수신자의 메일 링크로만 전달된다.

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `403` | OWNER 권한 없음 | `WORKSPACE_ACCESS_DENIED` |
| `404` | 워크스페이스를 찾을 수 없거나 호출자가 멤버가 아님 | `WORKSPACE_NOT_FOUND` |
| `409` | 그 이메일의 계정 중 하나가 이미 멤버임 | `ALREADY_MEMBER` |
| `409` | 같은 주소로 초대가 동시에 진행 중임 | `INVITATION_IN_PROGRESS` |
| `502` | 초대 메일 발송 실패 | `INVITATION_SEND_FAILED` |

`502`가 나도 초대 행은 남는다. DB 커밋과 SMTP 발송을 분리해 외부 메일 서버 왕복 동안
DB 커넥션을 붙잡지 않기 때문이며, 재초대하면 같은 행이 새 링크로 덮어쓰인다.

`INVITATION_IN_PROGRESS`는 같은 주소로 요청이 동시에 들어와 대기 중 초대 unique 제약에
걸린 경우다. 먼저 들어온 요청이 이미 메일을 보냈으므로 재시도할 필요는 없다.

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 호출자가 멤버가 아니면 `404`, 멤버지만 `OWNER`가 아니면 `403`이다.
- 소프트 삭제된 워크스페이스에는 초대할 수 없다.
- 초대 자체는 멤버십을 만들지 않으므로 인가 projection을 건드리지 않는다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/invitations" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"email":"member@example.com","role":"MEMBER"}'
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/WorkspaceInvitationController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: invite`)

[↑ 요약으로 돌아가기](#summary-post-invitations)

</details>

<a id="summary-get-invitations"></a>
### `GET /api/workspaces/{workspace_id}/invitations`

| 항목 | 내용 |
|---|---|
| 목적 | 아직 수락되지 않은 초대를 생성 순으로 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string` |
| 출력 | `200` 조회 성공 — `WorkspaceInvitationListResponse` |
| 조건 | 인증 필요<br>호출자의 역할이 `OWNER`여야 한다. |
| 주요 오류 | `403` OWNER 권한 없음<br>`404` 워크스페이스를 찾을 수 없음 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/invitations`

#### 2. 목적

대기 중(수락·취소되지 않은) 초대를 반환한다. 만료된 초대도 취소 전까지는 목록에 남아
소유자가 재발송·취소를 선택할 수 있다.

#### 3. Auth 필요 여부

- 필요

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | 워크스페이스 ID |

- 요청 본문 없음

#### 5. Response body

```json
{
  "invitations": [
    {
      "invitation_id": "inv_3c1d8e2f4a5b4c6d8e9f0a1b2c3d4e5f",
      "email": "member@example.com",
      "role": "MEMBER",
      "expires_at": "2026-09-14T04:25:24.371948Z",
      "invited_at": "2026-09-07T04:25:24.371948Z"
    }
  ]
}
```

멤버 목록은 [`GET /api/workspaces/{workspace_id}/members`](workspaces.md#summary-get-api-workspaces-workspace-id-members)로
따로 조회한다. 대기 중 초대는 `user_id`도 `joined_at`도 없어 멤버와 같은 표에 담기지 않는다.

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `403` | OWNER 권한 없음 | `WORKSPACE_ACCESS_DENIED` |
| `404` | 워크스페이스를 찾을 수 없거나 호출자가 멤버가 아님 | `WORKSPACE_NOT_FOUND` |

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 정렬: `created_at` 오름차순 고정

#### 8. 권한 규칙

- `OWNER`만 조회할 수 있다. 초대 대상 이메일은 일반 멤버에게 노출하지 않는다.

#### 9. 예시 요청/응답

```bash
curl "$ACCESS/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/invitations" \
  -H 'Authorization: Bearer <access_token>'
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/WorkspaceInvitationController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: listPending`)

[↑ 요약으로 돌아가기](#summary-get-invitations)

</details>

<a id="summary-delete-invitations"></a>
### `DELETE /api/workspaces/{workspace_id}/invitations/{invitation_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 대기 중인 초대를 취소해 링크를 무효화합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `invitation_id`: `string` |
| 출력 | `204` 취소 성공 — 본문 없음 |
| 조건 | 인증 필요<br>호출자의 역할이 `OWNER`여야 한다. |
| 주요 오류 | `403` OWNER 권한 없음<br>`404` 워크스페이스 또는 대기 중 초대를 찾을 수 없음 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`DELETE /api/workspaces/{workspace_id}/invitations/{invitation_id}`

#### 2. 목적

대기 중 초대를 취소한다. 취소된 초대의 링크는 이후 `404`가 되며, 같은 주소를 다시 초대하면
새 초대가 만들어진다.

#### 3. Auth 필요 여부

- 필요

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | 워크스페이스 ID |
| path | `invitation_id` | `string` | 예 | 초대 ID |

- 요청 본문 없음

#### 5. Response body

- HTTP `204`: 취소 성공, 본문 없음

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `403` | OWNER 권한 없음 | `WORKSPACE_ACCESS_DENIED` |
| `404` | 워크스페이스를 찾을 수 없거나, 초대가 다른 워크스페이스의 것이거나 이미 수락·취소됨 | `WORKSPACE_NOT_FOUND` / `INVITATION_NOT_FOUND` |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- `OWNER`만 취소할 수 있다.
- 다른 워크스페이스의 초대 ID를 넣어도 `404`다 — 초대 ID로 남의 워크스페이스를 건드릴 수 없다.

#### 9. 예시 요청/응답

```bash
curl -X DELETE "$ACCESS/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/invitations/inv_3c1d8e2f4a5b4c6d8e9f0a1b2c3d4e5f" \
  -H 'Authorization: Bearer <access_token>' \
  -i
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/WorkspaceInvitationController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: revoke`)

[↑ 요약으로 돌아가기](#summary-delete-invitations)

</details>

<a id="summary-get-invitation-preview"></a>
### `GET /api/invitations/{token}`

| 항목 | 내용 |
|---|---|
| 목적 | 초대 링크 화면이 로그인 전에 부르는 미리보기입니다. |
| 입력 | **Path** — `token`: `string` |
| 출력 | `200` 조회 성공 — `InvitationPreviewResponse` |
| 조건 | **인증 불필요.** 토큰을 가진 사람만 도달한다. |
| 주요 오류 | `404` 초대를 찾을 수 없음<br>`409` 이미 수락된 초대<br>`410` 만료된 초대 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`GET /api/invitations/{token}`

#### 2. 목적

메일 링크를 연 화면이 "어느 워크스페이스에, 누가, 어떤 주소로 초대했는지"를 로그인 전에 보여준다.
이 조회가 없으면 화면은 로그인하기 전까지 아무것도 표시할 수 없다.

#### 3. Auth 필요 여부

- 불필요 (`SecurityConfig`에서 `GET /api/invitations/*`만 permitAll)
- 수락(`POST .../accept`)은 인증이 필요하다.
- 토큰이 path에 있으므로 요청 로그에서는 `/api/invitations/***`로 가린다(`LoggableUri`).
  가리지 않으면 로그 열람만으로 유효한 토큰을 얻을 수 있어, DB에 해시만 두는 설계가 상쇄된다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `token` | `string` | 예 | 초대 링크에 실린 토큰 |

- 요청 본문 없음

#### 5. Response body

```json
{
  "workspace_id": "ws_9d47a0e9a6324341b47562553b75f92a",
  "workspace_name": "팀 워크스페이스",
  "email": "member@example.com",
  "role": "MEMBER",
  "invited_by": "홍길동",
  "expires_at": "2026-09-14T04:25:24.371948Z"
}
```

`email`은 화면이 "지금 로그인된 계정이 초대받은 주소와 다릅니다"를 안내하는 데 쓴다.

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `404` | 알 수 없거나 취소된 토큰 | `INVITATION_NOT_FOUND` |
| `409` | 이미 수락된 초대 | `INVITATION_ALREADY_ACCEPTED` |
| `410` | 만료된 초대 | `INVITATION_EXPIRED` |

취소된 초대는 처음부터 없었던 것처럼 `404`다. 취소 사실을 알려주면 토큰 탐색의 단서가 된다.

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 인증 없이 호출할 수 있지만, 유효한 토큰이 있어야만 워크스페이스 이름이 노출된다.
- 응답에 초대자의 이메일은 넣지 않는다(표시 이름만).

#### 9. 예시 요청/응답

```bash
curl "$ACCESS/api/invitations/<token>"
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/InvitationController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: preview`)

[↑ 요약으로 돌아가기](#summary-get-invitation-preview)

</details>

<a id="summary-post-invitation-accept"></a>
### `POST /api/invitations/{token}/accept`

| 항목 | 내용 |
|---|---|
| 목적 | 로그인한 계정을 워크스페이스 멤버로 편입합니다. |
| 입력 | **Path** — `token`: `string` |
| 출력 | `200` 수락 성공 — `InvitationAcceptResponse` |
| 조건 | 인증 필요<br>**로그인한 계정의 이메일이 초대받은 주소와 같아야 한다.** |
| 주요 오류 | `403` 이메일 불일치<br>`404` 초대를 찾을 수 없음<br>`409` 이미 수락된 초대<br>`410` 만료된 초대 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`POST /api/invitations/{token}/accept`

#### 2. 목적

초대를 수락해 멤버가 된다. 이 시점에 **어느 계정이 멤버가 될지**가 확정된다 — 같은 이메일로
일반 계정과 OAuth 계정이 모두 있어도, 로그인해 있는 그 계정만 편입된다.

#### 3. Auth 필요 여부

- 필요
- 미가입자는 링크에서 가입 화면으로 이동한 뒤 가입을 마치고 다시 호출한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `token` | `string` | 예 | 초대 링크에 실린 토큰 |

- 요청 본문 없음

#### 5. Response body

```json
{
  "workspace_id": "ws_9d47a0e9a6324341b47562553b75f92a",
  "workspace_name": "팀 워크스페이스",
  "role": "MEMBER"
}
```

같은 사용자가 링크를 두 번 눌러도 `200`이다(멱등). 이미 멤버인 상태에서의 재호출은
새 멤버십을 만들지 않는다.

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `403` | 로그인한 계정의 이메일이 초대받은 주소와 다름 | `INVITATION_EMAIL_MISMATCH` |
| `404` | 알 수 없거나 취소된 토큰 | `INVITATION_NOT_FOUND` |
| `409` | 다른 계정이 이미 수락한 초대 | `INVITATION_ALREADY_ACCEPTED` |
| `410` | 만료된 초대 | `INVITATION_EXPIRED` |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 로그인 계정의 이메일과 초대 이메일이 일치해야 한다. 이 검증이 링크 유출에 대한 유일한 방어다.
- 수락에 성공하면 인가 projection(`authz:role:{workspaceId}:{userId}`)을 무효화한다.
  수락 전 조회로 캐시된 `NONE` 판정이 남아 있으면 document-svc가 TTL 만료까지 계속 거부한다.
  무효화는 트랜잭션 커밋 이후에 일어난다 — 커밋 전에 지우면 그 사이의 조회가 아직 커밋되지 않은
  `NONE`을 다시 캐시한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/invitations/<token>/accept" \
  -H 'Authorization: Bearer <access_token>'
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/workspace/controller/InvitationController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: accept`)

[↑ 요약으로 돌아가기](#summary-post-invitation-accept)

</details>
