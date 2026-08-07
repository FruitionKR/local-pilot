# Architecture Decision Records

이 디렉터리는 현재 구현에 영향을 준 중요한 아키텍처 결정을 기록한다.

## 상태

- `Accepted`: 현재 적용 중인 결정
- `Proposed`: 적용 전 검토 중인 결정
- `Superseded`: 새 결정으로 대체된 결정

## 목록

- [0001. PostgreSQL을 primary database로 선택](./0001-choose-primary-database.md)
- [0002. Spring Security 기반 인증 전략 선택](./0002-choose-auth-strategy.md)
- [0003. PostgreSQL queue와 내부 HTTP 기반 event processing 선택](./0003-choose-event-processing-strategy.md)

새로운 기술 선택이 기존 결정과 충돌하면 기존 문서를 수정하기보다 새 ADR을 추가하고 이전 ADR의 상태를 `Superseded`로 갱신한다.
