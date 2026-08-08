# Backlog 문서

이 디렉터리는 현재 구현 기준과 다르거나, 구현 전 설계 단계에서 작성된 이전 자료를 보관한다.
현재 API 계약과 실행 기준은 `docs/` 최상위 문서와 `docs/spec/`의 최신 문서를 우선한다.
현재 MSA 목표 구조는 `docs/Fruition_AWS_MSA_Architecture.md`를 따른다.

보관 문서를 참고할 때는 각 파일 상단의 이전 자료 안내와 최신 참조 문서를 먼저 확인한다.

## 보관 문서

- `development.md` — 백엔드/프론트엔드 도입 전 개발 환경 기록. 현재 실행 기준은 `docs/local-runbook.md`.
- `Fruition_MSA_Proposal.md` — 4서비스와 SQS를 전제로 한 초기 AWS MSA 제안서. 현재 구조는 `docs/Fruition_AWS_MSA_Architecture.md`.
- `Fruition_MSA_Proposal_revised.md` — ECS on Fargate와 SQS를 전제로 한 3서비스 AWS MSA 제안서. 현재 구조는 `docs/Fruition_AWS_MSA_Architecture.md`.
- `msa-operational-contracts.md` — 4도메인 분할과 SQS/Redis Streams를 전제로 한 구현·운영 계약 초안. 필드·상태 전이·재처리 알고리즘만 참고한다.
- `query-sse-redis.md` — Query 실행·재처리를 Redis Streams로 확장하는 이전 설계안. 현재 AI 작업 큐는 Kafka를 사용하고 Redis는 실시간 상태 전달에 한정한다.
- `issue-2026-06-11.md` — 2026-06-16 기준 해결 또는 이관된 2026-06-11 이슈 기록.
- `issue-2026-06-12.md` — 2026-06-16 기준 해결된 rename API 이슈 기록.
- `issue-2026-06-16.md` — 전 항목 해결(PR 34/38/43/44/54/55/58)된 이슈 기록. 2026-07-15 이관.
- `issue-2026-06-17.md` — 백엔드 query event 중계(PR 39/41) 해결 기록. 프론트 SSE 잔여분은 `docs/issue/frontend/2026-07-15.md`.
- `issue-2026-06-20.md` — 인용 원본 하이라이트(PR 43~46) 해결 기록. 프론트 SSE 잔여분은 `docs/issue/frontend/2026-07-15.md`.
- `issue-2026-06-26.md` — 문서 처리 상태·삭제 API·DB 큐(PR 55) 해결 기록. heartbeat·동시 실행 방지·프론트 연동 잔여분은 `docs/issue/ai/2026-07-15.md`·`docs/issue/frontend/2026-07-15.md`.
- `issue-2026-06-27.md` — PR 49 작업 완료 기록(변경 기록). 요약은 `docs/changelog/ai.md` 2026-06-27 항목.
- `issue-2026-06-29.md` — concept 충돌·삭제 문서 handling 해결 기록. 동시 실행 방지·heartbeat 잔여분은 `docs/issue/ai/2026-07-15.md`.
- `issue-2026-07-02.md` — workspace 격리(PR 58/60/62/66/67) 해결 기록. lint/maintenance·source_refs 잔여분은 `docs/issue/backend/2026-07-15.md`·`docs/issue/frontend/2026-07-15.md`.
- `issue-2026-07-03.md` — PK UUID(PR 67)·프론트 workspace 마이그레이션(PR 68) 해결 기록. OAuth·FK CASCADE 잔여분은 `docs/issue/backend/2026-07-15.md`·`docs/issue/frontend/2026-07-15.md`.
- `issue-2026-07-04.md` — wiki workspace 경로 프론트 후속 작업 원문. 미해결 상태로 `docs/issue/frontend/2026-07-15.md`에 통합 이관.
- `issue-2026-07-09.md` — chat inline markdown 계약(PR 72/73) 해소 기록. processing_stage 폴링 잔여분은 `docs/issue/frontend/2026-07-15.md`.
- `issue-2026-07-10.md` — Chat Wiki API 계약 확정·검증 완료 기록. 계약 원본은 `docs/spec/chat-to-wiki-contract.md`.
- `issue-2026-07-14.md` — 복원 플로우 최적화 v5~v8 완료 기록(변경 기록). 요약은 `docs/changelog/ai.md` 2026-07-14 항목.
- `issue-2026-07-15.md` — 2026-07-15 해결 이슈 기록: 백엔드(query evidence source_refs 노출, documents/wiki_pages FK CASCADE + Flyway 도입)와 AI/Pipeline(pipeline run 동시 실행 방지·heartbeat·Markdown 편집 계약). 미해결분(wiki maintenance lint proxy, Kakao OAuth)은 `docs/issue/backend/2026-07-15.md`.
- `issue-2026-07-16.md` — wiki_pages 잔재 constraint 해결·Flyway 도입 완료 기록. 요약은 `docs/changelog/backend.md` 2026-07-16 항목.
- `ai-issue-2026-07-16.md` — llmPipeline 입력 책임·재실행 검증·PDF 복원 개선 완료 기록.
- `issue-2026-07-18.md` — Wiki ingest evaluator의 선택적 patch, fallback, retry 기록, unresolved 상태 구현 완료 기록.
- `issue-2026-07-20.md` — dev-up backend readiness 해결 기록. content_hash 잔여분은 `docs/issue/backend/2026-07-21.md`, pipeline 스키마 해결 기록은 `issue-2026-07-21.md`로 이관.
- `issue-2026-07-21.md` — 새 노트 생성, pipeline schema 소유권 충돌, Markdown 편집 router·생성 계약, 이메일 인증 회원가입·비밀번호 재설정 API와 Frontend 연동 해결 기록. 노트 본문 저장 API 잔여분은 `docs/issue/backend/2026-07-21.md`. pipeline 동시 실행·heartbeat는 dev에서 해결(요약은 `docs/changelog/ai.md` 2026-07-21 항목).
- `issue-ai-2026-07-22.md` — Agent 전체 편집·500 응답, Wiki maintenance HTTP 경계, PDF evaluator 원본 보존 검증 해결 기록.
- `issue-2026-07-25.md` — Backend의 Markdown 원문 내보내기, wiki-schema·maintenance 프록시, 폴더 트리 영속화와 AI/Pipeline의 활성 schema 주입, `source_related_to` 조합 저장·legacy 정리 절차, Query DB 후보 제한, multi-provider·Claude 지원, 삭제 workspace pipeline 차단 완료 기록. Provider 실환경 E2E는 `docs/issue/ai/2026-07-25.md`에서 계속 관리한다.
- `issue-2026-07-27.md` — 재편입 후 최신 active contribution 기반 Concept 본문·embedding 의미 정합성 복구 완료 기록.
- `issue-2026-07-28.md` — AI 편집 승인 저장의 `source=agent` 스냅샷 영속화와 채팅 편입 문서의 워크스페이스 목록·검색 노출, 자동 선택 연결 완료 기록.
- `issue-2026-07-29.md` — 문서 콘텐츠 버전 영속화, 변경 전후 스냅샷, 비파괴 복원과 GitHub 스타일 Markdown diff API 완료 기록. Frontend 연동은 `docs/issue/frontend/2026-07-23.md`에서 계속 관리한다.
- `issue-2026-08-03.md` — AI 작업 로그·복구 연동 완료 기록.
- `issue-2026-08-05.md` — AI 결과 콜백 E2E와 복구 삭제 Page의 link·embedding 정리 완료 기록.
- `Fruition_MVP_API_Contract.md` — 로그인 없이 단일 기본 workspace만 쓰던 시절 API 계약. 현재 API 계약은 `docs/Fruition_MVP_API_Contract.md`.
