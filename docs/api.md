# API 계약

이 문서는 Fruition의 공개 API와 서비스 간 내부 API를 사람 기준으로 설명한다. 기계가 읽는 원본 계약은 `api-specs/<service>/openapi.yaml`이며, 충돌할 경우 실행 코드와 생성된 OpenAPI를 우선한다.

- 전체 operation 수: 139
- 인증 기본값: 사용자 API는 Bearer access token, 내부 API는 서비스 토큰을 사용한다.
- 공통 오류 형식은 서비스에 따라 `ErrorResponse` 또는 FastAPI validation 응답을 사용한다.
- AI에게 넘기는 대화 맥락은 서버가 세션에서 읽어 조립한다. 클라이언트는 어떤 문답을 쓸지만 `selected_pair_ids`로 고르고, 비우면 세션의 최근 완결 문답을 쓴다. 이 세션에 속하지 않은 ID는 무시한다.
- Java 서비스(access-svc·document-svc)는 개별 매핑이 없는 예외도 `ErrorResponse`로 응답한다. Spring이 상태 코드를 담아 던진 예외(없는 경로의 `404` 등)는 그 상태를 유지하며 `REQUEST_FAILED`, 그 밖의 예상치 못한 예외는 `500 INTERNAL_ERROR`를 쓴다.
- 각 API는 동일한 10개 항목을 유지한다. 해당 사항이 없더라도 항목을 생략하지 않는다.

- 서비스 라우팅: `/api/auth/*`·`/api/workspaces` → access-svc(:8081), 그 외 → document-svc(:8080).
- 인증: `Authorization: Bearer <access JWT(HS256, 기본 900s)>`. refresh는 opaque 토큰(DB에 sha256 해시만 저장, rotation).
- 사용자 API는 authenticated다. health·OpenAPI만 permitAll이다. `/internal/**`는 원칙적으로 `X-Internal-Token`을 검증하고, Agent worker가 document-svc의 Tool을 호출하는 `/internal/agent/tools/**`와 Skill 참조 read는 `X-Agent-Service-Token`을 검증한다.
- 에러 envelope: `{ "error": { "code", "message", "details" } }`. 검증 실패는 400 `INVALID_REQUEST` + field details. 예외→코드 전체 매핑은 원문 참조.
- `Idempotency-Key`가 적용된 API는 1~255자 키를 사용한다. 실행 선점 lease는 15분이고, 완료 응답은 완료 시점부터 24시간 유지한다. 같은 사용자·endpoint·키의 같은 요청이 완료되면 저장된 응답을 재생하고, 다른 payload는 409 `IDEMPOTENCY_CONFLICT`, lease 내 처리 중인 동시 요청은 409 `IDEMPOTENCY_IN_PROGRESS`로 거절한다. 실행이 실패하거나 lease가 만료되면 같은 키로 재시도할 수 있다.
- ID 형식: `user_`/`doc_`/`session_`/`query_`/`agent_`/`op_` + UUID/난수.
- LLM은 다음 세 조합만 지원한다. 기본값은 `openai`/`gpt-5-nano`이며 reasoning effort는 `medium`, `gemini`/`gemini-3.1-flash-lite`는 `low`, `claude`/`claude-sonnet-5`는 extended thinking을 사용하지 않는다. `provider`와 `model`은 항상 함께 생략하거나 함께 전달해야 하며, 다른 조합은 요청 검증 오류다.
- provider별 base URL은 `openai=https://api.openai.com/v1`, `gemini=https://generativelanguage.googleapis.com/v1beta/openai`, `claude=https://api.anthropic.com/v1`로 고정한다.
- Ingest·Lint·Skill author/publish/update는 workspace AI 모델 설정의 `provider`·`model` snapshot, Query·Markdown Agent·Agent 경로는 사용자/API 요청 또는 chat/request 설정의 snapshot을 사용한다. provider/model은 사용자 설정·API·DB·Kafka payload에서 전달하며 env override는 없다. API key는 ai-svc secret env의 `OPENAI_API_KEY`·`GEMINI_API_KEY`·`ANTHROPIC_API_KEY`에서만 읽고 provider별 고정 base URL을 사용한다.
- API key는 backend 요청·Kafka command/event·application log에 포함하지 않는다. 기존 AI 작업 로그의 조회/결과 API는 LLM 설정을 받지 않는다. 실제 provider 호출 전에는 선택 provider key가 필요하지만 mock 통합 테스트에는 key가 필요 없다.
- `allow_web_search`가 `true`일 때만 Tavily adapter를 구성하고 web route를 허용한다. `false`이면 내부 문서가 뒷받침하는 범위만 답하고 부족한 범위를 명시하며, 내부 근거가 전혀 없을 때만 unsupported로 처리한다.
- 원문: docs/backlog/spec/api/00-common.md
## curl 예시 실행

아래 예시는 서비스별 base URL 변수를 쓴다. 붙여넣기 전에 로컬 기준으로 한 번 정의한다.

```sh
export ACCESS=http://localhost:8081    # access-svc: /api/auth/*, /api/workspaces
export DOCUMENT=http://localhost:8080  # document-svc: 그 외 사용자 API
export PIPELINE=http://localhost:8000  # ai-svc pipeline: 내부 전용
```

프론트엔드는 `/api/*` 경로 기반 rewrite로 두 backend에 나눠 보낸다(`services/frontend/next.config.mjs`). `/api/auth/*`와 `/api/workspaces`·`/api/workspaces/{id}`는 access-svc, 그 외 `/api/**`는 document-svc가 받는다.

## 서비스 목차

1. [access-svc](#access-svc)
2. [document-svc](#document-svc)
3. [pipeline](#pipeline)

# access-svc

## Auth

### POST /api/auth/email-verifications

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

### POST /api/auth/email-verifications/{verification_id}/confirm

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

### POST /api/auth/login

#### 1. Method + Path

`POST /api/auth/login`

#### 2. 목적

이메일/비밀번호를 검증하고 access/refresh token을 발급합니다.

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
  "refresh_token": "EXAMPLE-refresh-token-not-a-real-value-0000",
  "token_type": "Bearer"
}
```

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
  --data '{"email":"user@example.com","password":"stringst"}'
```

```json
{
  "access_token": "string",
  "expires_in": 900,
  "refresh_token": "EXAMPLE-refresh-token-not-a-real-value-0000",
  "token_type": "Bearer"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: login`)

### POST /api/auth/logout

#### 1. Method + Path

`POST /api/auth/logout`

#### 2. 목적

refresh token을 폐기합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`RefreshRequest`)

```json
{
  "refresh_token": "EXAMPLE-refresh-token-not-a-real-value-0000"
}
```

#### 5. Response body

- HTTP `204`: 로그아웃 성공
- Body: 없음

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
curl -X POST "$ACCESS/api/auth/logout" \
  -H 'Content-Type: application/json' \
  --data '{"refresh_token":"EXAMPLE-refresh-token-not-a-real-value-0000"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: logout`)

### GET /api/auth/me

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

### POST /api/auth/oauth/exchange

#### 1. Method + Path

`POST /api/auth/oauth/exchange`

#### 2. 목적

OAuth 로그인 성공 후 발급된 1회용 code를 access/refresh token으로 교환합니다.

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
  "refresh_token": "EXAMPLE-refresh-token-not-a-real-value-0000",
  "token_type": "Bearer"
}
```

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
  --data '{"code":"<value>"}'
```

```json
{
  "access_token": "string",
  "expires_in": 900,
  "refresh_token": "EXAMPLE-refresh-token-not-a-real-value-0000",
  "token_type": "Bearer"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: exchangeOAuthCode`)

### POST /api/auth/password-reset

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

### POST /api/auth/refresh

#### 1. Method + Path

`POST /api/auth/refresh`

#### 2. 목적

refresh token을 검증하고 access/refresh token을 새로 발급합니다. 기존 refresh token은 폐기됩니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`RefreshRequest`)

```json
{
  "refresh_token": "EXAMPLE-refresh-token-not-a-real-value-0000"
}
```

#### 5. Response body

- HTTP `200`: 재발급 성공
- Content-Type: `*/*` (`LoginResponse`)

```json
{
  "access_token": "string",
  "expires_in": 900,
  "refresh_token": "EXAMPLE-refresh-token-not-a-real-value-0000",
  "token_type": "Bearer"
}
```

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
  -H 'Content-Type: application/json' \
  --data '{"refresh_token":"EXAMPLE-refresh-token-not-a-real-value-0000"}'
```

```json
{
  "access_token": "string",
  "expires_in": 900,
  "refresh_token": "EXAMPLE-refresh-token-not-a-real-value-0000",
  "token_type": "Bearer"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/access-svc/src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/access-svc/openapi.yaml` (`operationId: refresh`)

### POST /api/auth/signup

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

## Workspaces

### GET /api/workspaces

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

### POST /api/workspaces

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

### GET /api/workspaces/trash

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

### PATCH /api/workspaces/{workspace_id}

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

### DELETE /api/workspaces/{workspace_id}

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

### POST /api/workspaces/{workspace_id}/restore

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

## internal-authz-controller

### GET /internal/authz/workspaces/{workspace_id}/users/{user_id}

#### 1. Method + Path

`GET /internal/authz/workspaces/{workspace_id}/users/{user_id}`

#### 2. 목적

목적 설명 없음

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `user_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `string` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*`

```json
{
}
```

#### 6. Error response

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

### GET /internal/users/{user_id}

#### 1. Method + Path

`GET /internal/users/{user_id}`

#### 2. 목적

목적 설명 없음

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `user_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `string` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*`

```json
{
}
```

#### 6. Error response

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

## internal-workspace-ai-model-controller

### GET /internal/workspaces/{workspace_id}/ai-model-settings

#### 1. Method + Path

`GET /internal/workspaces/{workspace_id}/ai-model-settings`

#### 2. 목적

목적 설명 없음

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `string` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*`

```json
{
}
```

#### 6. Error response

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

### PUT /internal/workspaces/{workspace_id}/ai-model-settings

#### 1. Method + Path

`PUT /internal/workspaces/{workspace_id}/ai-model-settings`

#### 2. 목적

목적 설명 없음

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `string` | 아니요 | - |

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


# document-svc

## AI Models

### GET /api/ai-models

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

### GET /api/workspaces/{workspace_id}/ai-model-settings

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

```json
{
  "ingest_lint": {
    "model": "gpt-5-nano",
    "provider": "openai"
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
- 워크스페이스 OWNER 권한이 필요하다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/ai-model-settings" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "ingest_lint": {
    "model": "gpt-5-nano",
    "provider": "openai"
  }
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/ai/WorkspaceAiModelSettingsController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: get`)

### PUT /api/workspaces/{workspace_id}/ai-model-settings

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
  "ingest_lint": {
    "model": "gpt-5-nano",
    "provider": "openai"
  }
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/ai/WorkspaceAiModelSettingsController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: update`)

## AI Operation Logs

### GET /api/workspaces/{workspace_id}/ai-operation-logs

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/ai-operation-logs`

#### 2. 목적

최신순으로 반환합니다. 로그 테이블만 읽으며 diff를 계산하지 않습니다.

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
      "target_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
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
      "target_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
    }
  ],
  "next_cursor": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/aihistory/controller/OperationQueryController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: list_3`)

### GET /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}`

#### 2. 목적

그 작업이 바꾼 리소스를 함께 반환합니다. 줄 수는 저장된 값이라 계산이 없습니다.

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
              "type": "string"
            }
          ],
          "new_lines": 5,
          "new_start": 10,
          "old_lines": 3,
          "old_start": 10
        }
      ],
      "id": 1,
      "resource_id": "string"
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
  "summary": "string"
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
              "type": "string"
            }
          ],
          "new_lines": 5,
          "new_start": 10,
          "old_lines": 3,
          "old_start": 10
        }
      ],
      "id": 1,
      "resource_id": "string"
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
  "summary": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/aihistory/controller/OperationQueryController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: detail`)

### POST /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}/restore

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

### GET /api/workspaces/{workspace_id}/ai-operation-logs/{operation_id}/restore-preview

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

## Agent

### GET /api/workspaces/{workspace_id}/agent/runs/{run_id}

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/agent/runs/{run_id}`

#### 2. 목적

자율 AgentRun 계획과 실행 상태를 조회합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`JsonNode`)

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/runs/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: getRun`)

### POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/approve

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/approve`

#### 2. 목적

현재 AgentRun 계획을 승인합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | - |

- Content-Type: `application/json` (`AgentRunApproveRequest`)

```json
{
  "operation_hash": "string",
  "plan_version": 1
}
```

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`JsonNode`)

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/runs/<value>/approve" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"operation_hash":"<value>","plan_version":1}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: approve`)

### POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/cancel

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/cancel`

#### 2. 목적

현재 AgentRun을 취소합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`JsonNode`)

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/runs/<value>/cancel" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: cancel`)

### POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/reject

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/reject`

#### 2. 목적

현재 AgentRun 계획을 거절합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`JsonNode`)

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/runs/<value>/reject" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: reject`)

### POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/revise

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/revise`

#### 2. 목적

현재 AgentRun에 새 계획을 요청합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | - |

- Content-Type: `application/json` (`AgentRunReviseRequest`)

```json
{
  "instruction": "표를 목록으로 바꿔줘"
}
```

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`JsonNode`)

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/runs/<value>/revise" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"instruction":"표를 목록으로 바꿔줘"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: revise`)

### POST /api/workspaces/{workspace_id}/agent/turn

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/agent/turn`

#### 2. 목적

사용자 요청을 비동기 Agent 실행 대기열에 등록합니다.

질의와 편집을 나누지 않고 이 입구 하나로 받는다. 무엇을 할지는 AI가 정하며, 질의로 판정하면
근거와 함께 답하고 편집으로 판정하면 편집안을 만든다. 어느 쪽이든 `session_id`가 가리키는
채팅 세션에 문답으로 남는다.

문서를 열지 않은 상태에서도 보낼 수 있다. 그때는 `documentId`·`baseVersion`·`editorSnapshot`을
모두 생략하며, 적용할 대상이 없어 AI는 답변·되물음만 낸다. 셋은 함께 있거나 함께 없어야 하고
하나만 오면 `400`이다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| body | `session_id` | `string` | 예 | 이 턴을 남길 채팅 세션 ID |
| body | `message` | `string` | 예 | 사용자 지시문 |
| body | `documentId` | `string` | 아니오 | 편집 대상 문서. 생략하면 `baseVersion`·`editorSnapshot`도 함께 생략한다 |
| body | `baseVersion` | `integer` | 아니오 | 편집 기준 문서 버전 |
| body | `editorSnapshot` | `object` | 아니오 | 편집 시작 시점의 에디터 상태 |
| body | `allow_web_search` | `boolean` | 아니오 | AI가 질의로 판정했을 때 웹 검색을 허용할지. 편집·Skill 갈래에는 영향이 없다 |
| body | `conversationContext.selected_pair_ids` | `string[]` | 아니오 | 맥락으로 쓸 문답 ID(최대 20개). 비우면 세션의 최근 완결 문답을 쓴다 |

- Content-Type: `application/json` (`AgentTurnRequest`)

```json
{
  "baseVersion": 3,
  "conversationContext": {
    "pendingSkillProposal": {
      "description": "string",
      "instructions_markdown": "string",
      "name": "string",
      "scope_type": "string"
    },
    "referenceContext": {
    },
    "selected_pair_ids": [
      "string"
    ]
  },
  "documentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "editorSnapshot": {
    "markdown": "string",
    "target": {
      "endLine": 24,
      "startLine": 10,
      "type": "selection"
    }
  },
  "allow_web_search": false,
  "message": "이 문단을 표로 정리해줘",
  "model": "gpt-5-nano",
  "provider": "openai",
  "session_id": "session_0ff8564ea24047cd8144d3f48badfe3f",
  "skill_draft_excluded_literals": [
    "string"
  ],
  "skill_draft_sources": [
    {
      "run_id": "string"
    }
  ],
  "skill_draft_user_directives": [
    "string"
  ]
}
```

#### 5. Response body

- HTTP `202`: Agent 실행이 대기열에 등록됨
- Content-Type: `*/*` (`AgentTurnResponse`)

```json
{
  "apply_operation_id": "string",
  "baseVersion": 3,
  "documentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "error": "string",
  "requestId": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "result": {
  },
  "status": "completed"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 문서 version 충돌 | `ErrorResponse` |
| `423` | 다른 사용자가 문서를 편집 중 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/turn" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"baseVersion":3,"conversationContext":{"pendingSkillProposal":{"description":"<value>","instructions_markdown":"<value>","name":"<value>","scope_type":"<value>"},"recentConversationSummary":"<value>","referenceContext":{}},"documentId":"doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83","editorSnapshot":{"markdown":"<value>","target":{"endLine":24,"startLine":10,"type":"selection"}},"message":"이 문단을 표로 정리해줘","model":"gpt-5-nano","provider":"openai","skill_draft_excluded_literals":["<value>"],"skill_draft_sources":[{"run_id":"<value>"}],"skill_draft_user_directives":["<value>"]}'
```

```json
{
  "apply_operation_id": "string",
  "baseVersion": 3,
  "documentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "error": "string",
  "requestId": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "result": {
  },
  "status": "completed"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: turn`)

### GET /api/workspaces/{workspace_id}/agent/turn/{run_id}

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/agent/turn/{run_id}`

#### 2. 목적

워크스페이스의 Agent 실행 결과를 조회합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | 조회할 Agent 실행 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 결과 조회 성공
- Content-Type: `*/*` (`AgentTurnResponse`)

```json
{
  "apply_operation_id": "string",
  "baseVersion": 3,
  "documentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "error": "string",
  "requestId": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "result": {
  },
  "status": "completed"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | Agent run ID 형식이 올바르지 않음 | `ErrorResponse` |
| `404` | 실행 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `503` | Agent 상태 파이프라인 사용 불가 | `없음` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/turn/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "apply_operation_id": "string",
  "baseVersion": 3,
  "documentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "error": "string",
  "requestId": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "result": {
  },
  "status": "completed"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: getTurn`)

### GET /api/workspaces/{workspace_id}/agent/turn/{run_id}/events

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/agent/turn/{run_id}/events`

#### 2. 목적

Agent turn의 진행 상황과 최종 결과를 Server-Sent Events로 전달합니다.

AI가 질의로 판정한 턴만 단계 이벤트를 낸다. 편집·Skill 갈래는 완료 이벤트만 온다. 클라이언트는
어느 갈래인지 미리 알 필요 없이 접수 응답의 `requestId`로 구독하면 된다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | 구독할 Agent 실행 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: SSE 구독 시작
- Content-Type: `text/event-stream`

```json
string
```

전달하는 이벤트는 질의 SSE와 같은 세 가지다. 두 갈래가 같은 broker를 쓰므로 이름도 같다.

| event | 의미 | payload |
|---|---|---|
| `query.log` | AI worker가 단계마다 발행한 진행 상황을 중계 | `request_id`, `sequence`, `received_at`, `stage`, `message`, `data` |
| `query.completed` | 최종 결과 반영 완료 | `request_id`, `status` |
| `query.failed` | 실패 확정 | `request_id`, `status`, `error` |

- 구독 시점 이전 이벤트는 Redis buffer에서 최대 200건까지 재생한다.
- 종료 이벤트는 최초 반영에서 한 번만 낸다. 결과가 재전송돼도 두 번 끝나지 않는다.
- `query.failed`의 `error`는 사용자에게 보일 문장이다. 내부 오류 코드는 로그와 `ai_task_result_receipts`에만 남는다.

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | Agent run ID 형식이 올바르지 않음 | `ErrorResponse` |
| `404` | 실행 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
- path의 `workspace_id`에 대한 활성 멤버십과 해당 run의 소유를 검증한다.
- 자격 검증은 적용 표(`agent_apply_projections`)만으로 한다. 결과 조회와 달리 pipeline을 부르지 않아, pipeline이 멈춰 있어도 버퍼에 쌓인 이벤트를 구독할 수 있다.

#### 9. 예시 요청/응답

```bash
curl -N -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/agent/turn/<value>/events" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Accept: text/event-stream'
```

```json
string
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: subscribeTurnEvents`)

## Chat Sessions

### GET /api/workspaces/{workspace_id}/chat/sessions

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/chat/sessions`

#### 2. 목적

가장 최근 메시지 순으로 정렬해 반환합니다.

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
- Content-Type: `*/*` (`ChatSessionListResponse`)

```json
{
  "sessions": [
    {
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "last_message_at": "2026-08-13T04:25:24.371948Z",
      "title": "검색 인덱싱 질문"
    }
  ]
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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "sessions": [
    {
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "last_message_at": "2026-08-13T04:25:24.371948Z",
      "title": "검색 인덱싱 질문"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: list_1`)

### POST /api/workspaces/{workspace_id}/chat/sessions

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions`

#### 2. 목적

워크스페이스당 최대 10개까지 생성할 수 있습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`ChatSessionCreateRequest`)

```json
{
  "title": "검색 인덱싱 질문"
}
```

#### 5. Response body

- HTTP `201`: 생성 성공
- Content-Type: `*/*` (`ChatSessionResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "last_message_at": "2026-08-13T04:25:24.371948Z",
  "title": "검색 인덱싱 질문"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 세션 개수 제한 초과 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"title":"검색 인덱싱 질문"}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "last_message_at": "2026-08-13T04:25:24.371948Z",
  "title": "검색 인덱싱 질문"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: create_1`)

### DELETE /api/workspaces/{workspace_id}/chat/sessions/{session_id}

#### 1. Method + Path

`DELETE /api/workspaces/{workspace_id}/chat/sessions/{session_id}`

#### 2. 목적

워크스페이스에서 지정한 채팅 세션과 해당 세션의 메시지 기록을 삭제합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Body: 없음

#### 5. Response body

- HTTP `204`: 삭제 성공
- Body: 없음

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 세션 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X DELETE "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: delete_2`)

### GET /api/workspaces/{workspace_id}/chat/sessions/{session_id}/messages

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/chat/sessions/{session_id}/messages`

#### 2. 목적

세션 내 채팅 메시지를 생성 순서대로 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`ChatMessagesResponse`)

Agent turn이 만든 메시지는 `run_id`와 `action`이 함께 온다. 질의 메시지는 두 키가 빠진다.
화면은 `action`으로 편집 미리보기와 일반 답변을 나누고, 승인 상태와 미리보기 본문은 `run_id`가
가리키는 run에서 읽는다.

```json
{
  "messages": [
    {
      "action": "markdown_edit",
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "error_message": "string",
      "id": "string",
      "model": "gpt-5-nano",
      "pair_id": "string",
      "partial_wiki_page_ids": [
        "string"
      ],
      "provider": "openai",
      "references": [
        {
          "id": 1,
          "rank": 0,
          "reference_type": "string",
          "source_block_ids": [
            "string"
          ],
          "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
          "source_refs": [
            {
              "source_block_id": "string",
              "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
            }
          ],
          "text": "string"
        }
      ],
      "related_pages": [
        {
          "depth": 1,
          "page_type": "Concept",
          "rank": 0,
          "relevance_score": 0.87,
          "role": "string",
          "slug": "search-indexing",
          "title": "검색 인덱싱",
          "wiki_page_id": "string"
        }
      ],
      "run_id": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 세션 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/messages" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "messages": [
    {
      "action": "markdown_edit",
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "error_message": "string",
      "id": "string",
      "model": "gpt-5-nano",
      "pair_id": "string",
      "partial_wiki_page_ids": [
        "string"
      ],
      "provider": "openai",
      "references": [
        {
          "id": 1,
          "rank": 0,
          "reference_type": "string",
          "source_block_ids": [
            "string"
          ],
          "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
          "source_refs": [
            {
              "source_block_id": "string",
              "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
            }
          ],
          "text": "string"
        }
      ],
      "related_pages": [
        {
          "depth": 1,
          "page_type": "Concept",
          "rank": 0,
          "relevance_score": 0.87,
          "role": "string",
          "slug": "search-indexing",
          "title": "검색 인덱싱",
          "wiki_page_id": "string"
        }
      ],
      "run_id": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: getMessages`)

### POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki`

#### 2. 목적

세션(full) 또는 선택 문답(partial)을 Markdown 문서로 저장하고 처리 큐에 등록합니다. 위키 생성은 파이프라인이 비동기로 수행합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Content-Type: `application/json` (`ChatWikiExportRequest`)

```json
{
  "pair_ids": [
    "string"
  ],
  "selection_mode": "full"
}
```

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`ChatWikiExportResponse`)

```json
{
  "exportDocumentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "status": "processing"
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/wiki" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"pair_ids":["<value>"],"selection_mode":"full"}'
```

```json
{
  "exportDocumentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "status": "processing"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/chat/controller/ChatWikiExportController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: exportToWiki`)

### POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki/preview

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki/preview`

#### 2. 목적

세션을 llmPipeline 입력용 Markdown으로 직렬화해 결과만 반환합니다. 저장/파이프라인 호출은 하지 않습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `text/plain;charset=UTF-8`

```json
string
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/wiki/preview" \
  -H 'Authorization: Bearer <access_token>'
```

```json
string
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/chat/controller/ChatWikiExportController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: previewWikiMarkdown`)

## Document Assets

### GET /api/workspaces/{workspace_id}/assets/{asset_id}/content

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/assets/{asset_id}/content`

#### 2. 목적

워크스페이스 멤버에게 관리 이미지 bytes를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `asset_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 이미지 반환
- Content-Type: `*/*`

```json
<binary>
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `304` | 캐시된 이미지 사용 | `없음` |
| `404` | asset 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

```json
<binary>
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/assets/55555555-5555-5555-5555-555555555555/content" \
  -H 'Authorization: Bearer <access_token>'
```

```json
<binary>
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentAssetController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: getContent`)

## Document Edit Lock

### POST /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock`

#### 2. 목적

편집기 진입 시 호출한다. 비었거나 만료됐거나 본인 보유면 잠금을 부여(200)한다. 다른 사용자가 편집 중이면 423과 보유자 정보를 반환한다.

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

- HTTP `200`: 잠금 획득/갱신
- Content-Type: `*/*` (`EditLockResponse`)

```json
{
  "expires_at": "2026-08-13T04:25:24.371948Z",
  "holder_display_name": "표시 이름",
  "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `423` | 다른 사용자가 편집 중 | `EditLockResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/edit-lock" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "expires_at": "2026-08-13T04:25:24.371948Z",
  "holder_display_name": "표시 이름",
  "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentEditLockController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: acquire`)

### DELETE /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock

#### 1. Method + Path

`DELETE /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock`

#### 2. 목적

편집기 종료 시 호출한다. 보유자 본인의 잠금만 해제하며 멱등이다.

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

- HTTP `200`: OK
- Body: 없음

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X DELETE "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/edit-lock" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentEditLockController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: release`)

### POST /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock/heartbeat

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/edit-lock/heartbeat`

#### 2. 목적

편집 중 주기적으로 호출해 잠금을 연장한다. 보유자가 아니거나 만료됐으면 409.

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

- HTTP `200`: 잠금 연장
- Content-Type: `*/*` (`EditLockResponse`)

```json
{
  "expires_at": "2026-08-13T04:25:24.371948Z",
  "holder_display_name": "표시 이름",
  "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `409` | 잠금 상실(만료/타인 보유) | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/edit-lock/heartbeat" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "expires_at": "2026-08-13T04:25:24.371948Z",
  "holder_display_name": "표시 이름",
  "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentEditLockController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: heartbeat`)

## Documents

### GET /api/workspaces/{workspace_id}/documents

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents`

#### 2. 목적

활성 문서의 호환용 평면 목록을 반환하며 파일명 검색을 지원합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| query | `query` | `string` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 목록 조회 성공
- Content-Type: `*/*` (`DocumentListResponse`)

```json
{
  "documents": [
    {
      "area": "string",
      "byte_size": 482913,
      "current_version": 1,
      "display_name": "설계문서",
      "document_role": "EDITABLE",
      "editable": false,
      "error_message": "string",
      "extracted_text_uri": "string",
      "file_type": "pdf",
      "filename": "설계문서.pdf"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `500` | 서버 내부 오류 | `ErrorResponse` |

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
- 필터링: `query`

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents?query=<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "documents": [
    {
      "area": "string",
      "byte_size": 482913,
      "current_version": 1,
      "display_name": "설계문서",
      "document_role": "EDITABLE",
      "editable": false,
      "error_message": "string",
      "extracted_text_uri": "string",
      "file_type": "pdf",
      "filename": "설계문서.pdf"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: list`)

### POST /api/workspaces/{workspace_id}/documents

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents`

#### 2. 목적

PDF 또는 Markdown 파일을 업로드합니다. Markdown은 편집 상태와 처리 큐를 생성하고, PDF는 읽기 전용 원본으로만 저장합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |
| query | `folder_id` | `string` | 아니요 | - |

- Content-Type: `multipart/form-data`

```json
{
  "file": "<binary>"
}
```

#### 5. Response body

- HTTP `201`: 업로드 성공
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
| `400` | 파일 없음 또는 잘못된 요청 | `ErrorResponse` |
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Idempotency-Key 충돌 | `ErrorResponse` |
| `415` | 지원하지 않는 파일 형식 | `ErrorResponse` |
| `500` | 서버 내부 오류 | `ErrorResponse` |

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
- 필터링: 지원하지 않음 (요청 옵션: `folder_id`)

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents?folder_id=55555555-5555-5555-5555-555555555555" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -F 'file=@<file>'
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
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: upload`)

### POST /api/workspaces/{workspace_id}/documents/markdown

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/markdown`

#### 2. 목적

표시 이름과 전체 Markdown 본문으로 즉시 편집 가능한 문서를 생성합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`MarkdownDocumentCreateRequest`)

```json
{
  "display_name": "회의록",
  "folder_id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "markdown": "# 회의록\n\n- 첫 번째 안건"
}
```

#### 5. Response body

- HTTP `201`: 생성 성공 또는 멱등 재요청
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
| `400` | 잘못된 본문 또는 Idempotency-Key | `ErrorResponse` |
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Idempotency-Key 충돌 | `ErrorResponse` |
| `413` | Markdown 5MB 초과 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/markdown" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"display_name":"회의록","folder_id":"8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2","markdown":"# 회의록\n\n- 첫 번째 안건"}'
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
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: createMarkdown`)

### GET /api/workspaces/{workspace_id}/documents/trash

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/trash`

#### 2. 목적

워크스페이스에서 소프트 삭제된 문서를 삭제 시각 역순으로 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 휴지통 조회 성공
- Content-Type: `*/*` (`DocumentTrashResponse`)

```json
{
  "documents": [
    {
      "current_version": 3,
      "delete_operation_id": "55555555-5555-5555-5555-555555555555",
      "deleted_at": "2026-08-13T04:25:24.371948Z",
      "deleted_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
      "display_name": "설계문서",
      "document_role": "EDITABLE",
      "filename": "설계문서.pdf",
      "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "source_document_id": "string"
    }
  ]
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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/trash" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "documents": [
    {
      "current_version": 3,
      "delete_operation_id": "55555555-5555-5555-5555-555555555555",
      "deleted_at": "2026-08-13T04:25:24.371948Z",
      "deleted_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
      "display_name": "설계문서",
      "document_role": "EDITABLE",
      "filename": "설계문서.pdf",
      "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "source_document_id": "string"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: trash`)

### GET /api/workspaces/{workspace_id}/documents/{document_id}

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}`

#### 2. 목적

특정 문서의 상세 정보를 반환합니다. 연결된 Wiki 페이지 목록이 포함됩니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | 문서 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 상세 조회 성공
- Content-Type: `*/*` (`DocumentDetailResponse`)

```json
{
  "byte_size": 482913,
  "current_version": 3,
  "display_name": "설계문서",
  "document_role": "EDITABLE",
  "edit_lock": {
    "expires_at": "2026-08-13T04:25:24.371948Z",
    "holder_display_name": "표시 이름",
    "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
  },
  "edit_revision": 12,
  "editable": true,
  "error_message": "string",
  "extracted_text_uri": "string",
  "file_type": "pdf"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `500` | 서버 내부 오류 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "byte_size": 482913,
  "current_version": 3,
  "display_name": "설계문서",
  "document_role": "EDITABLE",
  "edit_lock": {
    "expires_at": "2026-08-13T04:25:24.371948Z",
    "holder_display_name": "표시 이름",
    "holder_user_id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
  },
  "edit_revision": 12,
  "editable": true,
  "error_message": "string",
  "extracted_text_uri": "string",
  "file_type": "pdf"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: getById`)

### DELETE /api/workspaces/{workspace_id}/documents/{document_id}

#### 1. Method + Path

`DELETE /api/workspaces/{workspace_id}/documents/{document_id}`

#### 2. 목적

원본과 편집 상태를 유지한 채 문서를 소프트 삭제합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | 문서 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`DocumentLifecycleRequest`)

```json
{
  "base_version": 1
}
```

#### 5. Response body

- HTTP `200`: 삭제 성공
- Content-Type: `*/*` (`DocumentLifecycleResponse`)

```json
{
  "current_version": 2,
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "sort_order": 1024
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 base_version 또는 Idempotency-Key | `ErrorResponse` |
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 문서 version 또는 멱등 키 충돌 | `ErrorResponse` |

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
curl -X DELETE "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1}'
```

```json
{
  "current_version": 2,
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "sort_order": 1024
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: delete_1`)

### GET /api/workspaces/{workspace_id}/documents/{document_id}/blocks

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}/blocks`

#### 2. 목적

원본 문서를 block 단위로 나눈 텍스트 목록을 반환합니다. 답변 인용 클릭 시 원본 block 하이라이트에 사용됩니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | 문서 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`DocumentBlocksResponse`)

```json
{
  "blocks": [
    {
      "block_id": "string",
      "text": "string"
    }
  ],
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/blocks" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "blocks": [
    {
      "block_id": "string",
      "text": "string"
    }
  ],
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: blocks`)

### PUT /api/workspaces/{workspace_id}/documents/{document_id}/content

#### 1. Method + Path

`PUT /api/workspaces/{workspace_id}/documents/{document_id}/content`

#### 2. 목적

전체 Markdown과 신규 이미지를 저장합니다. base_revision이 현재 편집 revision과 일치할 때만 반영하며 revision_write_id 재시도는 기존 결과를 반환합니다. 이미지 포함 저장은 metadata part를 사용합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |

- Content-Type: `multipart/form-data`

```json
{
  "apply_operation_id": "string",
  "base_revision": "string",
  "markdown": "string",
  "metadata": "string",
  "revision_write_id": "string",
  "source": "string"
}
```

#### 5. Response body

- HTTP `200`: 저장 성공 또는 동일 본문 no-op
- Content-Type: `*/*` (`DocumentContentSaveResponse`)

```json
{
  "attachments": [
    {
      "asset_id": "55555555-5555-5555-5555-555555555555",
      "attachment_id": "55555555-5555-5555-5555-555555555555",
      "content_path": "string"
    }
  ],
  "changed": true,
  "content_hash": "string",
  "current_version": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "markdown": "string",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Markdown, base_revision 또는 revision_write_id | `ErrorResponse` |
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 편집 revision 또는 revision_write_id 충돌 | `ErrorResponse` |
| `413` | Markdown 5MB 또는 이미지 제한 초과 | `ErrorResponse` |
| `415` | 지원하지 않는 이미지 형식 | `ErrorResponse` |

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
curl -X PUT "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/content" \
  -H 'Authorization: Bearer <access_token>' \
  -F 'apply_operation_id=<value>' \
  -F 'base_revision=<value>' \
  -F 'markdown=<value>' \
  -F 'metadata=<value>' \
  -F 'revision_write_id=<value>' \
  -F 'source=<value>'
```

```json
{
  "attachments": [
    {
      "asset_id": "55555555-5555-5555-5555-555555555555",
      "attachment_id": "55555555-5555-5555-5555-555555555555",
      "content_path": "string"
    }
  ],
  "changed": true,
  "content_hash": "string",
  "current_version": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "markdown": "string",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: saveContent`)

### POST /api/workspaces/{workspace_id}/documents/{document_id}/convert-markdown

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/convert-markdown`

#### 2. 목적

PDF 원본 문서를 Markdown 문서로 변환합니다. 변환 결과를 담을 편집 가능 placeholder 문서를 즉시 만들어 반환하고, 실제 변환은 백그라운드에서 진행됩니다.

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

### GET /api/workspaces/{workspace_id}/documents/{document_id}/diff

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}/diff`

#### 2. 목적

두 Markdown 버전을 줄 단위로 비교해 GitHub 스타일 diff hunk를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |
| query | `from_version` | `integer` | 예 | - |
| query | `to_version` | `integer` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 비교 성공
- Content-Type: `*/*` (`DocumentContentDiffResponse`)

```json
{
  "additions": 12,
  "deletions": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "from_version": 2,
  "hunks": [
    {
      "lines": [
        {
          "content": "string",
          "new_line": 10,
          "old_line": 10,
          "type": "string"
        }
      ],
      "new_lines": 5,
      "new_start": 10,
      "old_lines": 3,
      "old_start": 10
    }
  ],
  "to_version": 3
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 편집 가능한 Markdown 문서가 아님 | `ErrorResponse` |
| `404` | 문서 또는 비교할 버전을 찾을 수 없음 | `ErrorResponse` |
| `422` | 문서 차이가 너무 커서 안전하게 비교할 수 없음 | `ErrorResponse` |

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
- 필터링: `from_version`, `to_version`

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/diff?from_version=1&to_version=1" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "additions": 12,
  "deletions": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "from_version": 2,
  "hunks": [
    {
      "lines": [
        {
          "content": "string",
          "new_line": 10,
          "old_line": 10,
          "type": "string"
        }
      ],
      "new_lines": 5,
      "new_start": 10,
      "old_lines": 3,
      "old_start": 10
    }
  ],
  "to_version": 3
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: compareVersions`)

### POST /api/workspaces/{workspace_id}/documents/{document_id}/duplicate

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/duplicate`

#### 2. 목적

문서 소유자가 최신 Markdown 편집본을 같은 부모의 마지막 위치에 새 문서로 복제합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Body: 없음

#### 5. Response body

- HTTP `201`: 복제 성공 또는 멱등 재요청
- Content-Type: `*/*` (`DocumentDuplicateResponse`)

```json
{
  "byte_size": 482913,
  "current_version": 1,
  "display_name": "설계문서 (사본)",
  "filename": "설계문서 (사본).pdf",
  "folder_id": "55555555-5555-5555-5555-555555555555",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "mime_type": "application/pdf",
  "sort_order": 2048,
  "source_document_id": "doc_8d4f1e6c3b0a97d25e4f831b9f4c7e2a"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Idempotency-Key | `ErrorResponse` |
| `403` | 문서 소유자가 아니거나 편집 문서가 아님 | `ErrorResponse` |
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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/duplicate" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>'
```

```json
{
  "byte_size": 482913,
  "current_version": 1,
  "display_name": "설계문서 (사본)",
  "filename": "설계문서 (사본).pdf",
  "folder_id": "55555555-5555-5555-5555-555555555555",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "mime_type": "application/pdf",
  "sort_order": 2048,
  "source_document_id": "doc_8d4f1e6c3b0a97d25e4f831b9f4c7e2a"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: duplicate`)

### GET /api/workspaces/{workspace_id}/documents/{document_id}/export

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}/export`

#### 2. 목적

최신 Markdown 편집본을 내보냅니다. 관리 이미지가 있으면 이미지와 Markdown을 ZIP으로 반환합니다.

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

- HTTP `200`: Markdown 다운로드
- Content-Type: `*/*`

```json
<binary>
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | workspace, Markdown 문서 또는 편집 상태를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/export" \
  -H 'Authorization: Bearer <access_token>'
```

```json
<binary>
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: export`)

### POST /api/workspaces/{workspace_id}/documents/{document_id}/ingest

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

### GET /api/workspaces/{workspace_id}/documents/{document_id}/original

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}/original`

#### 2. 목적

MinIO에 저장된 원본 파일을 스트리밍합니다. PDF는 inline, 그 외는 attachment로 반환됩니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | 문서 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 원본 파일 반환
- Content-Type: `*/*`

```json
<binary>
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 문서, 원본 파일 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/original" \
  -H 'Authorization: Bearer <access_token>'
```

```json
<binary>
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: getOriginal`)

### PATCH /api/workspaces/{workspace_id}/documents/{document_id}/position

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}/documents/{document_id}/position`

#### 2. 목적

문서를 대상 폴더와 정렬 위치로 이동합니다. base version과 Idempotency-Key로 동시 변경을 검증합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | 이동할 문서 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`DocumentPositionRequest`)

```json
{
  "base_version": 1,
  "folder_id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "position": 0
}
```

#### 5. Response body

- HTTP `200`: 이동 성공 또는 멱등 재요청
- Content-Type: `*/*` (`DocumentPositionResponse`)

```json
{
  "current_version": 2,
  "folder_id": "55555555-5555-5555-5555-555555555555",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "sort_order": 1024
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 위치 또는 version, 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) | `ErrorResponse` |
| `404` | 문서, 대상 폴더 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) | `ErrorResponse` |

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
curl -X PATCH "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/position" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1,"folder_id":"8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2","position":0}'
```

```json
{
  "current_version": 2,
  "folder_id": "55555555-5555-5555-5555-555555555555",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "sort_order": 1024
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentPositionController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: move_1`)

### PATCH /api/workspaces/{workspace_id}/documents/{document_id}/rename

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}/documents/{document_id}/rename`

#### 2. 목적

Notion의 page title처럼 표시 이름만 변경하며 본문과 Wiki 제목은 유지합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | 문서 ID |

- Content-Type: `application/json` (`DocumentRenameRequest`)

```json
{
  "base_version": 1,
  "display_name": "이름 바꾼 회의록"
}
```

#### 5. Response body

- HTTP `200`: 이름 변경 성공
- Content-Type: `*/*` (`DocumentRenameResponse`)

```json
{
  "changed": true,
  "current_version": 2,
  "display_name": "이름 바꾼 회의록",
  "filename": "회의록.md",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 유효하지 않은 파일명 | `ErrorResponse` |
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 문서 version 충돌 | `ErrorResponse` |

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
curl -X PATCH "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/rename" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1,"display_name":"이름 바꾼 회의록"}'
```

```json
{
  "changed": true,
  "current_version": 2,
  "display_name": "이름 바꾼 회의록",
  "filename": "회의록.md",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: rename_2`)

### POST /api/workspaces/{workspace_id}/documents/{document_id}/restore

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/restore`

#### 2. 목적

삭제 문서를 역할별 최상위 마지막 위치에 복구합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`DocumentLifecycleRequest`)

```json
{
  "base_version": 1
}
```

#### 5. Response body

- HTTP `200`: 복구 성공
- Content-Type: `*/*` (`DocumentLifecycleResponse`)

```json
{
  "current_version": 2,
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "sort_order": 1024
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 base_version 또는 Idempotency-Key | `ErrorResponse` |
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 삭제 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 문서 version 또는 멱등 키 충돌 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/restore" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1}'
```

```json
{
  "current_version": 2,
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "sort_order": 1024
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: restore_1`)

### GET /api/workspaces/{workspace_id}/documents/{document_id}/versions

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}/versions`

#### 2. 목적

편집 가능 Markdown 문서의 콘텐츠 버전 이력을 최신 순으로 반환합니다. 본문은 제외한 메타데이터만 제공합니다.

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

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`DocumentContentVersionListResponse`)

```json
{
  "current_version": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "versions": [
    {
      "content_hash": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "created_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
      "version": 3
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 편집 가능한 Markdown 문서가 아님 | `ErrorResponse` |
| `404` | 문서 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/versions" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "current_version": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "versions": [
    {
      "content_hash": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "created_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
      "version": 3
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: listVersions`)

### GET /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}`

#### 2. 목적

특정 버전의 전체 Markdown 본문을 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |
| path | `version` | `integer` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`DocumentContentVersionResponse`)

```json
{
  "content_hash": "string",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "created_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "markdown": "string",
  "version": 3
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 문서 또는 해당 버전을 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/versions/1" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "content_hash": "string",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "created_by": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "markdown": "string",
  "version": 3
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: getVersion`)

### POST /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}/restore

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/documents/{document_id}/versions/{version}/restore`

#### 2. 목적

과거 버전을 새 버전으로 복원합니다(비파괴적). base_version이 현재 version과 일치할 때만 반영합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |
| path | `version` | `integer` | 예 | - |

- Content-Type: `application/json` (`DocumentContentRestoreRequest`)

```json
{
  "base_version": 4
}
```

#### 5. Response body

- HTTP `200`: 복원 성공 또는 동일 본문 no-op
- Content-Type: `*/*` (`DocumentContentSaveResponse`)

```json
{
  "attachments": [
    {
      "asset_id": "55555555-5555-5555-5555-555555555555",
      "attachment_id": "55555555-5555-5555-5555-555555555555",
      "content_path": "string"
    }
  ],
  "changed": true,
  "content_hash": "string",
  "current_version": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "markdown": "string",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 편집 가능한 Markdown 문서가 아니거나 base_version 오류 | `ErrorResponse` |
| `403` | 문서 소유자가 아님 | `ErrorResponse` |
| `404` | 문서 또는 해당 버전을 찾을 수 없음 | `ErrorResponse` |
| `409` | 문서 version 충돌 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/documents/<value>/versions/1/restore" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":4}'
```

```json
{
  "attachments": [
    {
      "asset_id": "55555555-5555-5555-5555-555555555555",
      "attachment_id": "55555555-5555-5555-5555-555555555555",
      "content_path": "string"
    }
  ],
  "changed": true,
  "content_hash": "string",
  "current_version": 4,
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "markdown": "string",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: restoreVersion`)

## Folders

### POST /api/workspaces/{workspace_id}/folders

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/folders`

#### 2. 목적

워크스페이스의 최상위 또는 지정한 상위 폴더 아래에 새 폴더를 생성합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`FolderCreateRequest`)

```json
{
  "name": "설계",
  "parent_folder_id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2"
}
```

#### 5. Response body

- HTTP `201`: 생성 성공 또는 멱등 재요청
- Content-Type: `*/*` (`FolderResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "current_version": 1,
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "name": "설계",
  "parent_folder_id": "55555555-5555-5555-5555-555555555555",
  "sort_order": 1024,
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 이름 또는 위치, 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) | `ErrorResponse` |
| `404` | 워크스페이스 또는 상위 폴더를 찾을 수 없음 | `ErrorResponse` |
| `409` | IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/folders" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"name":"설계","parent_folder_id":"8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2"}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "current_version": 1,
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "name": "설계",
  "parent_folder_id": "55555555-5555-5555-5555-555555555555",
  "sort_order": 1024,
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/FolderController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: create`)

### PATCH /api/workspaces/{workspace_id}/folders/{folder_id}

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}/folders/{folder_id}`

#### 2. 목적

폴더 이름을 변경하고 base version으로 동시 변경을 검증합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `folder_id` | `string` | 예 | 이름을 변경할 폴더 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`FolderRenameRequest`)

```json
{
  "base_version": 1,
  "name": "설계 문서"
}
```

#### 5. Response body

- HTTP `200`: 변경 성공 또는 멱등 재요청
- Content-Type: `*/*` (`FolderResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "current_version": 1,
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "name": "설계",
  "parent_folder_id": "55555555-5555-5555-5555-555555555555",
  "sort_order": 1024,
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 이름 또는 version, 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) | `ErrorResponse` |
| `404` | 폴더 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) | `ErrorResponse` |

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
curl -X PATCH "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/folders/55555555-5555-5555-5555-555555555555" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1,"name":"설계 문서"}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "current_version": 1,
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "name": "설계",
  "parent_folder_id": "55555555-5555-5555-5555-555555555555",
  "sort_order": 1024,
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/FolderController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: rename_1`)

### DELETE /api/workspaces/{workspace_id}/folders/{folder_id}

#### 1. Method + Path

`DELETE /api/workspaces/{workspace_id}/folders/{folder_id}`

#### 2. 목적

폴더와 하위 항목을 휴지통 상태로 전환하며 base version으로 동시 변경을 검증합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `folder_id` | `string` | 예 | 삭제할 폴더 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`DocumentLifecycleRequest`)

```json
{
  "base_version": 1
}
```

#### 5. Response body

- HTTP `200`: 삭제 성공 또는 멱등 재요청
- Content-Type: `*/*` (`FolderLifecycleResponse`)

```json
{
  "current_version": 2,
  "delete_operation_id": "55555555-5555-5555-5555-555555555555",
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 version 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) | `ErrorResponse` |
| `403` | 내용이 있는 폴더를 삭제할 권한이 없음 | `ErrorResponse` |
| `404` | 폴더 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) | `ErrorResponse` |

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
curl -X DELETE "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/folders/55555555-5555-5555-5555-555555555555" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1}'
```

```json
{
  "current_version": 2,
  "delete_operation_id": "55555555-5555-5555-5555-555555555555",
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/FolderController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: delete`)

### GET /api/workspaces/{workspace_id}/folders/{folder_id}/children

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/folders/{folder_id}/children`

#### 2. 목적

폴더 바로 아래의 하위 폴더와 문서를 정렬 순서로 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `folder_id` | `string` | 예 | 조회할 폴더 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`FolderChildrenResponse`)

```json
{
  "items": [
    {
      "current_version": 1,
      "has_children": false,
      "id": "string",
      "name": "회의록",
      "sort_order": 1024,
      "type": "document"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 폴더 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/folders/55555555-5555-5555-5555-555555555555/children" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "items": [
    {
      "current_version": 1,
      "has_children": false,
      "id": "string",
      "name": "회의록",
      "sort_order": 1024,
      "type": "document"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/FolderController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: children`)

### PATCH /api/workspaces/{workspace_id}/folders/{folder_id}/position

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}/folders/{folder_id}/position`

#### 2. 목적

폴더를 대상 상위 폴더와 정렬 위치로 이동합니다. 자기 자신이나 하위 폴더로는 이동할 수 없습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `folder_id` | `string` | 예 | 이동할 폴더 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`FolderPositionRequest`)

```json
{
  "base_version": 1,
  "parent_folder_id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "position": 0
}
```

#### 5. Response body

- HTTP `200`: 이동 성공 또는 멱등 재요청
- Content-Type: `*/*` (`FolderResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "current_version": 1,
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "name": "설계",
  "parent_folder_id": "55555555-5555-5555-5555-555555555555",
  "sort_order": 1024,
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) | `ErrorResponse` |
| `404` | 폴더, 대상 폴더 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 순환 이동, version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) | `ErrorResponse` |

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
curl -X PATCH "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/folders/55555555-5555-5555-5555-555555555555/position" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1,"parent_folder_id":"8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2","position":0}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "current_version": 1,
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2",
  "name": "설계",
  "parent_folder_id": "55555555-5555-5555-5555-555555555555",
  "sort_order": 1024,
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/FolderController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: move`)

### POST /api/workspaces/{workspace_id}/folders/{folder_id}/restore

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/folders/{folder_id}/restore`

#### 2. 목적

삭제된 폴더와 하위 항목을 복구하고 유효한 탐색 위치에 배치합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `folder_id` | `string` | 예 | 복구할 폴더 ID |
| header | `Idempotency-Key` | `string` | 예 | 요청 멱등 키 |

- Content-Type: `application/json` (`DocumentLifecycleRequest`)

```json
{
  "base_version": 1
}
```

#### 5. Response body

- HTTP `200`: 복구 성공 또는 멱등 재요청
- Content-Type: `*/*` (`FolderLifecycleResponse`)

```json
{
  "current_version": 2,
  "delete_operation_id": "55555555-5555-5555-5555-555555555555",
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 version 또는 INVALID_IDEMPOTENCY_KEY(멱등 키 누락/유효하지 않음) | `ErrorResponse` |
| `404` | 삭제된 폴더 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | version 충돌, IDEMPOTENCY_CONFLICT(동일 키에 다른 payload 사용) 또는 IDEMPOTENCY_IN_PROGRESS(활성 lease 재사용) | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/folders/55555555-5555-5555-5555-555555555555/restore" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Idempotency-Key: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"base_version":1}'
```

```json
{
  "current_version": 2,
  "delete_operation_id": "55555555-5555-5555-5555-555555555555",
  "deleted": true,
  "deleted_at": "2026-08-13T04:25:24.371948Z",
  "id": "8d4f1e6c-3b0a-497d-25e4-f831b9f4c7e2"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/FolderController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: restore`)

## Navigation

### GET /api/workspaces/{workspace_id}/document-tree

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/document-tree`

#### 2. 목적

모든 폴더를 펼친 상태의 활성 폴더·문서 계층을 한 번에 반환합니다.

문서 항목에는 목록 조회(`GET /documents`)가 주는 것과 같은 메타데이터가 `document`에 담겨 온다.
화면이 계층과 문서 상태를 함께 쓰므로 두 번 부르지 않아도 된다. 두 응답은 같은 변환 규칙을 쓰므로
같은 문서가 화면마다 다르게 보이지 않는다.

폴더 항목에는 `document` 키가 없다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 전체 트리 조회 성공
- Content-Type: `*/*` (`DocumentTreeResponse`)

```json
{
  "items": [
    {
      "children": [
        {
        }
      ],
      "current_version": 1,
      "has_children": true,
      "id": "string",
      "name": "설계",
      "sort_order": 1024,
      "type": "folder"
    },
    {
      "current_version": 3,
      "document": {
        "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
        "status": "completed",
        "document_role": "EDITABLE",
        "needs_reingest": false
      },
      "has_children": false,
      "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "name": "note.md",
      "sort_order": 1024,
      "type": "document"
    }
  ]
}
```

- `document`는 `DocumentItem` 전체를 담는다. 위 예시는 지면상 일부만 보였다. 전체 필드는
  `GET /api/workspaces/{workspace_id}/documents` 항목과 같다.

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 활성 워크스페이스 또는 멤버십을 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/document-tree" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "items": [
    {
      "children": [
        {
        }
      ],
      "current_version": 1,
      "has_children": true,
      "id": "string",
      "name": "설계",
      "sort_order": 1024,
      "type": "folder"
    },
    {
      "current_version": 3,
      "document": {
        "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
        "status": "completed",
        "document_role": "EDITABLE",
        "needs_reingest": false
      },
      "has_children": false,
      "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "name": "note.md",
      "sort_order": 1024,
      "type": "document"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/DocumentTreeController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: tree`)

### GET /api/workspaces/{workspace_id}/navigation

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/navigation`

#### 2. 목적

워크스페이스 최상위의 폴더와 문서를 정렬 순서로 반환합니다.

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
- Content-Type: `*/*` (`FolderChildrenResponse`)

```json
{
  "items": [
    {
      "current_version": 1,
      "has_children": false,
      "id": "string",
      "name": "회의록",
      "sort_order": 1024,
      "type": "document"
    }
  ]
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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/navigation" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "items": [
    {
      "current_version": 1,
      "has_children": false,
      "id": "string",
      "name": "회의록",
      "sort_order": 1024,
      "type": "document"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/NavigationController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: root`)

### GET /api/workspaces/{workspace_id}/navigation/breadcrumb

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/navigation/breadcrumb`

#### 2. 목적

폴더 또는 문서까지 이어지는 상위 폴더 경로를 최상위부터 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| query | `folder_id` | `string` | 아니요 | 경로를 조회할 폴더 ID. document_id와 함께 사용할 수 없습니다. |
| query | `document_id` | `string` | 아니요 | 경로를 조회할 문서 ID. folder_id와 함께 사용할 수 없습니다. |

- Body: 없음

#### 5. Response body

- HTTP `200`: 경로 조회 성공
- Content-Type: `*/*` (`BreadcrumbResponse`)

```json
{
  "path": [
    {
      "id": "string",
      "name": "설계",
      "type": "folder"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | folder_id와 document_id가 모두 없거나 함께 전달됨 | `ErrorResponse` |
| `404` | 대상 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
- 필터링: `folder_id`, `document_id`

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/navigation/breadcrumb?folder_id=55555555-5555-5555-5555-555555555555&document_id=<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "path": [
    {
      "id": "string",
      "name": "설계",
      "type": "folder"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/NavigationController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: breadcrumb`)

### GET /api/workspaces/{workspace_id}/navigation/search

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/navigation/search`

#### 2. 목적

워크스페이스의 폴더 이름과 문서 파일명을 검색해 계층 경로를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| query | `query` | `string` | 예 | 폴더 이름 또는 문서 파일명 검색어 |

- Body: 없음

#### 5. Response body

- HTTP `200`: 검색 성공
- Content-Type: `*/*` (`HierarchySearchResponse`)

```json
{
  "results": [
    {
      "breadcrumb": [
        {
          "id": "string",
          "name": "설계",
          "type": "folder"
        }
      ],
      "id": "string",
      "name": "회의록",
      "type": "document"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 검색어가 비어 있거나 잘못됨 | `ErrorResponse` |
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
- 필터링: `query`

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/navigation/search?query=<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "results": [
    {
      "breadcrumb": [
        {
          "id": "string",
          "name": "설계",
          "type": "folder"
        }
      ],
      "id": "string",
      "name": "회의록",
      "type": "document"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/NavigationController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: search`)

## Query

### POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query`

#### 2. 목적

질문을 받아 Wiki 페이지를 검색하고 LLM으로 답변을 생성합니다. 응답에는 답변, 관련 Wiki 페이지, 원본 출처, 그래프 하이라이트 경로가 포함됩니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Content-Type: `application/json` (`QueryRequest`)

```json
{
  "allow_web_search": false,
  "model": "gpt-5-nano",
  "provider": "openai",
  "question": "검색 인덱싱은 어떻게 동작하나요?"
}
```

#### 5. Response body

- HTTP `200`: 질의 성공
- Content-Type: `*/*` (`QueryResponse`)

```json
{
  "assistant_message": {
    "content": "string",
    "created_at": "2026-08-13T04:25:24.371948Z",
    "id": "string",
    "role": "assistant",
    "status": "completed"
  },
  "error_code": "web_search_unavailable",
  "evidence_snippets": [
    {
      "rank": 0,
      "source_block_ids": [
        "string"
      ],
      "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "source_refs": [
        {
          "source_block_id": "string",
          "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
        }
      ],
      "text": "string"
    }
  ],
  "graph_context": {
    "edges": [
      {
        "from_page_id": "string",
        "link_type": "related",
        "role": "string",
        "score": 0.72,
        "to_page_id": "string"
      }
    ],
    "nodes": [
      {
        "depth": 1,
        "id": "string",
        "page_type": "Concept",
        "relevance_score": 0.87,
        "role": "string",
        "slug": "search-indexing",
        "title": "검색 인덱싱"
      }
    ]
  },
  "related_pages": [
    {
      "depth": 1,
      "id": "string",
      "page_type": "Concept",
      "relevance_score": 0.87,
      "role": "string",
      "slug": "search-indexing",
      "title": "검색 인덱싱"
    }
  ],
  "result_count": 5,
  "traversal_paths": [
    {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "related",
          "role": "string",
          "score": 0.72,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        "string"
      ],
      "path_id": "string",
      "role": "string",
      "score": 0.72,
      "stop_reason": "string",
      "used_for_answer": true
    }
  ],
  "user_message": {
    "content": "string",
    "created_at": "2026-08-13T04:25:24.371948Z",
    "id": "string",
    "role": "assistant",
    "status": "completed"
  },
  "web_search_executed": false,
  "web_search_requested": false
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 (질문이 비어 있는 경우) | `ErrorResponse` |
| `404` | 세션 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `500` | 서버 내부 오류 | `ErrorResponse` |
| `502` | 파이프라인 요청 거부 | `ErrorResponse` |
| `503` | 파이프라인 타임아웃 또는 사용 불가 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/query" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"allow_web_search":false,"model":"gpt-5-nano","provider":"openai","question":"검색 인덱싱은 어떻게 동작하나요?"}'
```

```json
{
  "assistant_message": {
    "content": "string",
    "created_at": "2026-08-13T04:25:24.371948Z",
    "id": "string",
    "role": "assistant",
    "status": "completed"
  },
  "error_code": "web_search_unavailable",
  "evidence_snippets": [
    {
      "rank": 0,
      "source_block_ids": [
        "string"
      ],
      "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "source_refs": [
        {
          "source_block_id": "string",
          "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
        }
      ],
      "text": "string"
    }
  ],
  "graph_context": {
    "edges": [
      {
        "from_page_id": "string",
        "link_type": "related",
        "role": "string",
        "score": 0.72,
        "to_page_id": "string"
      }
    ],
    "nodes": [
      {
        "depth": 1,
        "id": "string",
        "page_type": "Concept",
        "relevance_score": 0.87,
        "role": "string",
        "slug": "search-indexing",
        "title": "검색 인덱싱"
      }
    ]
  },
  "related_pages": [
    {
      "depth": 1,
      "id": "string",
      "page_type": "Concept",
      "relevance_score": 0.87,
      "role": "string",
      "slug": "search-indexing",
      "title": "검색 인덱싱"
    }
  ],
  "result_count": 5,
  "traversal_paths": [
    {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "related",
          "role": "string",
          "score": 0.72,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        "string"
      ],
      "path_id": "string",
      "role": "string",
      "score": 0.72,
      "stop_reason": "string",
      "used_for_answer": true
    }
  ],
  "user_message": {
    "content": "string",
    "created_at": "2026-08-13T04:25:24.371948Z",
    "id": "string",
    "role": "assistant",
    "status": "completed"
  },
  "web_search_executed": false,
  "web_search_requested": false
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/query/controller/QueryController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: query`)

### POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query/runs

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query/runs`

#### 2. 목적

질의를 비동기 run으로 시작합니다. 진행 상황은 GET /api/query/runs/{request_id}/events(SSE)로 구독합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Content-Type: `application/json` (`QueryRequest`)

```json
{
  "allow_web_search": false,
  "model": "gpt-5-nano",
  "provider": "openai",
  "question": "검색 인덱싱은 어떻게 동작하나요?"
}
```

#### 5. Response body

- HTTP `202`: run 시작됨
- Content-Type: `*/*` (`QueryRunCreateResponse`)

```json
{
  "request_id": "query_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "status": "pending"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 (질문이 비어 있는 경우) | `ErrorResponse` |
| `404` | 세션 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/query/runs" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"allow_web_search":false,"model":"gpt-5-nano","provider":"openai","question":"검색 인덱싱은 어떻게 동작하나요?"}'
```

```json
{
  "request_id": "query_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "status": "pending"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/query/controller/QueryController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: createRun`)

## Query Runs

### GET /api/query/runs/{requestId}

#### 1. Method + Path

`GET /api/query/runs/{requestId}`

#### 2. 목적

비동기 질의의 현재 상태와 완료 결과 또는 오류 정보를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `requestId` | `string` | 예 | 비동기 질의 요청 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 상태 조회 성공
- Content-Type: `*/*` (`QueryRunStatusResponse`)

```json
{
  "error": "string",
  "model": "gpt-5-nano",
  "provider": "openai",
  "request_id": "query_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "result": {
    "assistant_message": {
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "string",
      "role": "assistant",
      "status": "completed"
    },
    "error_code": "web_search_unavailable",
    "evidence_snippets": [
      {
        "rank": 0,
        "source_block_ids": [
          "string"
        ],
        "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
        "source_refs": [
          {
            "source_block_id": "string",
            "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
          }
        ],
        "text": "string"
      }
    ],
    "graph_context": {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "related",
          "role": "string",
          "score": 0.72,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        {
          "depth": 1,
          "id": "string",
          "page_type": "Concept",
          "relevance_score": 0.87,
          "role": "string",
          "slug": "search-indexing",
          "title": "검색 인덱싱"
        }
      ]
    },
    "related_pages": [
      {
        "depth": 1,
        "id": "string",
        "page_type": "Concept",
        "relevance_score": 0.87,
        "role": "string",
        "slug": "search-indexing",
        "title": "검색 인덱싱"
      }
    ],
    "result_count": 5,
    "traversal_paths": [
      {
        "edges": [
          {
            "from_page_id": "string",
            "link_type": "related",
            "role": "string",
            "score": 0.72,
            "to_page_id": "string"
          }
        ],
        "nodes": [
          "string"
        ],
        "path_id": "string",
        "role": "string",
        "score": 0.72,
        "stop_reason": "string",
        "used_for_answer": true
      }
    ],
    "user_message": {
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "string",
      "role": "assistant",
      "status": "completed"
    },
    "web_search_executed": false,
    "web_search_requested": false
  },
  "status": "completed",
  "web_search_enabled": false
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 질의 실행 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/query/runs/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "error": "string",
  "model": "gpt-5-nano",
  "provider": "openai",
  "request_id": "query_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "result": {
    "assistant_message": {
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "string",
      "role": "assistant",
      "status": "completed"
    },
    "error_code": "web_search_unavailable",
    "evidence_snippets": [
      {
        "rank": 0,
        "source_block_ids": [
          "string"
        ],
        "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
        "source_refs": [
          {
            "source_block_id": "string",
            "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
          }
        ],
        "text": "string"
      }
    ],
    "graph_context": {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "related",
          "role": "string",
          "score": 0.72,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        {
          "depth": 1,
          "id": "string",
          "page_type": "Concept",
          "relevance_score": 0.87,
          "role": "string",
          "slug": "search-indexing",
          "title": "검색 인덱싱"
        }
      ]
    },
    "related_pages": [
      {
        "depth": 1,
        "id": "string",
        "page_type": "Concept",
        "relevance_score": 0.87,
        "role": "string",
        "slug": "search-indexing",
        "title": "검색 인덱싱"
      }
    ],
    "result_count": 5,
    "traversal_paths": [
      {
        "edges": [
          {
            "from_page_id": "string",
            "link_type": "related",
            "role": "string",
            "score": 0.72,
            "to_page_id": "string"
          }
        ],
        "nodes": [
          "string"
        ],
        "path_id": "string",
        "role": "string",
        "score": 0.72,
        "stop_reason": "string",
        "used_for_answer": true
      }
    ],
    "user_message": {
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "string",
      "role": "assistant",
      "status": "completed"
    },
    "web_search_executed": false,
    "web_search_requested": false
  },
  "status": "completed",
  "web_search_enabled": false
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/query/controller/QueryRunController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: getRun_1`)

### GET /api/query/runs/{requestId}/events

#### 1. Method + Path

`GET /api/query/runs/{requestId}/events`

#### 2. 목적

비동기 질의의 진행 상황과 최종 결과를 Server-Sent Events로 전달합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `requestId` | `string` | 예 | 비동기 질의 요청 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: SSE 구독 시작
- Content-Type: `text/event-stream`

```json
string
```

전달하는 이벤트는 세 가지다.

| event | 의미 | payload |
|---|---|---|
| `query.log` | AI worker가 단계마다 발행한 진행 상황을 중계 | `request_id`, `sequence`, `received_at`, `stage`, `message`, `data` |
| `query.completed` | 최종 결과 반영 완료 | `request_id`, `status` |
| `query.failed` | 실패 확정 | `request_id`, `status`, `error` |

- 구독 시점 이전 이벤트는 Redis buffer에서 최대 200건까지 재생한다. `sequence`가 뒤로 가는 이벤트는 전달하지 않는다.
- `query.log`는 화면 피드백 용도라 유실을 허용한다. 중계가 실패해도 로그만 남기고 최종 결과 처리를 막지 않는다.
- 같은 진행 이벤트가 재전송돼도 `event_id`를 Redis에서 선점해 한 번만 전달한다.

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 질의 실행 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/query/runs/<value>/events" \
  -H 'Authorization: Bearer <access_token>'
```

```json
string
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/query/controller/QueryRunController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: subscribe`)

## Skills

### GET /api/workspaces/{workspace_id}/skills

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/skills`

#### 2. 목적

현재 사용자가 사용할 수 있는 워크스페이스 Skill 목록을 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 목록 조회 성공
- Content-Type: `*/*`

```json
[
  {
    "enabled_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "meeting-notes",
      "status": "published",
      "version": 3
    },
    "id": "string",
    "latest_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "meeting-notes",
      "status": "published",
      "version": 3
    },
    "owner_user_id": "string",
    "scope_type": "personal",
    "slug": "meeting-notes",
    "status": "published",
    "workspace_id": "string"
  }
]
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 요청 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills" \
  -H 'Authorization: Bearer <access_token>'
```

```json
[
  {
    "enabled_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "meeting-notes",
      "status": "published",
      "version": 3
    },
    "id": "string",
    "latest_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "meeting-notes",
      "status": "published",
      "version": 3
    },
    "owner_user_id": "string",
    "scope_type": "personal",
    "slug": "meeting-notes",
    "status": "published",
    "workspace_id": "string"
  }
]
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: list_2`)

### POST /api/workspaces/{workspace_id}/skills/author

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/skills/author`

#### 2. 목적

자연어 요구를 바탕으로 저장 전 Skill 초안을 생성합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`SkillAuthoringRequest`)

```json
{
  "authoring_mode": "enhance",
  "description": "string",
  "instruction": "string",
  "name": "meeting-notes",
  "reference_document_ids": [
    "string"
  ],
  "scope_type": "personal"
}
```

#### 5. Response body

- HTTP `200`: 초안 작성 성공
- Content-Type: `*/*`

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": "string",
  "name": "string",
  "question": "string",
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "version_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 | `ErrorResponse` |
| `404` | 워크스페이스 또는 참조 문서를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 이름 또는 버전 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `413` | 참조 문서 또는 요청 본문이 너무 큼 | `없음` |
| `422` | Skill 요청 검증 실패 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/author" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"authoring_mode":"enhance","description":"<value>","instruction":"<value>","name":"meeting-notes","reference_document_ids":["<value>"],"scope_type":"personal"}'
```

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": "string",
  "name": "string",
  "question": "string",
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: author`)

### POST /api/workspaces/{workspace_id}/skills/author/publish

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/skills/author/publish`

#### 2. 목적

작성된 Skill 정의를 검토 후 게시합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`SkillPublishRequest`)

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "name": "meeting-notes",
  "scope_type": "personal"
}
```

#### 5. Response body

- HTTP `200`: 게시 성공
- Content-Type: `*/*`

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": "string",
  "name": "string",
  "question": "string",
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "version_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Skill 정의 | `ErrorResponse` |
| `404` | 워크스페이스 또는 참조 문서를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 이름 또는 버전 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `413` | 요청 본문이 너무 큼 | `없음` |
| `422` | Skill 요청 검증 실패 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/author/publish" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"description":"<value>","instructions_markdown":"<value>","name":"meeting-notes","scope_type":"personal"}'
```

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": "string",
  "name": "string",
  "question": "string",
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: publish`)

### GET /api/workspaces/{workspace_id}/skills/{skill_id}

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/skills/{skill_id}`

#### 2. 목적

Skill의 현재 정의와 실행 설정을 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `skill_id` | `string` | 예 | 조회할 Skill ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 상세 조회 성공
- Content-Type: `*/*` (`SkillResponse`)

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "status": "published",
    "version": 3
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "status": "published",
    "version": 3
  },
  "owner_user_id": "string",
  "scope_type": "personal",
  "slug": "meeting-notes",
  "status": "published",
  "workspace_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | Skill 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 요청 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "status": "published",
    "version": 3
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "status": "published",
    "version": 3
  },
  "owner_user_id": "string",
  "scope_type": "personal",
  "slug": "meeting-notes",
  "status": "published",
  "workspace_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: get_1`)

### PATCH /api/workspaces/{workspace_id}/skills/{skill_id}

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}/skills/{skill_id}`

#### 2. 목적

Skill의 정의를 수정합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `skill_id` | `string` | 예 | 수정할 Skill ID |

- Content-Type: `application/json` (`SkillUpdateRequest`)

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "name": "meeting-notes"
}
```

#### 5. Response body

- HTTP `200`: 수정 성공
- Content-Type: `*/*`

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": "string",
  "name": "string",
  "question": "string",
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "version_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Skill 정의 | `ErrorResponse` |
| `404` | Skill 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 이름 또는 버전 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `413` | 요청 본문이 너무 큼 | `없음` |
| `422` | Skill 요청 검증 실패 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
curl -X PATCH "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/<value>" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"description":"<value>","instructions_markdown":"<value>","name":"meeting-notes"}'
```

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": "string",
  "name": "string",
  "question": "string",
  "scope_type": "string",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "string",
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: update_1`)

### POST /api/workspaces/{workspace_id}/skills/{skill_id}/disable

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/skills/{skill_id}/disable`

#### 2. 목적

Skill을 Agent 실행 대상에서 제외합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `skill_id` | `string` | 예 | 비활성화할 Skill ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 비활성화 성공
- Content-Type: `*/*` (`SkillResponse`)

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "status": "published",
    "version": 3
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "status": "published",
    "version": 3
  },
  "owner_user_id": "string",
  "scope_type": "personal",
  "slug": "meeting-notes",
  "status": "published",
  "workspace_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | Skill 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 요청 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/<value>/disable" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "status": "published",
    "version": 3
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "status": "published",
    "version": 3
  },
  "owner_user_id": "string",
  "scope_type": "personal",
  "slug": "meeting-notes",
  "status": "published",
  "workspace_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: disable`)

### POST /api/workspaces/{workspace_id}/skills/{skill_id}/enable

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/skills/{skill_id}/enable`

#### 2. 목적

Skill을 Agent 실행 대상에 포함합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `skill_id` | `string` | 예 | 활성화할 Skill ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 활성화 성공
- Content-Type: `*/*` (`SkillResponse`)

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "status": "published",
    "version": 3
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "status": "published",
    "version": 3
  },
  "owner_user_id": "string",
  "scope_type": "personal",
  "slug": "meeting-notes",
  "status": "published",
  "workspace_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | Skill 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | Skill 요청 충돌 | `ErrorResponse` |
| `410` | Skill이 더 이상 유효하지 않음 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/skills/<value>/enable" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "status": "published",
    "version": 3
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "meeting-notes",
    "status": "published",
    "version": 3
  },
  "owner_user_id": "string",
  "scope_type": "personal",
  "slug": "meeting-notes",
  "status": "published",
  "workspace_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/skill/controller/SkillController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: enable`)

## Wiki

### GET /api/workspaces/{workspace_id}/wiki/graph

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/wiki/graph`

#### 2. 목적

모든 Wiki 노드(pages)와 엣지(links)를 반환합니다. 중앙 그래프 렌더링과 답변 후 하이라이트에 사용됩니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 그래프 조회 성공
- Content-Type: `*/*` (`WikiGraphResponse`)

```json
{
  "edges": [
    {
      "confidence": 0.87,
      "from_page_id": "string",
      "label": "string",
      "link_type": "related",
      "to_page_id": "string"
    }
  ],
  "nodes": [
    {
      "id": "string",
      "page_type": "Concept",
      "slug": "search-indexing",
      "source_document": {
        "filename": "설계문서.pdf",
        "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
      },
      "status": "published",
      "summary": "string",
      "title": "검색 인덱싱"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `500` | 서버 내부 오류 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/graph" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "edges": [
    {
      "confidence": 0.87,
      "from_page_id": "string",
      "label": "string",
      "link_type": "related",
      "to_page_id": "string"
    }
  ],
  "nodes": [
    {
      "id": "string",
      "page_type": "Concept",
      "slug": "search-indexing",
      "source_document": {
        "filename": "설계문서.pdf",
        "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
      },
      "status": "published",
      "summary": "string",
      "title": "검색 인덱싱"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/wiki/controller/WikiController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: getGraph`)

### GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}`

#### 2. 목적

특정 Wiki 페이지의 상세 정보를 반환합니다. source_documents와 related_pages를 포함합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `wiki_page_id` | `string` | 예 | Wiki 페이지 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 페이지 조회 성공
- Content-Type: `*/*` (`WikiPageDetailResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "string",
  "markdown": "string",
  "markdown_uri": "string",
  "page_type": "Concept",
  "related_pages": [
    {
      "confidence": 0.87,
      "id": "string",
      "label": "string",
      "link_type": "related",
      "page_type": "Concept",
      "slug": "inverted-index",
      "title": "역색인"
    }
  ],
  "slug": "search-indexing",
  "source_documents": [
    {
      "confidence": 0.87,
      "filename": "설계문서.pdf",
      "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "relation_type": "string",
      "source_uri": "string"
    }
  ],
  "status": "published",
  "summary": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 페이지를 찾을 수 없음 | `ErrorResponse` |
| `500` | 서버 내부 오류 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/pages/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "string",
  "markdown": "string",
  "markdown_uri": "string",
  "page_type": "Concept",
  "related_pages": [
    {
      "confidence": 0.87,
      "id": "string",
      "label": "string",
      "link_type": "related",
      "page_type": "Concept",
      "slug": "inverted-index",
      "title": "역색인"
    }
  ],
  "slug": "search-indexing",
  "source_documents": [
    {
      "confidence": 0.87,
      "filename": "설계문서.pdf",
      "id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "relation_type": "string",
      "source_uri": "string"
    }
  ],
  "status": "published",
  "summary": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/wiki/controller/WikiController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: getPage`)

### GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/diff

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/diff`

#### 2. 목적

두 revision 사이의 diff를 반환합니다. 저장된 본문을 읽어 요청 시점에 계산하며, 사용자가 펼칠 때만 호출됩니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `wiki_page_id` | `string` | 예 | Wiki 페이지 ID |
| query | `from` | `integer` | 예 | 비교 기준 revision |
| query | `to` | `integer` | 예 | 비교 대상 revision |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`WikiPageDiffResponse`)

```json
{
  "additions": 12,
  "deletions": 4,
  "from_revision": 2,
  "hunks": [
    {
      "lines": [
        {
          "content": "string",
          "new_line": 10,
          "old_line": 10,
          "type": "string"
        }
      ],
      "new_lines": 5,
      "new_start": 10,
      "old_lines": 3,
      "old_start": 10
    }
  ],
  "page_id": "string",
  "to_revision": 3
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 페이지 또는 버전을 찾을 수 없음 | `ErrorResponse` |
| `422` | 두 본문의 차이가 너무 커서 비교할 수 없음 | `ErrorResponse` |

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
- 필터링: `from`, `to`

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/pages/<value>/diff?from=1&to=1" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "additions": 12,
  "deletions": 4,
  "from_revision": 2,
  "hunks": [
    {
      "lines": [
        {
          "content": "string",
          "new_line": 10,
          "old_line": 10,
          "type": "string"
        }
      ],
      "new_lines": 5,
      "new_start": 10,
      "old_lines": 3,
      "old_start": 10
    }
  ],
  "page_id": "string",
  "to_revision": 3
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/wiki/controller/WikiController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: diff`)

### PATCH /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/rename

#### 1. Method + Path

`PATCH /api/workspaces/{workspace_id}/wiki/pages/{wiki_page_id}/rename`

#### 2. 목적

Wiki 페이지 제목을 변경합니다. update_slug=true이면 slug도 재생성하며 중복 여부를 검증합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `wiki_page_id` | `string` | 예 | Wiki 페이지 ID |

- Content-Type: `application/json` (`WikiPageRenameRequest`)

```json
{
  "title": "검색 인덱싱",
  "update_slug": false
}
```

#### 5. Response body

- HTTP `200`: 이름 변경 성공
- Content-Type: `*/*` (`WikiPageRenameResponse`)

```json
{
  "id": "string",
  "page_type": "Concept",
  "previous_slug": "indexing",
  "previous_title": "인덱싱",
  "slug": "search-indexing",
  "slug_updated": false,
  "title": "검색 인덱싱",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 유효하지 않은 제목 | `ErrorResponse` |
| `404` | 페이지를 찾을 수 없음 | `ErrorResponse` |
| `409` | slug 충돌 | `ErrorResponse` |

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
curl -X PATCH "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/pages/<value>/rename" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"title":"검색 인덱싱","update_slug":false}'
```

```json
{
  "id": "string",
  "page_type": "Concept",
  "previous_slug": "indexing",
  "previous_title": "인덱싱",
  "slug": "search-indexing",
  "slug_updated": false,
  "title": "검색 인덱싱",
  "updated_at": "2026-08-13T04:25:24.371948Z"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/wiki/controller/WikiController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: rename`)

## Wiki Maintenance

### POST /api/workspaces/{workspace_id}/wiki/maintenance/lint

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/wiki/maintenance/lint`

#### 2. 목적

워크스페이스 Wiki 정합성 검사 실행을 비동기 대기열에 등록합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`WikiLintRequest`)

```json
{
  "dry_run": true,
  "materialize_promotions": false
}
```

#### 5. Response body

- HTTP `202`: Wiki 정합성 검사 실행이 대기열에 등록됨
- Content-Type: `*/*` (`WikiLintResponse`)

```json
{
  "operation_id": "string",
  "run_id": "string",
  "status": "queued"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 검사 옵션 | `ErrorResponse` |
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/maintenance/lint" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"dry_run":true,"materialize_promotions":false}'
```

```json
{
  "operation_id": "string",
  "run_id": "string",
  "status": "queued"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/wikimaintenance/controller/WikiMaintenanceController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: lint`)

### GET /api/workspaces/{workspace_id}/wiki/maintenance/runs/{run_id}

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/wiki/maintenance/runs/{run_id}`

#### 2. 목적

실행 중이거나 완료된 Wiki 정합성 검사 결과를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `run_id` | `string` | 예 | 조회할 검사 실행 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 결과 조회 성공
- Content-Type: `*/*` (`JsonNode`)

```json
{
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 검사 실행 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/maintenance/runs/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/wikimaintenance/controller/WikiMaintenanceController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: run`)

### GET /api/workspaces/{workspace_id}/wiki/maintenance/status

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/wiki/maintenance/status`

#### 2. 목적

워크스페이스 Wiki 유지보수 작업의 현재 상태를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 상태 조회 성공
- Content-Type: `*/*` (`WikiMaintenanceStatusResponse`)

```json
{
  "last_lint_at": "2026-08-13T04:25:24.371948Z",
  "last_wiki_change_at": "2026-08-13T04:25:24.371948Z",
  "needs_lint": true
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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki/maintenance/status" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "last_lint_at": "2026-08-13T04:25:24.371948Z",
  "last_wiki_change_at": "2026-08-13T04:25:24.371948Z",
  "needs_lint": true
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/wikimaintenance/controller/WikiMaintenanceController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: status`)

## Wiki Schema

### GET /api/workspaces/{workspace_id}/wiki-schema/active

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/wiki-schema/active`

#### 2. 목적

활성 Schema가 없으면 null을 포함한 200 응답을 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 활성 Schema 조회 성공(null 가능)
- Content-Type: `*/*` (`WikiSchemaResponse`)

```json
{
  "activated_at": "string",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": false,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "unclear",
      "text": "string"
    }
  ],
  "name": "설계 문서 스키마",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "v1"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `없음` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

```json
{
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
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki-schema/active" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "activated_at": "string",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": false,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "unclear",
      "text": "string"
    }
  ],
  "name": "설계 문서 스키마",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "v1"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/wikischema/controller/WikiSchemaController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: getActive`)

### POST /api/workspaces/{workspace_id}/wiki-schema/drafts

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/wiki-schema/drafts`

#### 2. 목적

검토할 Wiki 생성 규칙을 초안 상태로 저장합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`WikiSchemaDraftRequest`)

```json
{
  "name": "설계 문서 스키마",
  "rawMarkdown": "# 설계\n\n## 구성요소"
}
```

#### 5. Response body

- HTTP `200`: 초안 생성 성공
- Content-Type: `*/*` (`WikiSchemaDraftResponse`)

```json
{
  "wiki_schema": {
    "activated_at": "string",
    "created_at": "2026-08-13T04:25:24.371948Z",
    "fragments": {
      "concept_markdown": "string",
      "edit_markdown": "string",
      "global_markdown": "string",
      "ingest_markdown": "string",
      "query_markdown": "string",
      "template_markdown": "string"
    },
    "has_blocked_issues": false,
    "id": "string",
    "issues": [
      {
        "category": "string",
        "reason": "string",
        "section": "string",
        "severity": "unclear",
        "text": "string"
      }
    ],
    "name": "설계 문서 스키마",
    "preview_markdown": "string",
    "raw_markdown": "string",
    "schema_version": "v1"
  }
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Schema 정의 | `없음` |
| `404` | 워크스페이스를 찾을 수 없음 | `없음` |
| `422` | Schema 요청 검증 실패 | `없음` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

```json
{
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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki-schema/drafts" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"name":"설계 문서 스키마","rawMarkdown":"# 설계\n\n## 구성요소"}'
```

```json
{
  "wiki_schema": {
    "activated_at": "string",
    "created_at": "2026-08-13T04:25:24.371948Z",
    "fragments": {
      "concept_markdown": "string",
      "edit_markdown": "string",
      "global_markdown": "string",
      "ingest_markdown": "string",
      "query_markdown": "string",
      "template_markdown": "string"
    },
    "has_blocked_issues": false,
    "id": "string",
    "issues": [
      {
        "category": "string",
        "reason": "string",
        "section": "string",
        "severity": "unclear",
        "text": "string"
      }
    ],
    "name": "설계 문서 스키마",
    "preview_markdown": "string",
    "raw_markdown": "string",
    "schema_version": "v1"
  }
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/wikischema/controller/WikiSchemaController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: createDraft`)

### POST /api/workspaces/{workspace_id}/wiki-schema/preview

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/wiki-schema/preview`

#### 2. 목적

Schema 규칙을 저장하지 않고 적용해 예상 Wiki 구조를 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`WikiSchemaPreviewRequest`)

```json
{
  "rawMarkdown": "# 설계\n\n## 구성요소"
}
```

#### 5. Response body

- HTTP `200`: 미리보기 생성 성공
- Content-Type: `*/*` (`WikiSchemaPreviewResponse`)

```json
{
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": false,
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "unclear",
      "text": "string"
    }
  ],
  "preview_markdown": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 Schema 또는 입력 | `없음` |
| `404` | 워크스페이스를 찾을 수 없음 | `없음` |
| `422` | Schema 요청 검증 실패 | `없음` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

```json
{
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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki-schema/preview" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"rawMarkdown":"# 설계\n\n## 구성요소"}'
```

```json
{
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": false,
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "unclear",
      "text": "string"
    }
  ],
  "preview_markdown": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/wikischema/controller/WikiSchemaController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: preview`)

### POST /api/workspaces/{workspace_id}/wiki-schema/{schema_id}/activate

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/wiki-schema/{schema_id}/activate`

#### 2. 목적

선택한 Wiki Schema ID의 활성화를 요청합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `schema_id` | `string` | 예 | 활성화할 Wiki Schema ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 활성화 성공
- Content-Type: `*/*` (`WikiSchemaResponse`)

```json
{
  "activated_at": "string",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": false,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "unclear",
      "text": "string"
    }
  ],
  "name": "설계 문서 스키마",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "v1"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | Schema 또는 워크스페이스를 찾을 수 없음 | `없음` |
| `503` | llmPipeline 사용 불가 | `ErrorResponse` |

```json
{
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
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/wiki-schema/<value>/activate" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "activated_at": "string",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": false,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "unclear",
      "text": "string"
    }
  ],
  "name": "설계 문서 스키마",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "v1"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/wikischema/controller/WikiSchemaController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: activate`)

## agent-tool-controller

### POST /internal/agent/tools/execute/{tool_name}

#### 1. Method + Path

`POST /internal/agent/tools/execute/{tool_name}`

#### 2. 목적

목적 설명 없음

#### 3. Auth 필요 여부

- 필요
- `X-Agent-Service-Token`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `tool_name` | `string` | 예 | - |

- Content-Type: `application/json` (`AgentToolExecuteRequest`)

```json
{
  "arguments": {
  },
  "idempotency_key": "string",
  "operation_hash": "string",
  "operation_id": "string",
  "plan_id": "string",
  "plan_version": 1,
  "run_id": "string",
  "user_id": "string",
  "workspace_id": "string"
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

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/internal/agent/tools/execute/<value>" \
  -H 'Content-Type: application/json' \
  --data '{"arguments":{},"idempotency_key":"<value>","operation_hash":"<value>","operation_id":"<value>","plan_id":"<value>","plan_version":1,"run_id":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/agent/controller/AgentToolController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: execute`)

### POST /internal/agent/tools/read/{tool_name}

#### 1. Method + Path

`POST /internal/agent/tools/read/{tool_name}`

#### 2. 목적

목적 설명 없음

#### 3. Auth 필요 여부

- 필요
- `X-Agent-Service-Token`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `tool_name` | `string` | 예 | - |

- Content-Type: `application/json` (`AgentToolReadRequest`)

```json
{
  "arguments": {
  },
  "run_id": "string",
  "user_id": "string",
  "workspace_id": "string"
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

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/internal/agent/tools/read/<value>" \
  -H 'Content-Type: application/json' \
  --data '{"arguments":{},"run_id":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/agent/controller/AgentToolController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: read`)

## internal-document-controller

### GET /internal/documents/{document_id}/pipeline-source

#### 1. Method + Path

`GET /internal/documents/{document_id}/pipeline-source`

#### 2. 목적

목적 설명 없음

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `document_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `string` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*`

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/internal/documents/<value>/pipeline-source" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/InternalDocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: findPipelineSource`)

### POST /internal/workspaces/{workspace_id}/initial-note

#### 1. Method + Path

`POST /internal/workspaces/{workspace_id}/initial-note`

#### 2. 목적

목적 설명 없음

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `string` | 아니요 | - |

- Content-Type: `application/json` (`InitialNoteRequest`)

```json
{
  "user_id": "string"
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

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/internal/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/initial-note" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"user_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/document/controller/InternalDocumentController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: createInitialNote`)

## internal-wiki-contribution-controller

### POST /internal/wiki/contributions

#### 1. Method + Path

`POST /internal/wiki/contributions`

#### 2. 목적

목적 설명 없음

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `string` | 아니요 | - |

- Content-Type: `application/json` (`ContributionRequest`)

```json
{
  "page_ids": [
    "string"
  ],
  "workspace_id": "string"
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

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/internal/wiki/contributions" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"page_ids":["<value>"],"workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/wiki/controller/InternalWikiContributionController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: find`)

## skill-reference-controller

### POST /internal/agent/skill-authoring/references/read

#### 1. Method + Path

`POST /internal/agent/skill-authoring/references/read`

#### 2. 목적

목적 설명 없음

#### 3. Auth 필요 여부

- 필요
- `X-Agent-Service-Token`을 검증한다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`SkillReferenceReadRequest`)

```json
{
  "document_id": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `*/*` (`SkillReferenceReadResponse`)

```json
{
  "document_role": "string",
  "markdown": "string"
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/internal/agent/skill-authoring/references/read" \
  -H 'Content-Type: application/json' \
  --data '{"document_id":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "document_role": "string",
  "markdown": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/agent/controller/AgentTurnController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: read_1`)


# pipeline

## agent

### POST /agent/turn

#### 1. Method + Path

`POST /agent/turn`

#### 2. 목적

Handle Agent Turn

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`AgentTurnRequestBody`)

```json
{
  "active_markdown_context": {
    "markdown": "string",
    "target": null
  },
  "allow_web_search": true,
  "conversation_context": {
    "pending_skill_proposal": null,
    "recent_conversation_summary": null,
    "recent_messages": [
      {
        "content": "string",
        "role": "string"
      }
    ],
    "reference_context": null
  },
  "message": "string",
  "model": "string",
  "output_language": "ko",
  "provider": "string",
  "response_length": "concise",
  "skill_authoring_mode": "preserve",
  "skill_draft_excluded_literals": [
    "string"
  ]
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`AgentTurnResponse`)

```json
{
  "action": "chat_answer",
  "chat": {
    "answer": "string",
    "error_code": null,
    "evidence_snippets": [
      {
        "rank": 0,
        "source_block_ids": [
          "string"
        ],
        "source_document_id": "string",
        "source_refs": [
          {
            "source_block_id": "string",
            "source_document_id": "string"
          }
        ],
        "text": "string"
      }
    ],
    "graph_context": {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "string",
          "role": "string",
          "score": 0.0,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        {
          "depth": 0,
          "id": "string",
          "page_type": "string",
          "relevance_score": 0.0,
          "role": "string",
          "slug": "string",
          "title": "string"
        }
      ]
    },
    "related_pages": [
      {
        "depth": 0,
        "id": "string",
        "page_type": "string",
        "relevance_score": 0.0,
        "role": "string",
        "slug": "string",
        "title": "string"
      }
    ],
    "result_count": 1,
    "traversal_paths": [
      {
        "edges": [
          {
            "from_page_id": "string",
            "link_type": "string",
            "role": "string",
            "score": 0.0,
            "to_page_id": "string"
          }
        ],
        "nodes": [
          "string"
        ],
        "path_id": "string",
        "role": "string",
        "score": 0.0,
        "stop_reason": "string",
        "used_for_answer": false
      }
    ],
    "updated_conversation_summary": null,
    "web_search_executed": true,
    "web_search_requested": true
  },
  "edit": {
    "actual_target": {
      "end_line": 0,
      "start_line": 0,
      "type": "string"
    },
    "changed": true,
    "operation": "replace",
    "replacement_markdown": "string",
    "requested_target": {
      "end_line": 0,
      "start_line": 0,
      "type": "string"
    },
    "scope_expanded": true,
    "summary": "string"
  },
  "generated_markdown": {
    "markdown": "string",
    "summary": "string",
    "title": "string"
  },
  "message": "string",
  "route": {
    "action": "chat_answer",
    "confidence": 1,
    "edit_goal": "string",
    "reason": "string",
    "selected_skill_id": "string",
    "skill_candidates": [
      "string"
    ]
  },
  "run_id": "string",
  "run_status": "string",
  "skill_authoring": {
    "description": null,
    "instructions_markdown": null,
    "issues": [
      {
      }
    ],
    "name": null,
    "question": null,
    "scope_type": null,
    "skill_id": null,
    "skill_markdown": null,
    "status": "clarification_required",
    "version_id": null
  },
  "skill_candidates": [
    {
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "name": "string",
      "version_id": "string"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/agent/turn" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"active_markdown_context":{"markdown":"<value>","target":null},"allow_web_search":true,"conversation_context":{"pending_skill_proposal":null,"recent_conversation_summary":null,"recent_messages":[null],"reference_context":null},"message":"<value>","model":"<value>","output_language":"ko","provider":"<value>","response_length":"concise","skill_authoring_mode":"preserve","skill_draft_excluded_literals":["<value>"]}'
```

```json
{
  "action": "chat_answer",
  "chat": {
    "answer": "string",
    "error_code": null,
    "evidence_snippets": [
      {
        "rank": 0,
        "source_block_ids": [
          "string"
        ],
        "source_document_id": "string",
        "source_refs": [
          {
            "source_block_id": "string",
            "source_document_id": "string"
          }
        ],
        "text": "string"
      }
    ],
    "graph_context": {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "string",
          "role": "string",
          "score": 0.0,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        {
          "depth": 0,
          "id": "string",
          "page_type": "string",
          "relevance_score": 0.0,
          "role": "string",
          "slug": "string",
          "title": "string"
        }
      ]
    },
    "related_pages": [
      {
        "depth": 0,
        "id": "string",
        "page_type": "string",
        "relevance_score": 0.0,
        "role": "string",
        "slug": "string",
        "title": "string"
      }
    ],
    "result_count": 1,
    "traversal_paths": [
      {
        "edges": [
          {
            "from_page_id": "string",
            "link_type": "string",
            "role": "string",
            "score": 0.0,
            "to_page_id": "string"
          }
        ],
        "nodes": [
          "string"
        ],
        "path_id": "string",
        "role": "string",
        "score": 0.0,
        "stop_reason": "string",
        "used_for_answer": false
      }
    ],
    "updated_conversation_summary": null,
    "web_search_executed": true,
    "web_search_requested": true
  },
  "edit": {
    "actual_target": {
      "end_line": 0,
      "start_line": 0,
      "type": "string"
    },
    "changed": true,
    "operation": "replace",
    "replacement_markdown": "string",
    "requested_target": {
      "end_line": 0,
      "start_line": 0,
      "type": "string"
    },
    "scope_expanded": true,
    "summary": "string"
  },
  "generated_markdown": {
    "markdown": "string",
    "summary": "string",
    "title": "string"
  },
  "message": "string",
  "route": {
    "action": "chat_answer",
    "confidence": 1,
    "edit_goal": "string",
    "reason": "string",
    "selected_skill_id": "string",
    "skill_candidates": [
      "string"
    ]
  },
  "run_id": "string",
  "run_status": "string",
  "skill_authoring": {
    "description": null,
    "instructions_markdown": null,
    "issues": [
      {
      }
    ],
    "name": null,
    "question": null,
    "scope_type": null,
    "skill_id": null,
    "skill_markdown": null,
    "status": "clarification_required",
    "version_id": null
  },
  "skill_candidates": [
    {
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "name": "string",
      "version_id": "string"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: handle_agent_turn_agent_turn_post`)

## internal-agent-runs

### POST /internal/agent/runs/artifacts/list

#### 1. Method + Path

`POST /internal/agent/runs/artifacts/list`

#### 2. 목적

List Agent Artifacts

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`AgentArtifactListRequest`)

```json
{
  "run_id": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response List Agent Artifacts Internal Agent Runs Artifacts List Post`)

```json
[
  {
    "base_version": 1,
    "content_hash": "string",
    "document_id": "string",
    "id": "string",
    "purpose": "string",
    "target": {
    }
  }
]
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/internal/agent/runs/artifacts/list" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"run_id":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
[
  {
    "base_version": 1,
    "content_hash": "string",
    "document_id": "string",
    "id": "string",
    "purpose": "string",
    "target": {
    }
  }
]
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: list_agent_artifacts_internal_agent_runs_artifacts_list_post`)

### POST /internal/agent/runs/artifacts/register

#### 1. Method + Path

`POST /internal/agent/runs/artifacts/register`

#### 2. 목적

Register Agent Artifact

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`AgentArtifactRegisterRequest`)

```json
{
  "artifact_id": "string",
  "base_version": 1.0,
  "content_hash": "string",
  "document_id": "string",
  "markdown": "string",
  "purpose": "string",
  "run_id": "string",
  "target": {
  },
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`AgentArtifactResponse`)

```json
{
  "base_version": 1,
  "content_hash": "string",
  "document_id": "string",
  "id": "string",
  "purpose": "string",
  "target": {
  }
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/internal/agent/runs/artifacts/register" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"artifact_id":"<value>","base_version":1.0,"content_hash":"<value>","document_id":"<value>","markdown":"<value>","purpose":"<value>","run_id":"<value>","target":{},"user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "base_version": 1,
  "content_hash": "string",
  "document_id": "string",
  "id": "string",
  "purpose": "string",
  "target": {
  }
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: register_agent_artifact_internal_agent_runs_artifacts_register_post`)

### POST /internal/agent/runs/artifacts/resolve

#### 1. Method + Path

`POST /internal/agent/runs/artifacts/resolve`

#### 2. 목적

Resolve Agent Artifact

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`AgentArtifactResolveRequest`)

```json
{
  "artifact_id": "string",
  "base_version": 1.0,
  "content_hash": "string",
  "document_id": "string",
  "purpose": "string",
  "run_id": "string",
  "target": {
  },
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`AgentArtifactResolveResponse`)

```json
{
  "base_version": 1,
  "content_hash": "string",
  "document_id": "string",
  "id": "string",
  "markdown": "string",
  "purpose": "string",
  "target": {
  }
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/internal/agent/runs/artifacts/resolve" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"artifact_id":"<value>","base_version":1.0,"content_hash":"<value>","document_id":"<value>","purpose":"<value>","run_id":"<value>","target":{},"user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "base_version": 1,
  "content_hash": "string",
  "document_id": "string",
  "id": "string",
  "markdown": "string",
  "purpose": "string",
  "target": {
  }
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: resolve_agent_artifact_internal_agent_runs_artifacts_resolve_post`)

### POST /internal/agent/runs/tool-authorizations/execute

#### 1. Method + Path

`POST /internal/agent/runs/tool-authorizations/execute`

#### 2. 목적

Authorize Agent Tool Execute

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`AgentToolExecuteAuthorizationRequest`)

```json
{
  "arguments": {
  },
  "operation_hash": "string",
  "operation_id": "string",
  "plan_id": "string",
  "plan_version": 1.0,
  "run_id": "string",
  "tool_name": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `204`: Successful Response
- Body: 없음

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/internal/agent/runs/tool-authorizations/execute" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"arguments":{},"operation_hash":"<value>","operation_id":"<value>","plan_id":"<value>","plan_version":1.0,"run_id":"<value>","tool_name":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: authorize_agent_tool_execute_internal_agent_runs_tool_authorizations_execute_post`)

### POST /internal/agent/runs/tool-authorizations/read

#### 1. Method + Path

`POST /internal/agent/runs/tool-authorizations/read`

#### 2. 목적

Authorize Agent Tool Read

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`AgentToolReadAuthorizationRequest`)

```json
{
  "run_id": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `204`: Successful Response
- Body: 없음

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/internal/agent/runs/tool-authorizations/read" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"run_id":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: authorize_agent_tool_read_internal_agent_runs_tool_authorizations_read_post`)

### GET /internal/agent/runs/{run_id}

#### 1. Method + Path

`GET /internal/agent/runs/{run_id}`

#### 2. 목적

Get Markdown Agent Run

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `run_id` | `string` | 예 | - |
| query | `workspace_id` | `string` | 예 | - |
| query | `user_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`MarkdownAgentRunStatusResponse`)

```json
{
  "apply_operation_id": "string",
  "base_version": 1,
  "document_id": "string",
  "error_code": "string",
  "id": "string",
  "result": {
  },
  "status": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: `workspace_id`, `user_id`

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/internal/agent/runs/<value>?workspace_id=<value>&user_id=<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
  "apply_operation_id": "string",
  "base_version": 1,
  "document_id": "string",
  "error_code": "string",
  "id": "string",
  "result": {
  },
  "status": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_markdown_agent_run_internal_agent_runs__run_id__get`)

## pipeline

### POST /chat-wiki/runs

#### 1. Method + Path

`POST /chat-wiki/runs`

#### 2. 목적

Run Chat Wiki Endpoint

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`ChatWikiRunIn`)

```json
{
  "chat_append_system_prompt": "prompts/chat_semantic_append.system.md",
  "chat_system_prompt": "prompts/chat_semantic_extraction.system.md",
  "concept_page_mode": "auto",
  "concept_resolution_system_prompt": "prompts/concept_resolution.system.md",
  "concept_system_prompt": "prompts/concept_page_generation.system.md",
  "document_id": "string",
  "existing_wiki_dir": "string",
  "input_markdown": "string",
  "input_name": "string",
  "log_callback_url": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`PipelineRunOut`)

```json
{
  "log_path": "string",
  "manifest": {
  },
  "output_dir": "string",
  "run_id": "string",
  "status": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/chat-wiki/runs" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"chat_append_system_prompt":"prompts/chat_semantic_append.system.md","chat_system_prompt":"prompts/chat_semantic_extraction.system.md","concept_page_mode":"auto","concept_resolution_system_prompt":"prompts/concept_resolution.system.md","concept_system_prompt":"prompts/concept_page_generation.system.md","document_id":"<value>","existing_wiki_dir":"<value>","input_markdown":"<value>","input_name":"<value>","log_callback_url":"<value>"}'
```

```json
{
  "log_path": "string",
  "manifest": {
  },
  "output_dir": "string",
  "run_id": "string",
  "status": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: run_chat_wiki_endpoint_chat_wiki_runs_post`)

### POST /pipeline/reingest-runs

#### 1. Method + Path

`POST /pipeline/reingest-runs`

#### 2. 목적

Run Reingest Pipeline Endpoint

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`ReingestRunIn`)

```json
{
  "concept_page_mode": "auto",
  "concept_resolution_system_prompt": "prompts/concept_resolution.system.md",
  "concept_system_prompt": "prompts/concept_page_generation.system.md",
  "document_id": "string",
  "existing_wiki_dir": "string",
  "input_markdown": "string",
  "input_name": "string",
  "log_callback_url": "string",
  "max_eval_attempts": 1,
  "max_packet_chars": 1
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`PipelineRunOut`)

```json
{
  "log_path": "string",
  "manifest": {
  },
  "output_dir": "string",
  "run_id": "string",
  "status": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/pipeline/reingest-runs" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"concept_page_mode":"auto","concept_resolution_system_prompt":"prompts/concept_resolution.system.md","concept_system_prompt":"prompts/concept_page_generation.system.md","document_id":"<value>","existing_wiki_dir":"<value>","input_markdown":"<value>","input_name":"<value>","log_callback_url":"<value>","max_eval_attempts":1,"max_packet_chars":1}'
```

```json
{
  "log_path": "string",
  "manifest": {
  },
  "output_dir": "string",
  "run_id": "string",
  "status": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: run_reingest_pipeline_endpoint_pipeline_reingest_runs_post`)

### POST /pipeline/runs

#### 1. Method + Path

`POST /pipeline/runs`

#### 2. 목적

Run Pipeline Endpoint

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`PipelineRunIn`)

```json
{
  "concept_page_mode": "auto",
  "concept_resolution_system_prompt": "prompts/concept_resolution.system.md",
  "concept_system_prompt": "prompts/concept_page_generation.system.md",
  "document_id": "string",
  "existing_wiki_dir": "string",
  "input_name": "string",
  "log_callback_url": "string",
  "max_eval_attempts": 1,
  "max_packet_chars": 1,
  "mode": "api"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`PipelineRunOut`)

```json
{
  "log_path": "string",
  "manifest": {
  },
  "output_dir": "string",
  "run_id": "string",
  "status": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/pipeline/runs" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"concept_page_mode":"auto","concept_resolution_system_prompt":"prompts/concept_resolution.system.md","concept_system_prompt":"prompts/concept_page_generation.system.md","document_id":"<value>","existing_wiki_dir":"<value>","input_name":"<value>","log_callback_url":"<value>","max_eval_attempts":1,"max_packet_chars":1,"mode":"api"}'
```

```json
{
  "log_path": "string",
  "manifest": {
  },
  "output_dir": "string",
  "run_id": "string",
  "status": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: run_pipeline_endpoint_pipeline_runs_post`)

### GET /pipeline/runs/{run_id}

#### 1. Method + Path

`GET /pipeline/runs/{run_id}`

#### 2. 목적

Get Pipeline Run

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `run_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Get Pipeline Run Pipeline Runs  Run Id  Get`)

```json
{
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/pipeline/runs/<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_pipeline_run_pipeline_runs__run_id__get`)

### GET /pipeline/runs/{run_id}/logs

#### 1. Method + Path

`GET /pipeline/runs/{run_id}/logs`

#### 2. 목적

Get Pipeline Logs

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `run_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `text/plain`

```json
string
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/pipeline/runs/<value>/logs" \
  -H 'X-Internal-Token: <value>'
```

```json
string
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_pipeline_logs_pipeline_runs__run_id__logs_get`)

### GET /wiki/documents/{document_id}/context

#### 1. Method + Path

`GET /wiki/documents/{document_id}/context`

#### 2. 목적

Get Document Wiki Context

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `document_id` | `string` | 예 | - |
| query | `workspace_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Get Document Wiki Context Wiki Documents  Document Id  Context Get`)

```json
{
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: `workspace_id`

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/wiki/documents/<value>/context?workspace_id=<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_document_wiki_context_wiki_documents__document_id__context_get`)

### GET /wiki/graph

#### 1. Method + Path

`GET /wiki/graph`

#### 2. 목적

Get Wiki Graph

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| query | `workspace_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Get Wiki Graph Wiki Graph Get`)

```json
{
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: `workspace_id`

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/wiki/graph?workspace_id=<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_wiki_graph_wiki_graph_get`)

### POST /wiki/ingest-restore-runs

#### 1. Method + Path

`POST /wiki/ingest-restore-runs`

#### 2. 목적

Restore Ingest Operation

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`IngestOperationRestoreIn`)

```json
{
  "cancel_operation_ids": [
    "string"
  ],
  "deleted_pages": [
    "string"
  ],
  "operation_id": "string",
  "rebuild_pages": [
    {
      "keep_contributions": [
        {
          "document_id": "string",
          "operation_id": "string"
        }
      ],
      "page_id": "string"
    }
  ],
  "restore_to_operation_id": "string",
  "source_page": {
    "document_id": "string",
    "page_id": "string"
  },
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Restore Ingest Operation Wiki Ingest Restore Runs Post`)

```json
{
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/wiki/ingest-restore-runs" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"cancel_operation_ids":["<value>"],"deleted_pages":["<value>"],"operation_id":"<value>","rebuild_pages":[{"keep_contributions":[null],"page_id":"<value>"}],"restore_to_operation_id":"<value>","source_page":{"document_id":"<value>","page_id":"<value>"},"workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: restore_ingest_operation_wiki_ingest_restore_runs_post`)

### POST /wiki/lint-restore-runs

#### 1. Method + Path

`POST /wiki/lint-restore-runs`

#### 2. 목적

Restore Lint Operation

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`LintOperationRestoreIn`)

```json
{
  "deleted_pages": [
    "string"
  ],
  "operation_id": "string",
  "rebuild_pages": [
    {
      "keep_contributions": [
        {
          "document_id": "string",
          "operation_id": "string"
        }
      ],
      "page_id": "string"
    }
  ],
  "target_operation_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Restore Lint Operation Wiki Lint Restore Runs Post`)

```json
{
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/wiki/lint-restore-runs" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"deleted_pages":["<value>"],"operation_id":"<value>","rebuild_pages":[{"keep_contributions":[null],"page_id":"<value>"}],"target_operation_id":"<value>","workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: restore_lint_operation_wiki_lint_restore_runs_post`)

### POST /wiki/maintenance/lint

#### 1. Method + Path

`POST /wiki/maintenance/lint`

#### 2. 목적

Lint Wiki Workspace

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`WikiLintIn`)

```json
{
  "dry_run": true,
  "materialize_promotions": true,
  "model": "string",
  "operation_id": "string",
  "provider": "openai",
  "user_id": "local-user",
  "workspace_id": "local-workspace"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`WikiLintOut`)

```json
{
  "active_path": "string",
  "applied_cluster_reconciliation": {
  },
  "applied_reconciliations": [
    {
    }
  ],
  "changed_pages": [
    {
    }
  ],
  "cluster_count": 1,
  "invalid_promotions": [
    {
    }
  ],
  "invalid_relations": [
    {
    }
  ],
  "materialized_promotions": [
    {
    }
  ],
  "materialized_relations": [
    {
    }
  ],
  "merged_promotions": [
    {
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/wiki/maintenance/lint" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"dry_run":true,"materialize_promotions":true,"model":"<value>","operation_id":"<value>","provider":"openai","user_id":"local-user","workspace_id":"local-workspace"}'
```

```json
{
  "active_path": "string",
  "applied_cluster_reconciliation": {
  },
  "applied_reconciliations": [
    {
    }
  ],
  "changed_pages": [
    {
    }
  ],
  "cluster_count": 1,
  "invalid_promotions": [
    {
    }
  ],
  "invalid_relations": [
    {
    }
  ],
  "materialized_promotions": [
    {
    }
  ],
  "materialized_relations": [
    {
    }
  ],
  "merged_promotions": [
    {
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: lint_wiki_workspace_wiki_maintenance_lint_post`)

### POST /wiki/pages/lookup

#### 1. Method + Path

`POST /wiki/pages/lookup`

#### 2. 목적

Lookup Wiki Pages

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`WikiPageLookupIn`)

```json
{
  "page_ids": [
    "string"
  ],
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Lookup Wiki Pages Wiki Pages Lookup Post`)

```json
[
  {
  }
]
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/wiki/pages/lookup" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"page_ids":["<value>"],"workspace_id":"<value>"}'
```

```json
[
  {
  }
]
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: lookup_wiki_pages_wiki_pages_lookup_post`)

### GET /wiki/pages/{page_id}

#### 1. Method + Path

`GET /wiki/pages/{page_id}`

#### 2. 목적

Get Wiki Page

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `page_id` | `string` | 예 | - |
| query | `workspace_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Get Wiki Page Wiki Pages  Page Id  Get`)

```json
{
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: `workspace_id`

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/wiki/pages/<value>?workspace_id=<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_wiki_page_wiki_pages__page_id__get`)

### PATCH /wiki/pages/{page_id}/rename

#### 1. Method + Path

`PATCH /wiki/pages/{page_id}/rename`

#### 2. 목적

Rename Wiki Page

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `page_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`WikiPageRenameIn`)

```json
{
  "title": "string",
  "update_slug": true,
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Rename Wiki Page Wiki Pages  Page Id  Rename Patch`)

```json
{
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X PATCH "$PIPELINE/wiki/pages/<value>/rename" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"title":"<value>","update_slug":true,"user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: rename_wiki_page_wiki_pages__page_id__rename_patch`)

### DELETE /wiki/workspaces/{workspace_id}/documents/{document_id}

#### 1. Method + Path

`DELETE /wiki/workspaces/{workspace_id}/documents/{document_id}`

#### 2. 목적

Delete Document Wiki Data

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `document_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json`

```json
{
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X DELETE "$PIPELINE/wiki/workspaces/<value>/documents/<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: delete_document_wiki_data_wiki_workspaces__workspace_id__documents__document_id__delete`)

### GET /wiki/workspaces/{workspace_id}/last-updated

#### 1. Method + Path

`GET /wiki/workspaces/{workspace_id}/last-updated`

#### 2. 목적

Get Last Wiki Updated

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Get Last Wiki Updated Wiki Workspaces  Workspace Id  Last Updated Get`)

```json
{
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/wiki/workspaces/<value>/last-updated" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_last_wiki_updated_wiki_workspaces__workspace_id__last_updated_get`)

## query

### POST /query

#### 1. Method + Path

`POST /query`

#### 2. 목적

Answer Query

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`QueryRequest`)

```json
{
  "allow_web_search": true,
  "model": "string",
  "output_language": "ko",
  "provider": "string",
  "question": "string",
  "recent_conversation_summary": "string",
  "recent_messages": [
    {
      "content": "string",
      "role": "user"
    }
  ],
  "reference_context": {
  },
  "response_length": "concise",
  "user_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`QueryResponse`)

```json
{
  "answer": "string",
  "error_code": "string",
  "evidence_snippets": [
    {
      "rank": 1,
      "source_block_ids": [
        "string"
      ],
      "source_document_id": "string",
      "source_refs": [
        {
          "source_block_id": "string",
          "source_document_id": "string"
        }
      ],
      "text": "string"
    }
  ],
  "graph_context": {
    "edges": [
      {
        "from_page_id": "string",
        "link_type": "string",
        "role": "string",
        "score": 0.0,
        "to_page_id": "string"
      }
    ],
    "nodes": [
      {
        "depth": 0,
        "id": "string",
        "page_type": "string",
        "relevance_score": 0.0,
        "role": "string",
        "slug": "string",
        "title": "string"
      }
    ]
  },
  "related_pages": [
    {
      "depth": 1,
      "id": "string",
      "page_type": "string",
      "relevance_score": 1,
      "role": "string",
      "slug": "string",
      "title": "string"
    }
  ],
  "result_count": 1,
  "traversal_paths": [
    {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "string",
          "role": "string",
          "score": 0.0,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        "string"
      ],
      "path_id": "string",
      "role": "string",
      "score": 1,
      "stop_reason": "string",
      "used_for_answer": true
    }
  ],
  "updated_conversation_summary": "string",
  "web_search_executed": true,
  "web_search_requested": true
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/query" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"allow_web_search":true,"model":"<value>","output_language":"ko","provider":"<value>","question":"<value>","recent_conversation_summary":"<value>","recent_messages":[{"content":"<value>","role":"user"}],"reference_context":{},"response_length":"concise","user_id":"<value>"}'
```

```json
{
  "answer": "string",
  "error_code": "string",
  "evidence_snippets": [
    {
      "rank": 1,
      "source_block_ids": [
        "string"
      ],
      "source_document_id": "string",
      "source_refs": [
        {
          "source_block_id": "string",
          "source_document_id": "string"
        }
      ],
      "text": "string"
    }
  ],
  "graph_context": {
    "edges": [
      {
        "from_page_id": "string",
        "link_type": "string",
        "role": "string",
        "score": 0.0,
        "to_page_id": "string"
      }
    ],
    "nodes": [
      {
        "depth": 0,
        "id": "string",
        "page_type": "string",
        "relevance_score": 0.0,
        "role": "string",
        "slug": "string",
        "title": "string"
      }
    ]
  },
  "related_pages": [
    {
      "depth": 1,
      "id": "string",
      "page_type": "string",
      "relevance_score": 1,
      "role": "string",
      "slug": "string",
      "title": "string"
    }
  ],
  "result_count": 1,
  "traversal_paths": [
    {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "string",
          "role": "string",
          "score": 0.0,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        "string"
      ],
      "path_id": "string",
      "role": "string",
      "score": 1,
      "stop_reason": "string",
      "used_for_answer": true
    }
  ],
  "updated_conversation_summary": "string",
  "web_search_executed": true,
  "web_search_requested": true
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/query/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: answer_query_query_post`)

## skills

### GET /skills

#### 1. Method + Path

`GET /skills`

#### 2. 목적

List Skills

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| query | `workspace_id` | `string` | 예 | - |
| query | `user_id` | `string` | 예 | - |
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response List Skills Skills Get`)

```json
[
  {
    "enabled_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "string",
      "status": "string",
      "version": 0
    },
    "id": "string",
    "latest_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "string",
      "status": "string",
      "version": 0
    },
    "owner_user_id": "string",
    "scope_type": "string",
    "slug": "string",
    "status": "string",
    "workspace_id": "string"
  }
]
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: `workspace_id`, `user_id`

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/skills?workspace_id=<value>&user_id=<value>" \
  -H 'X-Agent-Service-Token: <value>'
```

```json
[
  {
    "enabled_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "string",
      "status": "string",
      "version": 0
    },
    "id": "string",
    "latest_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "string",
      "status": "string",
      "version": 0
    },
    "owner_user_id": "string",
    "scope_type": "string",
    "slug": "string",
    "status": "string",
    "workspace_id": "string"
  }
]
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: list_skills_skills_get`)

### POST /skills/author

#### 1. Method + Path

`POST /skills/author`

#### 2. 목적

Author Skill

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 아니요 | - |

- Content-Type: `application/json` (`SkillAuthoringRequest`)

```json
{
  "authoring_mode": "preserve",
  "description": "string",
  "instruction": "string",
  "model": "string",
  "name": "example",
  "provider": "string",
  "reference_document_ids": [
    "string"
  ],
  "scope_type": "personal",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillAuthoringResponse`)

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": [
    {
    }
  ],
  "name": "string",
  "question": "string",
  "scope_type": "personal",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "clarification_required",
  "version_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/skills/author" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"authoring_mode":"preserve","description":"<value>","instruction":"<value>","model":"<value>","name":"<value>","provider":"<value>","reference_document_ids":["<value>"],"scope_type":"personal","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": [
    {
    }
  ],
  "name": "string",
  "question": "string",
  "scope_type": "personal",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "clarification_required",
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: author_skill_skills_author_post`)

### POST /skills/author/publish

#### 1. Method + Path

`POST /skills/author/publish`

#### 2. 목적

Publish Authored Skill

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 아니요 | - |

- Content-Type: `application/json` (`PublishAuthoredSkillRequest`)

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "model": "string",
  "name": "example",
  "provider": "string",
  "scope_type": "personal",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillAuthoringResponse`)

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": [
    {
    }
  ],
  "name": "string",
  "question": "string",
  "scope_type": "personal",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "clarification_required",
  "version_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/skills/author/publish" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"description":"<value>","instructions_markdown":"<value>","model":"<value>","name":"<value>","provider":"<value>","scope_type":"personal","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": [
    {
    }
  ],
  "name": "string",
  "question": "string",
  "scope_type": "personal",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "clarification_required",
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: publish_authored_skill_skills_author_publish_post`)

### POST /skills/preview

#### 1. Method + Path

`POST /skills/preview`

#### 2. 목적

Preview Skill

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 아니요 | - |

- Content-Type: `application/json` (`SkillDefinitionRequest`)

```json
{
  "allowed_tools": [
    "list_root_items"
  ],
  "capabilities": [
    "document-create"
  ],
  "description": "string",
  "instructions_markdown": "string",
  "name": "example",
  "user_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillPreviewResponse`)

```json
{
  "has_blocked_issues": true,
  "lint_result": {
  }
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/skills/preview" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"allowed_tools":["list_root_items"],"capabilities":["document-create"],"description":"<value>","instructions_markdown":"<value>","name":"<value>","user_id":"<value>"}'
```

```json
{
  "has_blocked_issues": true,
  "lint_result": {
  }
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: preview_skill_skills_preview_post`)

### GET /skills/{skill_id}

#### 1. Method + Path

`GET /skills/{skill_id}`

#### 2. 목적

Get Skill

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `skill_id` | `string` | 예 | - |
| query | `workspace_id` | `string` | 예 | - |
| query | `user_id` | `string` | 예 | - |
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillResponse`)

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "owner_user_id": "string",
  "scope_type": "string",
  "slug": "string",
  "status": "string",
  "workspace_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: `workspace_id`, `user_id`

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/skills/<value>?workspace_id=<value>&user_id=<value>" \
  -H 'X-Agent-Service-Token: <value>'
```

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "owner_user_id": "string",
  "scope_type": "string",
  "slug": "string",
  "status": "string",
  "workspace_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_skill_skills__skill_id__get`)

### PATCH /skills/{skill_id}

#### 1. Method + Path

`PATCH /skills/{skill_id}`

#### 2. 목적

Update Skill

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `skill_id` | `string` | 예 | - |
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 아니요 | - |

- Content-Type: `application/json` (`UpdateSkillRequest`)

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "model": "string",
  "name": "example",
  "provider": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillAuthoringResponse`)

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": [
    {
    }
  ],
  "name": "string",
  "question": "string",
  "scope_type": "personal",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "clarification_required",
  "version_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X PATCH "$PIPELINE/skills/<value>" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"description":"<value>","instructions_markdown":"<value>","model":"<value>","name":"<value>","provider":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": [
    {
    }
  ],
  "name": "string",
  "question": "string",
  "scope_type": "personal",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "clarification_required",
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: update_skill_skills__skill_id__patch`)

### POST /skills/{skill_id}/disable

#### 1. Method + Path

`POST /skills/{skill_id}/disable`

#### 2. 목적

Disable Skill

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `skill_id` | `string` | 예 | - |
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 아니요 | - |

- Content-Type: `application/json` (`SkillActorRequest`)

```json
{
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillResponse`)

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "owner_user_id": "string",
  "scope_type": "string",
  "slug": "string",
  "status": "string",
  "workspace_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/skills/<value>/disable" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "owner_user_id": "string",
  "scope_type": "string",
  "slug": "string",
  "status": "string",
  "workspace_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: disable_skill_skills__skill_id__disable_post`)

### POST /skills/{skill_id}/enable

#### 1. Method + Path

`POST /skills/{skill_id}/enable`

#### 2. 목적

Enable Skill

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `skill_id` | `string` | 예 | - |
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 아니요 | - |

- Content-Type: `application/json` (`SkillActorRequest`)

```json
{
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillResponse`)

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "owner_user_id": "string",
  "scope_type": "string",
  "slug": "string",
  "status": "string",
  "workspace_id": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/skills/<value>/enable" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "owner_user_id": "string",
  "scope_type": "string",
  "slug": "string",
  "status": "string",
  "workspace_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: enable_skill_skills__skill_id__enable_post`)

## wiki-schema

### GET /wiki-schema/active

#### 1. Method + Path

`GET /wiki-schema/active`

#### 2. 목적

Get Active Wiki Schema

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| query | `workspace_id` | `string` | 예 | - |
| query | `user_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Get Active Wiki Schema Wiki Schema Active Get`)

```json
{
  "activated_at": "string",
  "created_at": "string",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": true,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "string",
      "text": "string"
    }
  ],
  "name": "string",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: `workspace_id`, `user_id`

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/wiki-schema/active?workspace_id=<value>&user_id=<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
  "activated_at": "string",
  "created_at": "string",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": true,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "string",
      "text": "string"
    }
  ],
  "name": "string",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_schema/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_active_wiki_schema_wiki_schema_active_get`)

### POST /wiki-schema/drafts

#### 1. Method + Path

`POST /wiki-schema/drafts`

#### 2. 목적

Create Wiki Schema Draft

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`CreateWikiSchemaDraftRequest`)

```json
{
  "name": "default",
  "raw_markdown": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`CreateWikiSchemaDraftResponse`)

```json
{
  "wiki_schema": {
    "activated_at": "string",
    "created_at": "string",
    "fragments": {
      "concept_markdown": "string",
      "edit_markdown": "string",
      "global_markdown": "string",
      "ingest_markdown": "string",
      "query_markdown": "string",
      "template_markdown": "string"
    },
    "has_blocked_issues": true,
    "id": "string",
    "issues": [
      {
        "category": "string",
        "reason": "string",
        "section": "string",
        "severity": "string",
        "text": "string"
      }
    ],
    "name": "string",
    "preview_markdown": "string",
    "raw_markdown": "string",
    "schema_version": "string"
  }
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/wiki-schema/drafts" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"name":"default","raw_markdown":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "wiki_schema": {
    "activated_at": "string",
    "created_at": "string",
    "fragments": {
      "concept_markdown": "string",
      "edit_markdown": "string",
      "global_markdown": "string",
      "ingest_markdown": "string",
      "query_markdown": "string",
      "template_markdown": "string"
    },
    "has_blocked_issues": true,
    "id": "string",
    "issues": [
      {
        "category": "string",
        "reason": "string",
        "section": "string",
        "severity": "string",
        "text": "string"
      }
    ],
    "name": "string",
    "preview_markdown": "string",
    "raw_markdown": "string",
    "schema_version": "string"
  }
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_schema/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: create_wiki_schema_draft_wiki_schema_drafts_post`)

### POST /wiki-schema/preview

#### 1. Method + Path

`POST /wiki-schema/preview`

#### 2. 목적

Preview Wiki Schema

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`WikiSchemaPreviewRequest`)

```json
{
  "raw_markdown": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`WikiSchemaPreviewResponse`)

```json
{
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": true,
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": null,
      "severity": "blocked",
      "text": "string"
    }
  ],
  "preview_markdown": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/wiki-schema/preview" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"raw_markdown":"<value>"}'
```

```json
{
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": true,
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": null,
      "severity": "blocked",
      "text": "string"
    }
  ],
  "preview_markdown": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_schema/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: preview_wiki_schema_wiki_schema_preview_post`)

### POST /wiki-schema/{schema_id}/activate

#### 1. Method + Path

`POST /wiki-schema/{schema_id}/activate`

#### 2. 목적

Activate Wiki Schema

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `schema_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`WikiSchemaResponse`)

```json
{
  "activated_at": "string",
  "created_at": "string",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": true,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": null,
      "severity": "blocked",
      "text": "string"
    }
  ],
  "name": "string",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "string"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/wiki-schema/<value>/activate" \
  -H 'X-Internal-Token: <value>'
```

```json
{
  "activated_at": "string",
  "created_at": "string",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": true,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": null,
      "severity": "blocked",
      "text": "string"
    }
  ],
  "name": "string",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_schema/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: activate_wiki_schema_wiki_schema__schema_id__activate_post`)

## 기타

### GET /documents/{document_id}

#### 1. Method + Path

`GET /documents/{document_id}`

#### 2. 목적

Get Document

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `document_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Get Document Documents  Document Id  Get`)

```json
{
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/documents/<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_document_documents__document_id__get`)

### GET /health

#### 1. Method + Path

`GET /health`

#### 2. 목적

Health

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- 없음

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Health Health Get`)

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/health"
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: health_health_get`)
