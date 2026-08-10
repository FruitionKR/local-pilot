# Wiki DB 경계를 먼저 분리하고 maintenance cutover로 이전

## 맥락

AI와 document-svc가 `core_db`의 Wiki 현재 상태를 함께 읽고 쓰고, AI가 `documents.status`까지 갱신해 트랜잭션 소유권이 겹쳤다. 현재 테이블을 즉시 삭제하면 기존 page ID와 문서 원문 연결을 잃을 수 있다.

## 결정

- 먼저 document-svc의 Wiki 현재 상태 조회를 AI 내부 API로 전환하고, AI의 `documents` 접근과 core 기여 이력 JOIN을 내부 API로 바꾼다.
- 이 단계에서는 Wiki 현재 상태·embedding·`pipeline_runs`의 물리 저장소를 `core_db`에 유지한다. DB 이동과 `ai_runtime` core 권한 회수는 별도 maintenance cutover에서 수행한다.
- DB 경계를 넘게 될 `page_id`·`document_id` FK만 미리 제거하고 opaque logical ID로 취급한다. 테이블은 삭제하지 않는다.
- 데이터가 있는 환경은 write worker 중지 → 양쪽 DB snapshot → ID 보존 복사 → row count·PK·hash·고아 참조 검증 → 연결 전환 → smoke test 순서로 이전한다. 기존 core 테이블은 안정화 기간에 read-only로 보존한 뒤 별도 migration에서 제거한다.
- 폐기 가능한 로컬 개발 데이터는 DB를 재생성할 수 있지만, 공유·운영 데이터에는 이 예외를 적용하지 않는다.
- 문서 최종 상태는 document-svc가 run을 폴링해 투영하고, `notify_pending`이면 기존 callback retry API를 호출한다.

## 대안과 기각 사유

- core 테이블 즉시 삭제: ID·원문 연결 보존을 검증할 수 없어 기각했다.
- dual-write: 현재 zero-downtime 요구가 없고 동기화·보정 경로가 늘어나므로 만들지 않는다.
- callback만 사용: callback 유실 시 문서 상태가 고착되므로 polling 복구를 함께 둔다.

## 결과

경계 준비와 데이터 이동을 분리해 각 단계의 rollback 지점을 유지한다. 이 단계에서는 ai-svc가 논리 소유자지만 Wiki 현재 상태가 물리적으로 `core_db`에 남는 전환기 예외가 존재한다.
