# Backend 변경 기록

## 2026-08-08

### refactor: Skill 라우팅 책임을 LLM Pipeline으로 일원화

- Spring의 `SkillExecutionResolver`와 Skill 후보 snapshot 전송을 제거해 중복 DB 조회를 없앴다.
- Agent 요청의 자연어 메시지와 `/command`를 변경하지 않고 LLM Pipeline에 전달하도록 계약을 정리했다.
- Backend 전체 테스트 `./gradlew test`를 통과했다.
