# Auth API

[API 문서](../README.md) / [access-svc](README.md)

가입·이메일 인증·로그인·토큰 API다.

- API 수: 20

## API 목차

| API | 목적 |
|---|---|
| [`POST /api/auth/email-availability`](#summary-post-api-auth-email-availability) | 회원가입 전에 이메일로 신규 가입할 수 있는지 빠르게 확인합니다. 일반 회원가입 계정만 대상으로 확인하므로, OAuth로만 가입된 이메일은 일반 회원가입이 가능해 `available: true`를 반환합니다. |
| [`POST /api/auth/email-verifications`](#summary-post-api-auth-email-verifications) | 회원가입/비밀번호 재설정/이메일 변경을 위한 인증번호를 발급합니다. |
| [`POST /api/auth/email-verifications/{verification_id}/confirm`](#summary-post-api-auth-email-verifications-verification-id-confirm) | 인증번호를 검증하고 1회용 verification_token을 발급합니다. |
| [`POST /api/auth/login`](#summary-post-api-auth-login) | 이메일/비밀번호를 검증하고 access token과 HttpOnly refresh 쿠키를 발급합니다. MFA를 켠 사용자에게는 `mfa_required`를 돌려줍니다. |
| [`POST /api/auth/logout`](#summary-post-api-auth-logout) | HttpOnly refresh 쿠키를 폐기하고 제거합니다. |
| [`GET /api/auth/me`](#summary-get-api-auth-me) | access token으로 인증된 사용자의 프로필을 반환합니다. |
| [`PATCH /api/auth/me`](#summary-patch-api-auth-me) | 인증된 사용자의 표시 이름을 변경합니다. |
| [`PUT /api/auth/me/email`](#summary-put-api-auth-me-email) | 새 이메일로 받은 인증번호 토큰으로 계정 이메일을 바꿉니다. |
| [`GET /api/auth/me/sessions`](#summary-get-api-auth-me-sessions) | 폐기되지 않은 로그인 세션을 반환합니다. |
| [`DELETE /api/auth/me/sessions/{session_id}`](#summary-delete-api-auth-me-sessions-session-id) | 지정한 세션의 refresh token을 폐기합니다. |
| [`PUT /api/auth/me/password`](#summary-put-api-auth-me-password) | 현재 비밀번호를 확인하고 새 비밀번호로 바꿉니다. |
| [`POST /api/auth/login/mfa`](#summary-post-api-auth-login-mfa) | 다단계 인증 코드로 로그인을 마칩니다. |
| [`GET /api/auth/me/mfa`](#summary-get-api-auth-me-mfa) | 다단계 인증 상태를 반환합니다. |
| [`POST /api/auth/me/mfa`](#summary-post-api-auth-me-mfa) | secret과 복구 코드를 발급합니다(등록 1단계). |
| [`POST /api/auth/me/mfa/activate`](#summary-post-api-auth-me-mfa-activate) | 코드를 확인하고 다단계 인증을 켭니다(등록 2단계). |
| [`DELETE /api/auth/me/mfa`](#summary-delete-api-auth-me-mfa) | 코드로 본인을 확인한 뒤 다단계 인증을 해제합니다. |
| [`POST /api/auth/oauth/exchange`](#summary-post-api-auth-oauth-exchange) | OAuth code를 access token과 HttpOnly refresh 쿠키로 교환합니다. |
| [`POST /api/auth/password-reset`](#summary-post-api-auth-password-reset) | verification_token으로 본인 확인 후 비밀번호를 변경하고 기존 세션을 폐기합니다. |
| [`POST /api/auth/refresh`](#summary-post-api-auth-refresh) | HttpOnly refresh 쿠키를 검증하고 access token과 refresh 쿠키를 회전합니다. |
| [`POST /api/auth/signup`](#summary-post-api-auth-signup) | 이메일/비밀번호로 신규 사용자를 생성합니다. |

## 한눈에 보기

<a id="summary-post-api-auth-email-availability"></a>
### `POST /api/auth/email-availability`

| 항목 | 내용 |
|---|---|
| 목적 | 회원가입 전에 이메일로 신규 가입할 수 있는지 빠르게 확인합니다. 일반 회원가입 계정만 대상으로 확인하므로, OAuth로만 가입된 이메일은 일반 회원가입이 가능해 `available: true`를 반환합니다. |
| 입력 | **Body** — `EmailAvailabilityRequest` |
| 출력 | `200` 가입 가능 여부 — `EmailAvailabilityResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다.<br>기존 인증번호 요청 API도 가입 이메일 중복을 `409`로 노출하므로 동일한 공개 범위를 유지한다.<br>그 밖의 조건은 상세 권한 규칙 참고 |
| 주요 오류 | `400` 잘못된 요청 — `ErrorResponse`<br>`429` 요청 횟수 제한 초과 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-email-availability"></a>
### `POST /api/auth/email-availability` 상세

#### 1. Method + Path

`POST /api/auth/email-availability`

#### 2. 목적

회원가입 전에 이메일로 신규 가입할 수 있는지 빠르게 확인합니다. 일반 회원가입 계정만 대상으로 확인하므로, OAuth로만 가입된 이메일은 일반 회원가입이 가능해 `available: true`를 반환합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음
- Content-Type: `application/json` (`EmailAvailabilityRequest`)

```json
{
  "email": "user@example.com"
}
```

#### 5. Response body

- HTTP `200`: 가입 가능 여부
- Content-Type: `*/*` (`EmailAvailabilityResponse`)

```json
{
  "available": true
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 이메일 형식 | `ErrorResponse` |
| `429` | IP 또는 이메일 기준 요청 횟수 제한 초과 | `ErrorResponse` |

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.
- 기존 인증번호 요청 API도 가입 이메일 중복을 `409`로 노출하므로 동일한 공개 범위를 유지한다.
- 계정 열거 비용을 제한하기 위해 Redis에서 IP당 30회/분, 이메일당 5회/분으로 호출을 제한한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/email-availability" \
  -H 'Content-Type: application/json' \
  --data '{"email":"user@example.com"}'
```

```json
{
  "available": true
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: checkEmailAvailability`)

[↑ 요약으로 돌아가기](#summary-post-api-auth-email-availability)

</details>

<a id="summary-post-api-auth-email-verifications"></a>
### `POST /api/auth/email-verifications`

| 항목 | 내용 |
|---|---|
| 목적 | 회원가입/비밀번호 재설정을 위한 인증번호를 발급합니다. |
| 입력 | **Body** — `EmailVerificationRequest` |
| 출력 | `202` 인증번호 발급 — `EmailVerificationResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `400` 잘못된 요청 — `ErrorResponse`<br>`409` 이미 가입된 이메일(purpose=signup) — `ErrorResponse`<br>`429` 재요청 제한 초과 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-email-verifications"></a>
### `POST /api/auth/email-verifications` 상세

#### 1. Method + Path

`POST /api/auth/email-verifications`

#### 2. 목적

회원가입/비밀번호 재설정을 위한 인증번호를 발급합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`EmailVerificationRequest`)

```json
{
  "email": "user@example.com",
  "purpose": "signup"
}
```

#### 5. Response body

- HTTP `202`: 인증번호 발급
- Content-Type: `*/*` (`EmailVerificationResponse`)

```json
{
  "expires_in": 300,
  "retry_after": 60,
  "verification_id": "ev_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 | `ErrorResponse` |
| `409` | 이미 가입된 이메일(purpose=signup) | `ErrorResponse` |
| `429` | 재요청 제한 초과 | `ErrorResponse` |

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

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/email-verifications" \
  -H 'Content-Type: application/json' \
  --data '{"email":"user@example.com","purpose":"signup"}'
```

```json
{
  "expires_in": 300,
  "retry_after": 60,
  "verification_id": "ev_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: requestEmailVerification`)

[↑ 요약으로 돌아가기](#summary-post-api-auth-email-verifications)

</details>

<a id="summary-post-api-auth-email-verifications-verification-id-confirm"></a>
### `POST /api/auth/email-verifications/{verification_id}/confirm`

| 항목 | 내용 |
|---|---|
| 목적 | 인증번호를 검증하고 1회용 verification_token을 발급합니다. |
| 입력 | **Path** — `verification_id`: `string`<br>**Body** — `VerificationConfirmRequest` |
| 출력 | `200` 검증 성공 — `VerificationConfirmResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `400` 인증번호 불일치·만료·시도 초과 — `ErrorResponse`<br>`404` 인증 요청을 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-email-verifications-verification-id-confirm"></a>
### `POST /api/auth/email-verifications/{verification_id}/confirm` 상세

#### 1. Method + Path

`POST /api/auth/email-verifications/{verification_id}/confirm`

#### 2. 목적

인증번호를 검증하고 1회용 verification_token을 발급합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `verification_id` | `string` | 예 | - |

- Content-Type: `application/json` (`VerificationConfirmRequest`)

```json
{
  "code": "042173"
}
```

#### 5. Response body

- HTTP `200`: 검증 성공
- Content-Type: `*/*` (`VerificationConfirmResponse`)

```json
{
  "expires_in": 600,
  "verification_token": "EXAMPLE-verification-token-not-real-0000000"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 인증번호 불일치·만료·시도 초과 | `ErrorResponse` |
| `404` | 인증 요청을 찾을 수 없음 | `ErrorResponse` |

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

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/email-verifications/<value>/confirm" \
  -H 'Content-Type: application/json' \
  --data '{"code":"042173"}'
```

```json
{
  "expires_in": 600,
  "verification_token": "EXAMPLE-verification-token-not-real-0000000"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: confirmEmailVerification`)

[↑ 요약으로 돌아가기](#summary-post-api-auth-email-verifications-verification-id-confirm)

</details>

<a id="summary-post-api-auth-login"></a>
### `POST /api/auth/login`

| 항목 | 내용 |
|---|---|
| 목적 | 이메일/비밀번호를 검증하고 access token과 HttpOnly refresh 쿠키를 발급합니다. |
| 입력 | **Body** — `LoginRequest` |
| 출력 | `200` 로그인 성공 — `LoginResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `401` 이메일 또는 비밀번호 불일치 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-login"></a>
### `POST /api/auth/login` 상세

#### 1. Method + Path

`POST /api/auth/login`

#### 2. 목적

이메일/비밀번호를 검증하고 access token과 HttpOnly refresh 쿠키를 발급합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`LoginRequest`)

```json
{
  "email": "user@example.com",
  "password": "stringst"
}
```

#### 5. Response body

- HTTP `200`: 로그인 성공
- Content-Type: `*/*` (`LoginResponse`)

```json
{
  "access_token": "string",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

- 응답의 `Set-Cookie`가 `fruition_refresh_token`을 `HttpOnly; SameSite=Strict`로 저장한다.

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `401` | 이메일 또는 비밀번호 불일치 | `ErrorResponse` |

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

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/login" \
  -H 'Content-Type: application/json' \
  -c cookies.txt \
  --data '{"email":"user@example.com","password":"stringst"}'
```

```json
{
  "access_token": "string",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: login`)

[↑ 요약으로 돌아가기](#summary-post-api-auth-login)

</details>

<a id="summary-post-api-auth-logout"></a>
### `POST /api/auth/logout`

| 항목 | 내용 |
|---|---|
| 목적 | HttpOnly refresh 쿠키를 폐기하고 제거합니다. |
| 입력 | **Cookie** — `fruition_refresh_token`(선택) |
| 출력 | `204` 로그아웃 성공 |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | 없음 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-logout"></a>
### `POST /api/auth/logout` 상세

#### 1. Method + Path

`POST /api/auth/logout`

#### 2. 목적

HttpOnly refresh 쿠키를 폐기하고 제거합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Body: 없음
- Cookie: `fruition_refresh_token`(선택). 없거나 이미 만료돼도 로그아웃은 멱등하게 성공한다.

#### 5. Response body

- HTTP `204`: 로그아웃 성공
- Body: 없음

#### 6. Error response

- 없음

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/logout" \
  -b cookies.txt
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: logout`)

[↑ 요약으로 돌아가기](#summary-post-api-auth-logout)

</details>

<a id="summary-get-api-auth-me"></a>
### `GET /api/auth/me`

| 항목 | 내용 |
|---|---|
| 목적 | access token으로 인증된 사용자의 프로필을 반환합니다. |
| 입력 | 없음 |
| 출력 | `200` 조회 성공 — `MeResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다. |
| 주요 오류 | `401` 인증되지 않음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-auth-me"></a>
### `GET /api/auth/me` 상세

#### 1. Method + Path

`GET /api/auth/me`

#### 2. 목적

access token으로 인증된 사용자의 프로필을 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

- 없음

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`MeResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "display_name": "표시 이름",
  "email": "user@example.com",
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `401` | 인증되지 않음 | 없음(본문 없이 상태 코드만) |

인증 필터가 막는 401은 `HttpStatusEntryPoint`가 상태 코드만 내보내므로 본문이 없다. `error.code`로 분기할 수 없으니 상태 코드로 판정한다. 로그인 실패처럼 컨트롤러까지 도달한 뒤 발생하는 401은 `ErrorResponse`를 반환한다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$ACCESS/api/auth/me" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "display_name": "표시 이름",
  "email": "user@example.com",
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: me`)

[↑ 요약으로 돌아가기](#summary-get-api-auth-me)

</details>

<a id="summary-patch-api-auth-me"></a>
### `PATCH /api/auth/me`

| 항목 | 내용 |
|---|---|
| 목적 | 인증된 사용자의 표시 이름을 변경합니다. |
| 입력 | **Body** — `DisplayNameUpdateRequest` |
| 출력 | `200` 변경 성공 — `MeResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다. |
| 주요 오류 | `400` 잘못된 요청 — `ErrorResponse`<br>`401` 인증되지 않음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`PATCH /api/auth/me`

#### 2. 목적

인증된 사용자의 표시 이름을 변경한다. 이메일과 provider는 이 API로 바꿀 수 없다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `display_name` | `string` | 예 | 새 표시 이름(255자 이하). 서버가 앞뒤 공백을 제거한다 |

```json
{
  "display_name": "새 이름"
}
```

#### 5. Response body

- HTTP `200`: 변경 성공 — `MeResponse`

```json
{
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
  "email": "user@example.com",
  "display_name": "새 이름",
  "created_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `400` | `display_name`이 비었거나 255자를 넘음 | `INVALID_REQUEST` |
| `401` | access token이 없거나 유효하지 않음 | — |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 토큰의 사용자 본인만 대상이다. 경로에 사용자 ID를 받지 않으므로 남의 프로필은 바꿀 수 없다.

#### 9. 예시 요청/응답

```bash
curl -X PATCH "$ACCESS/api/auth/me" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"display_name":"새 이름"}'
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: updateDisplayName`)

[↑ 요약으로 돌아가기](#summary-patch-api-auth-me)

</details>

<a id="summary-put-api-auth-me-email"></a>
### `PUT /api/auth/me/email`

| 항목 | 내용 |
|---|---|
| 목적 | 새 이메일로 받은 인증번호 토큰으로 본인 확인 후 계정 이메일을 바꿉니다. |
| 입력 | **Body** — `EmailChangeRequest`<br>**Cookie** — `fruition_refresh_token`(선택) |
| 출력 | `200` 변경 성공 — `MeResponse` |
| 조건 | 인증 필요<br>`purpose=email_change`로 **새 주소에** 발급받은 토큰이어야 한다. |
| 주요 오류 | `400` 유효하지 않은 `verification_token` — `ErrorResponse`<br>`401` 인증되지 않음 — `ErrorResponse`<br>`409` 같은 provider에 이미 그 이메일 계정이 있음 — `ErrorResponse` |
<a id="summary-get-api-auth-me-sessions"></a>
### `GET /api/auth/me/sessions`

| 항목 | 내용 |
|---|---|
| 목적 | 폐기되지 않은 로그인 세션을 최근 로그인 순으로 반환합니다. |
| 입력 | **Cookie** — `fruition_refresh_token`(선택) |
| 출력 | `200` 조회 성공 — `SessionListResponse` |
| 조건 | 인증 필요 |
| 주요 오류 | `401` 인증되지 않음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`PUT /api/auth/me/email`

#### 2. 목적

계정 이메일을 바꾼다. 인증번호는 **바꾸려는 새 주소로** 발송해, 그 메일함을 통제하는지 확인한다.

흐름은 두 단계다.

1. `POST /api/auth/email-verifications` — `{"email": "<새 주소>", "purpose": "email_change"}`
2. `POST /api/auth/email-verifications/{verification_id}/confirm` — 코드 검증, `verification_token` 발급
3. `PUT /api/auth/me/email` — 토큰으로 확정
`GET /api/auth/me/sessions`

#### 2. 목적

로그인된 기기 목록이다. refresh token 하나가 세션 하나에 대응한다.

#### 3. Auth 필요 여부

- 필요
- refresh 쿠키(`fruition_refresh_token`)를 함께 읽어 현재 세션을 식별한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `new_email` | `string` | 예 | 바꿀 새 이메일(255자 이하). 서버가 trim·소문자화한다 |
| body | `verification_token` | `string` | 예 | `purpose=email_change`로 받은 1회용 토큰 |
| cookie | `fruition_refresh_token` | `string` | 아니오 | 현재 세션의 refresh token. 없으면 모든 세션이 폐기된다 |

```json
{
  "new_email": "new@example.com",
  "verification_token": "EXAMPLE-verification-token-not-real-0000000"
}
```

#### 5. Response body

- HTTP `200`: 변경 성공 — `MeResponse`

```json
{
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
  "email": "new@example.com",
  "display_name": "표시 이름",
  "created_at": "2026-08-13T04:25:24.371948Z"
}
```

- refresh 쿠키를 함께 읽어 지금 요청을 보낸 세션에 `current: true`를 단다. 쿠키가 없으면 전부 `false`다.

#### 4. Request body

- 요청 본문 없음

#### 5. Response body

```json
{
  "sessions": [
    {
      "session_id": 42,
      "user_agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
      "current": true,
      "created_at": "2026-09-07T04:25:24.371948Z",
      "expires_at": "2026-09-21T04:25:24.371948Z"
    }
  ]
}
```

`user_agent`는 로그인 시점의 원문이다. 이 컬럼이 생기기 전에 발급된 세션은 `null`이라
화면에서 "알 수 없는 기기"로 보여야 한다.

`created_at`이 사실상 마지막 사용 시각이다. refresh는 토큰을 회전시켜 새 세션 행을 만들고
옛 행을 폐기하므로, 활성 세션의 `created_at`은 마지막 갱신 시점이다.

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `400` | `new_email` 형식 오류 등 | `INVALID_REQUEST` |
| `400` | 토큰이 없거나 만료·소비됐거나 `new_email`과 다른 주소로 발급됨 | `INVALID_VERIFICATION_TOKEN` |
| `401` | access token이 없거나 유효하지 않음 | — |
| `409` | 같은 provider에 이미 그 이메일 계정이 있음 | `DUPLICATE_EMAIL` |
| `401` | access token이 없거나 유효하지 않음 | — |

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 정렬: `created_at` 내림차순 고정
- 폐기된 세션은 반환하지 않는다

#### 8. 권한 규칙

- 토큰의 사용자 본인 세션만 반환한다.

#### 9. 예시 요청/응답

```bash
curl "$ACCESS/api/auth/me/sessions" \
  -H 'Authorization: Bearer <access_token>' \
  -b 'fruition_refresh_token=<refresh_token>'
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: sessions`)

[↑ 요약으로 돌아가기](#summary-get-api-auth-me-sessions)

</details>

<a id="summary-delete-api-auth-me-sessions-session-id"></a>
### `DELETE /api/auth/me/sessions/{session_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 지정한 세션의 refresh token을 폐기합니다. |
| 입력 | **Path** — `session_id`: `integer` |
| 출력 | `204` 폐기 성공 — 본문 없음 |
| 조건 | 인증 필요<br>본인 세션이어야 한다. |
| 주요 오류 | `401` 인증되지 않음 — `ErrorResponse`<br>`404` 세션을 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`DELETE /api/auth/me/sessions/{session_id}`

#### 2. 목적

특정 기기를 로그아웃시킨다. 현재 세션을 지정하면 스스로 로그아웃하는 것이라 허용한다 —
다만 refresh 쿠키는 지워지지 않으므로, 현재 세션을 끊을 때는
[`POST /api/auth/logout`](#summary-post-api-auth-logout)을 쓰는 편이 낫다.

#### 3. Auth 필요 여부

- 필요

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `session_id` | `integer` | 예 | `GET /api/auth/me/sessions`가 준 세션 ID |

- 요청 본문 없음

#### 5. Response body

- HTTP `204`: 폐기 성공, 본문 없음

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | access token이 없거나 유효하지 않음 | — |
| `404` | 세션이 없거나, 남의 세션이거나, 이미 폐기됨 | `SESSION_NOT_FOUND` |

셋을 모두 `404`로 통일한다. 남의 세션을 `403`으로 구분하면 세션 ID 존재 여부가 드러난다.

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 토큰의 사용자 본인만 대상이다.
- 계정은 `(email, provider)`로 유일하다. **같은 이메일이라도 provider가 다르면 충돌이 아니다** —
  일반 가입 계정이 이미 쓰는 주소로 구글 계정의 이메일을 바꾸는 건 허용된다.
- OAuth 계정도 바꿀 수 있다. OAuth 로그인은 `(provider, provider_user_id)`로 사용자를 찾으므로
  이메일이 바뀌어도 로그인이 끊기지 않고, 다음 로그인이 provider 이메일로 되돌리지도 않는다.
- 성공하면 현재 세션을 제외한 refresh token을 폐기한다. 비밀번호 변경과 같은 기준이다.
- 인증번호 발송(`POST /api/auth/email-verifications`)은 인증이 필요 없는 엔드포인트라,
  중복 확인은 여기 확정 시점에 한다. 발송 단계에서는 계정 존재 여부를 노출하지 않는다.
- 본인 세션만 폐기할 수 있다.
- 폐기된 세션의 refresh token으로는 더 이상 access token을 갱신할 수 없다. 이미 발급된
  access token은 만료(기본 900초)까지 유효하다.

#### 9. 예시 요청/응답

```bash
curl -X PUT "$ACCESS/api/auth/me/email" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  -b 'fruition_refresh_token=<refresh_token>' \
  --data '{"new_email":"new@example.com","verification_token":"<token>"}'
curl -X DELETE "$ACCESS/api/auth/me/sessions/42" \
  -H 'Authorization: Bearer <access_token>' \
  -i
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: changeEmail`)

[↑ 요약으로 돌아가기](#summary-put-api-auth-me-email)
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: revokeSession`)

[↑ 요약으로 돌아가기](#summary-delete-api-auth-me-sessions-session-id)

</details>

<a id="summary-put-api-auth-me-password"></a>
### `PUT /api/auth/me/password`

| 항목 | 내용 |
|---|---|
| 목적 | 현재 비밀번호를 확인하고 새 비밀번호로 바꿉니다. 성공하면 현재 세션을 제외한 refresh token이 폐기됩니다. |
| 입력 | **Body** — `PasswordChangeRequest`<br>**Cookie** — `fruition_refresh_token`(선택) |
| 출력 | `204` 변경 성공 — 본문 없음 |
| 조건 | 인증 필요<br>비밀번호를 쓰는 계정(`provider=local`)이어야 한다. |
| 주요 오류 | `400` 비밀번호를 쓰지 않는 계정 — `ErrorResponse`<br>`401` 인증되지 않았거나 현재 비밀번호가 다름 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`PUT /api/auth/me/password`

#### 2. 목적

로그인 상태에서 비밀번호를 바꾼다. 비로그인 흐름인
[`POST /api/auth/password-reset`](#summary-post-api-auth-password-reset)과 달리 인증번호가 아니라
**현재 비밀번호**로 본인을 확인한다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.
- refresh 쿠키(`fruition_refresh_token`)를 함께 읽어 현재 세션을 식별한다. 쿠키 path가
  `/api/auth`라 이 경로에는 자동으로 실린다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `current_password` | `string` | 예 | 현재 비밀번호 |
| body | `new_password` | `string` | 예 | 새 비밀번호(8~72자) |
| cookie | `fruition_refresh_token` | `string` | 아니오 | 현재 세션의 refresh token. 없으면 모든 세션이 폐기된다 |

```json
{
  "current_password": "password1234",
  "new_password": "newPassword1234"
}
```

#### 5. Response body

- HTTP `204`: 변경 성공, 본문 없음

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `400` | `new_password` 길이 위반 등 | `INVALID_REQUEST` |
| `400` | OAuth로만 가입해 비밀번호가 없는 계정 | `PASSWORD_LOGIN_UNAVAILABLE` |
| `401` | access token이 없거나 유효하지 않음 | — |
| `401` | 현재 비밀번호가 다름 | `INVALID_CREDENTIALS` |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 토큰의 사용자 본인만 대상이다.
- `password_hash`가 없는 계정(OAuth 전용)은 "현재 비밀번호"가 성립하지 않아 `400`이다.
- 성공하면 **현재 세션을 제외한** refresh token을 전부 폐기한다. 비밀번호가 샜을 때 다른 기기의
  세션을 끊으면서, 방금 현재 비밀번호로 본인 확인을 마친 사용자는 로그아웃시키지 않기 위해서다.
  refresh 쿠키가 없으면 지킬 세션을 특정할 수 없어 전부 폐기한다.
- 비로그인 `password-reset`이 세션을 **전부** 폐기하는 것과 다르다. 그쪽은 요청자가 메일함만
  통제하고 있어 지켜줄 현재 세션이 없다.

#### 9. 예시 요청/응답

```bash
curl -X PUT "$ACCESS/api/auth/me/password" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  -b 'fruition_refresh_token=<refresh_token>' \
  --data '{"current_password":"password1234","new_password":"newPassword1234"}' \
  -i
```

```
HTTP/1.1 204 No Content
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: changePassword`)

[↑ 요약으로 돌아가기](#summary-put-api-auth-me-password)

</details>

<a id="summary-post-api-auth-login-mfa"></a>
### `POST /api/auth/login/mfa`

| 항목 | 내용 |
|---|---|
| 목적 | 다단계 인증 코드로 로그인을 마칩니다. |
| 입력 | **Body** — `MfaLoginRequest` |
| 출력 | `200` 로그인 성공 — `LoginResponse` |
| 조건 | 인증 불필요. `mfa_token`이 신원을 대신한다. |
| 주요 오류 | `400` mfa_token 만료·소비됨<br>`401` 코드가 올바르지 않음 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`POST /api/auth/login/mfa`

#### 2. 목적

`POST /api/auth/login`이 `mfa_required: true`를 돌려줬을 때 두 번째 단계를 마친다.

#### 3. Auth 필요 여부

- 불필요. 아직 로그인 전이며 `mfa_token`이 "비밀번호는 통과했다"는 증거다.
- `mfa_token`은 1회용이고 기본 300초 뒤 만료된다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `mfa_token` | `string` | 예 | 로그인 1단계 응답의 토큰 |
| body | `code` | `string` | 예 | 인증 앱의 6자리 코드 **또는** 복구 코드 |

```json
{
  "mfa_token": "EXAMPLE-mfa-token-not-real",
  "code": "482917"
}
```

복구 코드도 같은 자리에 넣는다. 별도 엔드포인트를 두지 않는다.

#### 5. Response body

`POST /api/auth/login`의 성공 응답과 같다. refresh 토큰은 HttpOnly 쿠키로 나간다.

```json
{
  "access_token": "<JWT>",
  "token_type": "Bearer",
  "expires_in": 900
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `400` | `mfa_token`이 없거나 만료·소비됨 | `INVALID_MFA_CHALLENGE` |
| `401` | 코드가 올바르지 않음 | `INVALID_MFA_CODE` |

**코드가 틀려도 `mfa_token`은 살아 있다.** 오타 한 번에 비밀번호부터 다시 넣게 만들지 않는다.
TOTP인지 복구 코드인지는 구분해 알려주지 않는다.

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 같은 30초 창의 TOTP 코드는 두 번 쓸 수 없다. 가로챈 코드의 재사용을 막는다.
- 시계 오차를 감안해 앞뒤 한 창(±30초)까지 받아준다.
- 복구 코드는 한 번 쓰면 소멸한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/login/mfa" \
  -H 'Content-Type: application/json' \
  --data '{"mfa_token":"<token>","code":"482917"}'
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: loginMfa`)

[↑ 요약으로 돌아가기](#summary-post-api-auth-login-mfa)

</details>

<a id="summary-get-api-auth-me-mfa"></a>
### `GET /api/auth/me/mfa`

| 항목 | 내용 |
|---|---|
| 목적 | 다단계 인증 상태를 반환합니다. |
| 입력 | 없음 |
| 출력 | `200` 조회 성공 — `MfaStatusResponse` |
| 조건 | 인증 필요 |
| 주요 오류 | `401` 인증되지 않음 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`GET /api/auth/me/mfa`

#### 2. 목적

설정 화면이 "2단계 인증: 켜짐/꺼짐"과 남은 복구 코드 수를 보여주는 데 쓴다.

#### 3. Auth 필요 여부

- 필요

#### 4. Request body

- 요청 본문 없음

#### 5. Response body

```json
{
  "enabled": true,
  "activated_at": "2026-09-07T21:40:11.204813Z",
  "remaining_recovery_codes": 9
}
```

`enabled`는 **활성화까지 끝난 경우에만** true다. 등록만 하고 코드 검증을 통과하지 않았으면 false다 —
그 상태는 로그인을 막지 않으므로 화면에도 "켜짐"으로 보이면 안 된다.

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | access token이 없거나 유효하지 않음 | — |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 본인 상태만 조회한다.

#### 9. 예시 요청/응답

```bash
curl "$ACCESS/api/auth/me/mfa" -H 'Authorization: Bearer <access_token>'
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: mfaStatus`)

[↑ 요약으로 돌아가기](#summary-get-api-auth-me-mfa)

</details>

<a id="summary-post-api-auth-me-mfa"></a>
### `POST /api/auth/me/mfa`

| 항목 | 내용 |
|---|---|
| 목적 | secret과 복구 코드를 발급합니다(등록 1단계). |
| 입력 | 없음 |
| 출력 | `200` 발급 성공 — `MfaRegistrationResponse` |
| 조건 | 인증 필요 |
| 주요 오류 | `401` 인증되지 않음<br>`409` 이미 켜져 있음 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`POST /api/auth/me/mfa`

#### 2. 목적

QR로 보여줄 secret과 복구 코드를 만든다. **이 단계로는 켜지지 않는다** —
로그인은 그대로 비밀번호만으로 통과한다.

#### 3. Auth 필요 여부

- 필요

#### 4. Request body

- 요청 본문 없음

#### 5. Response body

```json
{
  "secret": "JBSWY3DPEHPK3PXP",
  "otpauth_uri": "otpauth://totp/Fruition%3Auser%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Fruition",
  "recovery_codes": ["A3F2K9QZ", "..."]
}
```

`recovery_codes`는 **이 응답에서만** 볼 수 있다. 서버에는 SHA-256 해시만 남아 다시 조회할 수 없다.
`otpauth_uri`를 QR로 만들면 인증 앱이 바로 읽는다. `secret`은 QR을 못 쓸 때 수동 입력용이다.

이미 등록만 해둔 상태에서 다시 호출하면 새 secret으로 갈아끼우고 복구 코드도 새로 발급한다 —
옛 복구 코드는 옛 secret과 짝이라 함께 버린다.

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | access token이 없거나 유효하지 않음 | — |
| `409` | 이미 활성화됨. 해제 후 다시 등록해야 한다 | `MFA_ALREADY_ENABLED` |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 본인 계정에만 등록한다.
- secret은 AES-GCM으로 암호화해 저장한다. 검증에 원문이 필요해 해시로 둘 수 없기 때문이며,
  평문으로 두면 DB 유출 시 MFA가 통째로 무력화된다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/me/mfa" -H 'Authorization: Bearer <access_token>'
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: registerMfa`)

[↑ 요약으로 돌아가기](#summary-post-api-auth-me-mfa)

</details>

<a id="summary-post-api-auth-me-mfa-activate"></a>
### `POST /api/auth/me/mfa/activate`

| 항목 | 내용 |
|---|---|
| 목적 | 코드를 확인하고 다단계 인증을 켭니다(등록 2단계). |
| 입력 | **Body** — `MfaCodeRequest` |
| 출력 | `204` 활성화 성공 — 본문 없음 |
| 조건 | 인증 필요 |
| 주요 오류 | `401` 코드가 올바르지 않음<br>`404` 등록된 설정 없음<br>`409` 이미 켜져 있음 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`POST /api/auth/me/mfa/activate`

#### 2. 목적

인증 앱이 만든 코드가 맞는지 확인하고 실제로 켠다. **이 단계를 통과해야 로그인에 코드가 요구된다.**

두 단계로 나눈 이유: 등록 즉시 켜버리면 QR을 잘못 스캔했거나 앱 시계가 틀어진 사용자가
다음 로그인에서 자기 계정에 못 들어간다.

#### 3. Auth 필요 여부

- 필요

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `code` | `string` | 예 | 인증 앱의 6자리 코드 |

```json
{
  "code": "482917"
}
```

#### 5. Response body

- HTTP `204`: 활성화 성공, 본문 없음

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | 코드가 올바르지 않음 | `INVALID_MFA_CODE` |
| `404` | `POST /api/auth/me/mfa`를 먼저 호출하지 않음 | `MFA_NOT_ENABLED` |
| `409` | 이미 활성화됨 | `MFA_ALREADY_ENABLED` |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 활성화에 쓴 시간 창은 소비 처리한다. 같은 코드로 바로 로그인할 수 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/me/mfa/activate" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"code":"482917"}'
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: activateMfa`)

[↑ 요약으로 돌아가기](#summary-post-api-auth-me-mfa-activate)

</details>

<a id="summary-delete-api-auth-me-mfa"></a>
### `DELETE /api/auth/me/mfa`

| 항목 | 내용 |
|---|---|
| 목적 | 코드로 본인을 확인한 뒤 다단계 인증을 해제합니다. |
| 입력 | **Body** — `MfaCodeRequest` |
| 출력 | `204` 해제 성공 — 본문 없음 |
| 조건 | 인증 필요 |
| 주요 오류 | `401` 코드가 올바르지 않음<br>`404` 켜져 있지 않음 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`DELETE /api/auth/me/mfa`

#### 2. 목적

다단계 인증을 끈다. secret과 남은 복구 코드가 모두 지워진다.

#### 3. Auth 필요 여부

- 필요
- access token만으로는 부족하다. 토큰을 탈취한 쪽이 MFA를 그냥 꺼버릴 수 있기 때문에
  코드를 한 번 더 요구한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `code` | `string` | 예 | 인증 앱의 6자리 코드 **또는** 복구 코드 |

```json
{
  "code": "482917"
}
```

복구 코드도 받는 이유: 기기를 잃은 사용자가 MFA를 끄고 다시 등록할 수 있어야 한다.
OAuth 전용 계정은 비밀번호가 없으므로 코드가 유일한 확인 수단이다.

#### 5. Response body

- HTTP `204`: 해제 성공, 본문 없음

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | 코드가 올바르지 않음 | `INVALID_MFA_CODE` |
| `404` | 켜져 있지 않음 | `MFA_NOT_ENABLED` |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 본인 계정만 해제한다.
- 해제 후에는 `POST /api/auth/login`이 다시 토큰을 바로 돌려준다.

#### 9. 예시 요청/응답

```bash
curl -X DELETE "$ACCESS/api/auth/me/mfa" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"code":"482917"}'
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: disableMfa`)

[↑ 요약으로 돌아가기](#summary-delete-api-auth-me-mfa)

</details>

<a id="summary-post-api-auth-oauth-exchange"></a>
### `POST /api/auth/oauth/exchange`

| 항목 | 내용 |
|---|---|
| 목적 | OAuth 로그인 성공 후 발급된 1회용 code를 access token과 HttpOnly refresh 쿠키로 교환합니다. |
| 입력 | **Body** — `OAuthExchangeRequest` |
| 출력 | `200` 교환 성공 — `LoginResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `401` 유효하지 않거나 만료된 code — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-oauth-exchange"></a>
### `POST /api/auth/oauth/exchange` 상세

#### 1. Method + Path

`POST /api/auth/oauth/exchange`

#### 2. 목적

OAuth 로그인 성공 후 발급된 1회용 code를 access token과 HttpOnly refresh 쿠키로 교환합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`OAuthExchangeRequest`)

```json
{
  "code": "string"
}
```

#### 5. Response body

- HTTP `200`: 교환 성공
- Content-Type: `*/*` (`LoginResponse`)

```json
{
  "access_token": "string",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

- 응답의 `Set-Cookie`가 `fruition_refresh_token`을 `HttpOnly; SameSite=Strict`로 저장한다.

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `401` | 유효하지 않거나 만료된 code | `ErrorResponse` |

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

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/oauth/exchange" \
  -H 'Content-Type: application/json' \
  -c cookies.txt \
  --data '{"code":"<value>"}'
```

```json
{
  "access_token": "string",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: exchangeOAuthCode`)

[↑ 요약으로 돌아가기](#summary-post-api-auth-oauth-exchange)

</details>

<a id="summary-post-api-auth-password-reset"></a>
### `POST /api/auth/password-reset`

| 항목 | 내용 |
|---|---|
| 목적 | verification_token으로 본인 확인 후 비밀번호를 변경하고 기존 세션을 폐기합니다. |
| 입력 | **Body** — `PasswordResetRequest` |
| 출력 | `204` 재설정 성공 |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `400` 잘못된 요청 또는 유효하지 않은 토큰(`INVALID_VERIFICATION_TOKEN`), OAuth로만 가입된 이메일(`PASSWORD_LOGIN_UNAVAILABLE`) — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-password-reset"></a>
### `POST /api/auth/password-reset` 상세

#### 1. Method + Path

`POST /api/auth/password-reset`

#### 2. 목적

verification_token으로 본인 확인 후 비밀번호를 변경하고 기존 세션을 폐기합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`PasswordResetRequest`)

```json
{
  "email": "user@example.com",
  "new_password": "password1234",
  "verification_token": "EXAMPLE-verification-token-not-real-0000000"
}
```

#### 5. Response body

- HTTP `204`: 재설정 성공
- Body: 없음

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 또는 유효하지 않은 토큰 | `ErrorResponse` |

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

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/password-reset" \
  -H 'Content-Type: application/json' \
  --data '{"email":"user@example.com","new_password":"password1234","verification_token":"EXAMPLE-verification-token-not-real-0000000"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: resetPassword`)

[↑ 요약으로 돌아가기](#summary-post-api-auth-password-reset)

</details>

<a id="summary-post-api-auth-refresh"></a>
### `POST /api/auth/refresh`

| 항목 | 내용 |
|---|---|
| 목적 | HttpOnly refresh 쿠키를 검증하고 access token과 refresh 쿠키를 회전합니다. |
| 입력 | **Cookie** — `fruition_refresh_token` |
| 출력 | `200` 재발급 성공 — `LoginResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `401` 유효하지 않거나 만료된 refresh token — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-refresh"></a>
### `POST /api/auth/refresh` 상세

#### 1. Method + Path

`POST /api/auth/refresh`

#### 2. 목적

HttpOnly refresh 쿠키를 검증하고 access token과 refresh 쿠키를 회전합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Body: 없음
- Cookie: `fruition_refresh_token`(필수)

#### 5. Response body

- HTTP `200`: 재발급 성공
- Content-Type: `*/*` (`LoginResponse`)

```json
{
  "access_token": "string",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

- 응답의 `Set-Cookie`가 기존 refresh 쿠키를 회전한 값으로 교체한다.

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `401` | 유효하지 않거나 만료된 refresh token | `ErrorResponse` |

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

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/refresh" \
  -b cookies.txt -c cookies.txt
```

```json
{
  "access_token": "string",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: refresh`)

[↑ 요약으로 돌아가기](#summary-post-api-auth-refresh)

</details>

<a id="summary-post-api-auth-signup"></a>
### `POST /api/auth/signup`

| 항목 | 내용 |
|---|---|
| 목적 | 이메일/비밀번호로 신규 사용자를 생성합니다. |
| 입력 | **Body** — `SignupRequest` |
| 출력 | `201` 회원가입 성공 — `SignupResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `400` 잘못된 요청 — `ErrorResponse`<br>`409` 이미 가입된 이메일 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-signup"></a>
### `POST /api/auth/signup` 상세

#### 1. Method + Path

`POST /api/auth/signup`

#### 2. 목적

이메일/비밀번호로 신규 사용자를 생성합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`SignupRequest`)

```json
{
  "display_name": "표시 이름",
  "email": "user@example.com",
  "password": "password1234",
  "verification_token": "EXAMPLE-verification-token-not-real-0000000"
}
```

#### 5. Response body

- HTTP `201`: 회원가입 성공
- Content-Type: `*/*` (`SignupResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "display_name": "표시 이름",
  "email": "user@example.com",
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 | `ErrorResponse` |
| `409` | 이미 가입된 이메일 | `ErrorResponse` |

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

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/signup" \
  -H 'Content-Type: application/json' \
  --data '{"display_name":"표시 이름","email":"user@example.com","password":"password1234","verification_token":"EXAMPLE-verification-token-not-real-0000000"}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "display_name": "표시 이름",
  "email": "user@example.com",
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: signup`)

[↑ 요약으로 돌아가기](#summary-post-api-auth-signup)

</details>
