# Backend 변경 기록

## 2026-08-09

### feat: Agent Tool Backend 접근 경계 연결

- `X-Agent-Service-Token`으로 보호되는 Agent Tool read·execute 내부 endpoint를 추가했다.
- AgentRun의 사용자·Workspace 범위와 mutation의 승인된 현재 plan·operation·arguments를 검증하고 기존 문서·폴더 권한, version, idempotency 처리를 재사용했다.
- `$operation_result`는 성공한 선행 operation 결과로 해석해 승인 범위를 유지하며, 실행 직전 `running` operation만 허용한다.
- content artifact 계약이 필요한 `create_document`, `apply_document_edit`는 연결 전까지 명시적으로 차단한다.
- Agent Tool 대상 테스트와 Backend 전체 테스트를 통과했다.

### feat: 완료 AgentRun 기반 Skill 제안 연결

- 선택한 AgentRun의 사용자·Workspace·완료 상태와 성공 operation을 검증해 `/skills/draft-from-runs/preview`로 전달하는 공개 API를 추가했다.
- AgentRun 실행 기반 schema와 `skill_version_sources` schema를 추가했으며, runtime source 저장 책임은 Skill version을 생성하는 llmPipeline 게시 transaction에 남겼다.
- llmPipeline 코드는 변경하지 않고 Spring의 source Run 검증과 proposal proxy 경계만 구현했다.
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
