# Backend 변경 기록

## 2026-08-09

### feat: 완료 AgentRun 기반 Skill 제안 연결

- 선택한 AgentRun의 사용자·Workspace·완료 상태와 성공 operation을 검증해 `/skills/draft-from-runs/preview`로 전달하는 공개 API를 추가했다.
- AgentRun 실행 기반 schema와 `skill_version_sources`를 추가하고, 게시된 Skill version에 검증된 source Run을 연결했다.
- llmPipeline 코드는 변경하지 않았으며, 외부 게시 성공 후 Spring source 연결이 실패하면 감사 연결이 누락될 수 있는 transaction 경계를 문서화했다.
- Skill 테스트와 Backend 전체 테스트를 통과했다.

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
