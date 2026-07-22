# Backlog 문서

이 디렉터리는 현재 구현 기준과 다르거나, 구현 전 설계 단계에서 작성된 이전 자료를 보관한다.
현재 API 계약과 실행 기준은 `docs/` 최상위 문서와 `docs/spec/`의 최신 문서를 우선한다.

보관 문서를 참고할 때는 각 파일 상단의 이전 자료 안내와 최신 참조 문서를 먼저 확인한다.

## 보관 문서

- `development.md` — 백엔드/프론트엔드 도입 전 개발 환경 기록. 현재 실행 기준은 `docs/local-runbook.md`.
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
- `issue-2026-07-15.md` — pipeline run 동시 실행 방지·heartbeat·Markdown 편집 계약 완료 기록.
- `issue-2026-07-16.md` — wiki_pages 잔재 constraint 해결·Flyway 도입 완료 기록. 요약은 `docs/changelog/backend.md` 2026-07-16 항목.
- `ai-issue-2026-07-16.md` — llmPipeline 입력 책임·재실행 검증·PDF 복원 개선 완료 기록.
- `issue-2026-07-18.md` — Wiki ingest evaluator의 선택적 patch, fallback, retry 기록, unresolved 상태 구현 완료 기록.
- `issue-2026-07-20.md` — dev-up backend readiness 해결 기록. content_hash 잔여분은 `docs/issue/backend/2026-07-21.md`, pipeline 스키마 해결 기록은 `issue-2026-07-21.md`로 이관.
- `issue-2026-07-21.md` — 새 노트 생성, pipeline schema 소유권 충돌, Markdown 편집 router·생성 계약, 이메일 인증 기반 회원가입·비밀번호 재설정 API 해결 기록. 노트 본문 저장 API 잔여분은 `docs/issue/backend/2026-07-21.md`, 프론트 재배선 잔여분은 `docs/issue/frontend/2026-07-21.md`, auth 계약은 `docs/spec/api/auth.md`. pipeline 동시 실행·heartbeat는 dev에서 해결(요약은 `docs/changelog/ai.md` 2026-07-21 항목).
- `Fruition_MVP_API_Contract.md` — 로그인 없이 단일 기본 workspace만 쓰던 시절 API 계약. 현재 API 계약은 `docs/Fruition_MVP_API_Contract.md`.
