# 0002. Spring Security 기반 인증 전략 선택

- Status: Accepted
- Date: 2026-08-06

## Context

현재 제품 API는 workspace 경계를 기준으로 문서·Wiki·채팅을 격리한다. MVP에서도 사용자 식별, access token 만료, refresh token 폐기, OAuth provider 연결이 필요하다.

## Decision

Spring Security를 인증 경계로 사용한다.

- 이메일 회원가입은 이메일 verification 후 BCrypt password를 저장한다.
- Google·Naver·Kakao OAuth는 Spring Security OAuth client로 처리한다.
- access token은 짧은 만료의 JWT를 사용하고, refresh token은 opaque token으로 발급해 DB에는 hash만 저장한다.
- refresh token은 rotation하며 logout·만료·폐기 상태를 확인한다.
- 최초 가입 시 기본 workspace와 `owner` membership을 함께 생성한다.
- workspace 하위 API는 service 계층에서 membership을 다시 확인하고, 접근 불가 resource는 `404`로 숨긴다.

## Alternatives Considered

- 서버 세션·cookie만 사용: revocation은 단순하지만 API와 pipeline 경계의 stateless 전달이 어렵다.
- Supabase/Auth0 같은 외부 Auth service: 구현 부담은 줄지만 MVP의 workspace·refresh·OAuth 계약을 외부 정책에 맞춰야 한다.
- OAuth-only: 이메일 기반 가입과 provider 미연결 사용자를 지원할 수 없다.

## Consequences / Trade-offs

### Positive

- Spring public API에서 인증·인가·workspace ownership을 한 경계로 관리할 수 있다.
- refresh token을 개별 폐기할 수 있고, password·token 원문을 저장하지 않는다.
- provider를 추가해도 제품 API의 사용자·workspace 모델은 유지된다.

### Negative

- JWT secret, OAuth client secret, refresh token lifecycle을 안전하게 운영해야 한다.
- token이 만료된 프론트 요청의 자동 갱신은 현재 기본 구현 범위가 아니다.
- 현재 일반 멤버 초대·세분화된 role 권한은 구현 범위에 포함되지 않는다.

## Follow-up

- 외부 배포 전 CORS, cookie/redirect 정책, rate limit, secret rotation을 환경별로 확정한다.
- refresh token 도난·재사용 탐지와 사용자 전체 session 폐기 정책을 운영 요구사항으로 검토한다.
