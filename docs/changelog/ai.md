# AI/Pipeline 변경 기록

## 2026-08-08

### feat: Wiki 페이지 이름 변경 API 구현

- Backend의 기존 위임 계약에 맞춰 `PATCH /wiki/pages/{wiki_page_id}/rename`을 추가했다.
- 페이지 소속과 slug 충돌을 PostgreSQL transaction 안에서 검증하고, title만 변경하거나 title과 slug를 함께 변경할 수 있도록 했다.
- 잘못된 제목, 페이지 부재, slug 충돌, 동일 slug 재요청과 동시 unique 충돌을 자동화 테스트로 검증했다.
