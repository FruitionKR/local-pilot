# Auth API

[API 문서](../README.md) / [access-svc](README.md)

가입·이메일 인증·로그인·토큰 API다.

- API 수: 10

## API 목차

| API | 목적 |
|---|---|
| [`POST /api/auth/email-availability`](#summary-post-api-auth-email-availability) | 회원가입 전에 이메일로 신규 가입할 수 있는지 빠르게 확인합니다. OAuth 계정을 포함해 이미 등록된 이메일은 `available: false`를 반환합니다. |
| [`POST /api/auth/email-verifications`](#summary-post-api-auth-email-verifications) | 회원가입/비밀번호 재설정을 위한 인증번호를 발급합니다. |
| [`POST /api/auth/email-verifications/{verification_id}/confirm`](#summary-post-api-auth-email-verifications-verification-id-confirm) | 인증번호를 검증하고 1회용 verification_token을 발급합니다. |
| [`POST /api/auth/login`](#summary-post-api-auth-login) | 이메일/비밀번호를 검증하고 access token과 HttpOnly refresh 쿠키를 발급합니다. |
| [`POST /api/auth/logout`](#summary-post-api-auth-logout) | HttpOnly refresh 쿠키를 폐기하고 제거합니다. |
| [`GET /api/auth/me`](#summary-get-api-auth-me) | access token으로 인증된 사용자의 프로필을 반환합니다. |
| [`POST /api/auth/oauth/exchange`](#summary-post-api-auth-oauth-exchange) | OAuth code를 access token과 HttpOnly refresh 쿠키로 교환합니다. |
| [`POST /api/auth/password-reset`](#summary-post-api-auth-password-reset) | verification_token으로 본인 확인 후 비밀번호를 변경하고 기존 세션을 폐기합니다. |
| [`POST /api/auth/refresh`](#summary-post-api-auth-refresh) | HttpOnly refresh 쿠키를 검증하고 access token과 refresh 쿠키를 회전합니다. |
| [`POST /api/auth/signup`](#summary-post-api-auth-signup) | 이메일/비밀번호로 신규 사용자를 생성합니다. |

## 한눈에 보기

<a id="summary-post-api-auth-email-availability"></a>
### `POST /api/auth/email-availability`

| 항목 | 내용 |
|---|---|
| 목적 | 회원가입 전에 이메일로 신규 가입할 수 있는지 빠르게 확인합니다. OAuth 계정을 포함해 이미 등록된 이메일은 `available: false`를 반환합니다. |
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

회원가입 전에 이메일로 신규 가입할 수 있는지 빠르게 확인합니다. OAuth 계정을 포함해 이미 등록된 이메일은 `available: false`를 반환합니다.

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
| 주요 오류 | `400` 잘못된 요청 또는 유효하지 않은 토큰 — `ErrorResponse` |

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
