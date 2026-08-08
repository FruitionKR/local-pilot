# AI/Pipeline 변경 기록

## 2026-08-08

### feat: Wiki 페이지 이름 변경 API 구현

- Backend의 기존 위임 계약에 맞춰 `PATCH /wiki/pages/{wiki_page_id}/rename`을 추가했다.
- 페이지 소속과 slug 충돌을 PostgreSQL transaction 안에서 검증하고, title만 변경하거나 title과 slug를 함께 변경할 수 있도록 했다.
- 잘못된 제목, 페이지 부재, slug 충돌, 동일 slug 재요청과 동시 unique 충돌을 자동화 테스트로 검증했다.

### fix: 실패한 Wiki ingest 변경 이력 보존

- 페이지 저장 뒤 후속 단계가 실패해도 실패 콜백의 `changed_pages`에 이미 생성된 operation artifact를 보존한다.
- operation별 JSON 기여 조각의 저장 필드와 식별자 검증 기반 재조립을 다시 확인했다.
- llmPipeline 전체 테스트 `799 passed, 61 subtests passed`로 검증했다.
