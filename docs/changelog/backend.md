# Backend 변경 이력

## 2026-08-10

- 워크스페이스 OWNER가 ingest/lint AI provider와 model을 설정하고 MEMBER가 조회할 수 있는 API를 추가했다.
- Query별 AI provider/model 선택값과 사용자 전역 웹 검색 허용 여부를 실행 이력에 snapshot으로 저장한다.
- 사용자 웹 검색 설정 조회·변경 API와 access/document 서비스 간 내부 조회를 추가했다. 기본값과 조회 실패 시 동작은 모두 비허용이다.
- AI Query HTTP/Kafka payload에는 설정을 `allow_web_search` boolean으로 전달하며 AI pipeline 구현은 변경하지 않는다.
- `java-shared`, `access-svc`, `document-svc` 전체 테스트를 통과했다.
