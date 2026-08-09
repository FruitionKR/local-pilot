# Backend 변경 기록

## 2026-08-09

### feat: Skill 작성·게시 연동 계약 통일

- Spring 공개 Skill API를 llmPipeline의 `/skills/author`, `/skills/author/publish`, 조회·수정·활성화 계약에 맞춰 proxy 방식으로 통일했다.
- 사용자 요청에서 capability와 Tool 선택을 제거하고, 내부 service token과 Workspace membership을 검증하는 참조 문서 Markdown 조회 endpoint를 추가했다.
- llmPipeline Skill repository가 Spring 관리 schema를 사용할 수 있도록 호환 migration을 추가했다.
- Skill 전용 테스트 10개와 Backend 전체 테스트 534개를 통과했다.

## 2026-08-08

### refactor: Skill 라우팅 책임을 LLM Pipeline으로 일원화

- Spring의 `SkillExecutionResolver`와 Skill 후보 snapshot 전송을 제거해 중복 DB 조회를 없앴다.
- Agent 요청의 자연어 메시지와 `/command`를 변경하지 않고 LLM Pipeline에 전달하도록 계약을 정리했다.
- Backend 전체 테스트 `./gradlew test`를 통과했다.
