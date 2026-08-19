# ADR-0017: 계정 식별자를 (email, provider)로 두고 provider별로 계정을 분리

- 상태: 적용됨
- 관련: V11 `split_accounts_by_provider`, [data-model](../data-model.md), [access auth API](../api/access/auth.md)
- 대체: `users.email` 단독 UK와 이메일 일치 시 OAuth 계정을 기존 계정에 자동 연결하던 동작

## 맥락

기존에는 `users.email`이 단독 UK였고, `OAuthUserService.findOrCreateUser`가 `(provider,
provider_user_id)` 링크를 못 찾으면 provider가 준 이메일로 기존 사용자를 조회해 그 계정에
OAuth 링크를 붙였다. 따라서 같은 이메일로 일반 회원가입한 계정과 OAuth 로그인은 항상 하나의
계정으로 수렴했고, 사용자에게 선택권이 없었다.

제품 요구는 반대다. 같은 이메일이라도 계정을 만든 수단이 다르면 별개 계정으로 취급할 수 있어야
한다. 한 이메일에 `local`·`google`·`naver`·`kakao` 계정이 각각 존재할 수 있다.

## 결정

### 1. 계정 식별자

`users`에 `provider`를 추가하고 UK를 `(email, provider)`로 교체한다. `provider`는 계정을 만든
수단이며, 일반 회원가입은 `local`, OAuth는 provider 등록 ID(`google`·`naver`·`kakao`)다.
`user_oauth_accounts`는 계정에 연결된 provider 목록을 계속 소유하므로, `users.provider`(창설
수단)와 역할이 겹치지 않는다.

### 2. 이메일 기반 자동 연결 제거

`findOrCreateUser`는 `(provider, provider_user_id)` 링크가 없으면 이메일 조회 없이 항상 새 계정을
만든다. 이메일이 같은 다른 provider 계정과 합치지 않는다.

### 3. 비밀번호 경로는 `local` 스코프

로그인·회원가입·이메일 중복 검사·가입용 인증번호 발송·비밀번호 재설정은 모두
`provider = 'local'` 계정만 대상으로 한다. 결과로 이메일 중복 검사는 OAuth 전용 이메일에
`available: true`를 반환한다. 회원가입으로 만든 `local` 계정은 항상 비밀번호를 가지므로 로그인
경로의 `passwordHash == null` 차단을 삭제한다. V11 백필의 `COALESCE(..., 'local')` 폴백은
비밀번호도 OAuth 링크도 없는 계정에 `local`을 넣으므로 이 불변식의 예외가 남지만, 그런 계정은
비밀번호 검증이 실패해 동일하게 인증 오류로 수렴한다.

비밀번호 재설정은 인증번호를 소비한 뒤 세 갈래로 나눈다. `local` 계정이 있으면 변경하고,
`local`이 없고 같은 이메일의 OAuth 계정이 있으면 `PASSWORD_LOGIN_UNAVAILABLE`로 가입 provider를
알려주며, 아무 계정도 없으면 기존대로 `INVALID_VERIFICATION_TOKEN`을 반환한다. 유효한 인증번호를
통과한 요청자는 이미 해당 메일함을 통제하므로 provider 노출은 열거 위험을 만들지 않는다.

### 4. 기존 데이터 처리

V11은 `password_hash IS NOT NULL`이면 `local`을, 아니면 가장 먼저 연결된 provider를 할당한다.
비밀번호를 가진 계정에 OAuth 링크가 함께 있어도 `local`을 우선해 비밀번호 로그인 경로를
보존한다. 이후 계정 소유 provider와 다른 링크 행은 삭제한다. 그 링크가 남으면 다음 로그인이
계속 같은 계정으로 들어가 분리가 적용되지 않기 때문이다. 링크 행만 지우므로 계정·워크스페이스·
문서는 보존된다.

## 대안과 기각 사유

- **검증된 이메일이면 자동 연결 유지**: 중복 계정을 막고 사용자 문의를 줄이지만, 같은 이메일을
  용도별로 나눠 쓰려는 요구를 표현할 수 없다. 또한 서버가 되돌리기 어려운 병합을 사용자 동의
  없이 결정한다.
- **부분 유니크 인덱스(`users(email) WHERE password_hash IS NOT NULL`)**: 컬럼 추가 없이 같은
  분리를 얻지만, 계정 종류를 비밀번호 유무로 암묵 표현하므로 OAuth 계정에 비밀번호를 추가하는
  변경이 제약을 깨뜨린다. provider 구분도 링크 테이블 조인 없이는 알 수 없다.
- **연결 여부를 사용자에게 묻는 단계를 이번에 함께 도입**: 인증 흐름 변경과 프런트 화면이
  필요해 마이그레이션과 한 변경에 섞인다. 동작하는 최소 버전을 먼저 두고 후속 결정으로 다룬다.

## 결과

- 한 이메일에 provider별 계정이 공존한다. 계정마다 기본 워크스페이스가 따로 생기므로 같은
  사람이 두 계정을 쓰면 문서도 나뉜다. 이는 이 결정이 의도한 동작이다.
- OAuth 계정은 비밀번호를 가질 수 없다. 기존에 비밀번호 재설정으로 OAuth 계정에 비밀번호를
  붙일 수 있었던 경로는 사라진다.
- 이메일이 같아도 provider가 다르면 별개 계정이므로, 사용자가 어떤 수단으로 가입했는지 기억하지
  못하면 다른 계정으로 들어갈 수 있다. 연결 여부를 묻는 단계는 후속 범위로 남는다.
- `email_verified`를 주지 않는 provider 처리와 계정 연결 UI는 이 결정에 포함하지 않는다.
