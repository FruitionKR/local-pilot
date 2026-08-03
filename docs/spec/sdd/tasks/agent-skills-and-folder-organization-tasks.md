# Agent Skill과 승인형 폴더 정리 작업 계획

## 1. 문서 정보

- 상태: Draft
- 작성일: 2026-08-03
- 관련 SDD: docs/spec/sdd/agent-skills-and-folder-organization.md

## 2. 작업 원칙

- 각 TASK는 연결된 REQ의 실패 테스트 또는 계약 테스트부터 작성한다.
- 기존 query, Markdown 생성, Markdown 편집 동작을 유지한다.
- Agent는 Spring backend의 권한과 도메인 검증을 우회하지 않는다.
- 기존 wiki_schema는 별도 기본 설정으로 유지한다.
- 사용자 승인 없이 상태 변경 operation을 실행하지 않는다.
- 현재 작업 트리의 관련 없는 변경은 수정하지 않는다.

## 3. 작업 목록

### TASK-001 Agent·Skill 계약 테스트

- 관련 요구사항: REQ-001, REQ-002, REQ-003, REQ-009
- 변경 대상:
  - llmPipeline/tests/modules/agent/
  - 신규 llmPipeline/tests/modules/skill/
  - backend/src/test/java/fruition/agent/
- 작업:
  - auto, explicit, off 호출 모드 계약
  - 명시적 Skill 오류
  - action과 Skill 동시 선택
  - 모호한 후보의 clarify와 재선택
  - query Skill 미적용 회귀
  - folder_organize와 run_id 응답
- 완료 조건:
  - [ ] 구현 전 실패하는 계약 테스트를 작성한다.
  - [ ] 정상·오류·미적용 시나리오를 독립적으로 검증한다.
  - [ ] 기존 query/create/edit 계약 테스트를 유지한다.

### TASK-002 DB Migration과 저장 모델

- 관련 요구사항: REQ-003, REQ-004, REQ-005, REQ-006, REQ-008
- 변경 대상:
  - backend/src/main/resources/db/migration/
  - 신규 llmPipeline Skill·AgentRun repository
- 작업:
  - skills, skill_versions
  - agent_runs, agent_plans, agent_plan_operations
  - agent_approvals, agent_jobs, agent_tool_executions
  - scope, version, status, hash, lease index와 constraint
- 완료 조건:
  - [ ] 빈 PostgreSQL에서 Flyway migration이 성공한다.
  - [ ] 기존 데이터가 있는 PostgreSQL에서 migration이 성공한다.
  - [ ] 개인·팀 Skill 소유 범위 constraint를 검증한다.
  - [ ] 동일 job의 중복 lease를 차단한다.
  - [ ] 기존 wiki_schemas 데이터와 API를 유지한다.

### TASK-003 Skill 관리 도메인과 저장소

- 관련 요구사항: REQ-001, REQ-002, REQ-003
- 변경 대상:
  - 신규 llmPipeline/app/modules/skill/
  - llmPipeline Skill HTTP 계약
  - Spring Skill proxy와 권한 검사
- 작업:
  - 개인·팀 Skill 모델
  - draft version, preview, 안전성 검사
  - publish, enable, disable
  - enabled version 교체와 이전 version 보존
- 완료 조건:
  - [ ] 개인 Skill은 작성자만 관리·사용한다.
  - [ ] 팀 Skill은 owner/editor만 관리한다.
  - [ ] 모든 Workspace 멤버가 enabled 팀 Skill을 사용한다.
  - [ ] enabled Skill 수정 시 새 draft version을 생성한다.
  - [ ] publish 전 version은 자동 선택되지 않는다.
  - [ ] 안전성 차단 Skill은 publish할 수 없다.

### TASK-004 Capability와 Tool 허용 정책

- 관련 요구사항: REQ-003, REQ-004
- 변경 대상:
  - 신규 Skill capability 정책
  - Skill preview·validation
- 작업:
  - document-create, document-edit, folder-organize, template
  - capability별 허용 tool mapping
  - 임의 tool 추가 차단
- 완료 조건:
  - [ ] capability 밖의 tool 저장 요청을 거절한다.
  - [ ] folder-organize에 삭제·SQL·shell·범용 HTTP tool을 추가할 수 없다.
  - [ ] allowed_tools가 Backend 권한을 확대하지 않는다.
  - [ ] Skill instructions가 승인 정책을 약화하지 못한다.

### TASK-005 Skill 선택과 Prompt 조립

- 관련 요구사항: REQ-001, REQ-002, REQ-003
- 변경 대상:
  - llmPipeline/app/modules/agent/
  - llmPipeline/app/core/llm_prompt.py
  - AgentTurnRouter prompt와 출력 계약
- 작업:
  - slash command parser
  - auto, explicit, off 처리
  - 개인·팀 enabled 후보 조회
  - action과 Skill 동시 선택
  - 모호한 후보의 clarify와 요청 재개
  - Schema와 Skill prompt 우선순위
  - AgentRun Skill version 고정
- 완료 조건:
  - [ ] 명시적 Skill이 자동 선택보다 우선한다.
  - [ ] 모호하면 사용자 선택 전 실행하지 않는다.
  - [ ] query에는 Skill을 주입하지 않는다.
  - [ ] Markdown 생성·편집에 선택된 Skill을 주입한다.
  - [ ] off 모드에서는 Skill을 주입하지 않는다.

### TASK-006 AgentRun과 계획 생성

- 관련 요구사항: REQ-004, REQ-008, REQ-009
- 변경 대상:
  - 신규 llmPipeline AgentRun domain/application
  - AgentRun repository
  - 계획 prompt와 출력 parser
- 작업:
  - folder_organize AgentRun 생성
  - 읽기 전용 tool 구조 조사
  - 최대 20개 operation 계획
  - 의존 관계와 대상 ID·base_version
  - plan version과 canonical hash
  - clarification_required
  - step, tool 호출, timeout 제한
- 완료 조건:
  - [ ] 계획 생성 중 변경 tool을 호출하지 않는다.
  - [ ] 계획은 최대 20개 operation만 포함한다.
  - [ ] ID, version, 위치, 이유, 의존 관계를 저장한다.
  - [ ] 동일 계획은 동일 canonical hash를 만든다.
  - [ ] 제한 초과와 모호한 대상을 명시적으로 처리한다.

### TASK-007 승인·수정·취소

- 관련 요구사항: REQ-005
- 변경 대상:
  - llmPipeline AgentRun 승인 UseCase
  - Spring AgentRun proxy API
  - 승인 무결성 검사
- 작업:
  - approve, reject, revise, cancel
  - 자연어 승인·거절 intent
  - plan version과 hash 검증
  - 계획 수정 시 승인 무효화
  - 상태별 취소
- 완료 조건:
  - [ ] 미승인 plan은 실행 job을 생성하지 않는다.
  - [ ] 다른 version·hash 승인을 거절한다.
  - [ ] 자연어 승인은 pending plan이 하나일 때만 허용한다.
  - [ ] 계획 수정 후 전체 재승인을 요구한다.
  - [ ] 실행 중 취소 후 새 operation을 시작하지 않는다.

### TASK-008 사용자 권한 기반 Tool Gateway

- 관련 요구사항: REQ-003, REQ-005, REQ-006
- 변경 대상:
  - Spring 내부 Agent tool controller·service
  - FolderService, DocumentService, DocumentPlacementService
  - llmPipeline tool adapter
- 작업:
  - 읽기·변경 tool adapter
  - service 인증
  - AgentRun·plan·operation 승인 검증
  - 실행 사용자의 현재 권한 재검증
  - Workspace 경계와 version 검증
- 완료 조건:
  - [ ] Agent가 DB를 직접 조회·수정하지 않는다.
  - [ ] 다른 Workspace 항목에 접근할 수 없다.
  - [ ] 팀 Skill 작성자 권한을 실행 사용자에게 전달하지 않는다.
  - [ ] 승인되지 않은 operation을 실행하지 않는다.
  - [ ] 권한 회수 operation은 forbidden으로 기록한다.
  - [ ] 기존 hierarchy와 version 검사를 유지한다.

### TASK-009 PostgreSQL Agent Worker

- 관련 요구사항: REQ-006, REQ-008
- 변경 대상:
  - 신규 llmPipeline worker entrypoint
  - agent_jobs repository
  - infra/docker-compose.pipeline.yml
- 작업:
  - planning, execution, verification job
  - PostgreSQL lease와 heartbeat
  - Worker crash 후 job 재획득
  - 관찰 결과와 실행 가능한 승인 operation을 입력으로 한 bounded ReAct decision
  - 허용된 read, 승인 operation 하나 실행, 완료, 새 계획 요청 action 검증
  - 상태 변경 시 승인된 plan의 tool과 arguments만 사용
  - operation별 Idempotency-Key와 retry
- 완료 조건:
  - [ ] API와 Worker를 별도 프로세스로 실행한다.
  - [ ] 두 Worker가 같은 job을 동시에 처리하지 않는다.
  - [ ] lease 만료 job을 다른 Worker가 처리한다.
  - [ ] 의존 operation은 직렬로 실행한다.
  - [ ] LLM이 계획 밖 operation이나 변경 인자를 실행할 수 없다.
  - [ ] 계획 변경이 필요하면 clarification_required로 중단하고 재승인을 요구한다.
  - [ ] decision과 tool 호출 제한에서 loop가 종료된다.
  - [ ] 영구 오류는 재시도하지 않는다.

### TASK-010 실행 결과 검증과 복구 상태

- 관련 요구사항: REQ-006, REQ-007, REQ-008
- 변경 대상:
  - AgentRun verification application
  - AgentRun 조회 API
  - 최종 상태 계산
- 작업:
  - 변경 대상 재조회
  - operation별 상태
  - 의존 operation skipped
  - partial_failed, conflicted, cancelled
  - 중단된 AgentRun 조회와 복구
- 완료 조건:
  - [ ] Tool 성공 후 실제 상태를 재조회한다.
  - [ ] 불일치는 verification_failed로 기록한다.
  - [ ] 일부 실패 시 성공한 변경을 정확히 표시한다.
  - [ ] 자동 rollback이나 미승인 복구를 수행하지 않는다.
  - [ ] run_id로 진행·종료 상태를 복원한다.

### TASK-011 Spring Agent API 통합

- 관련 요구사항: REQ-001, REQ-005, REQ-008, REQ-009
- 변경 대상:
  - backend/src/main/java/fruition/agent/
  - PipelineAgentRequester
  - AgentRun·Skill proxy
- 작업:
  - 기존 /agent/turn 하위 호환
  - workspace/user context 전달
  - folder_organize의 AgentRun 생성·반환
  - AgentRun 관리 proxy
  - Skill 관리 proxy
  - Pipeline 오류 매핑
- 완료 조건:
  - [ ] query/create/edit 응답 계약을 유지한다.
  - [ ] 폴더 요청은 run_id와 queued를 반환한다.
  - [ ] 인증 사용자를 actor context로 전달한다.
  - [ ] 다른 사용자의 개인 Skill·AgentRun에 접근할 수 없다.
  - [ ] Pipeline 오류를 정의된 Backend 오류로 변환한다.

### TASK-012 Frontend Skill 관리

- 관련 요구사항: REQ-001, REQ-002, REQ-003
- 변경 대상:
  - frontend/src/entities/schema/
  - frontend/src/features/schema-manage/
  - 신규 Skill entity·feature
- 작업:
  - Schema와 Skill 화면 분리
  - 개인·팀 scope
  - name, description, instructions, capabilities
  - preview, publish, enable, disable
  - slash command 검색·autocomplete
  - off 모드
- 완료 조건:
  - [ ] Schema와 Skill 역할을 UI에서 구분한다.
  - [ ] 접근 가능한 Skill만 검색한다.
  - [ ] 같은 이름은 소유 범위를 함께 표시한다.
  - [ ] 존재하지 않는 Skill을 실행할 수 없다.
  - [ ] 팀 Skill 관리 권한에 맞게 UI를 제한한다.

### TASK-013 Frontend AgentRun 계획·승인 UI

- 관련 요구사항: REQ-002, REQ-004, REQ-005, REQ-007, REQ-008
- 변경 대상:
  - frontend/src/widgets/agent-panel/
  - frontend/src/features/agent-chat/
  - 신규 AgentRun query·card
- 작업:
  - 모호한 Skill 후보 선택
  - 계획 카드
  - 승인·거절·수정·취소
  - 2초 polling
  - operation 진행 상태와 최종 결과
- 완료 조건:
  - [ ] 모호한 Skill은 선택 전 실행하지 않는다.
  - [ ] 승인 전 operation을 실행하지 않는다.
  - [ ] 계획 변경과 재승인을 확인할 수 있다.
  - [ ] 종료 상태에서 polling을 중단한다.
  - [ ] 화면을 다시 열어 진행 중 run을 복원한다.

### TASK-014 운영 설정과 보관

- 관련 요구사항: REQ-007, REQ-008, REQ-009
- 변경 대상:
  - infra/docker-compose.pipeline.yml
  - 환경변수 예시
  - AgentRun 정리 job
  - 운영 로그
- 작업:
  - Agent worker service
  - AGENT_SKILLS_ENABLED kill switch
  - Worker health check
  - 종료된 AgentRun 90일 정리
  - 구조화된 run·job·operation 로그
- 완료 조건:
  - [ ] kill switch가 신규 Skill 선택과 AgentRun 생성을 차단한다.
  - [ ] kill switch 상태에서도 기존 query/create/edit가 작동한다.
  - [ ] Worker health check를 제공한다.
  - [ ] 종료 후 90일이 지난 기록만 삭제한다.
  - [ ] 본문, 전체 prompt, 인증 token을 감사 로그에 남기지 않는다.

## 4. 구현 순서

1. TASK-001 계약 테스트
2. TASK-002 DB 기반
3. TASK-003~TASK-005 Skill 저장·정책·선택
4. TASK-006~TASK-007 AgentRun 계획·승인
5. TASK-008 Tool 권한 경계
6. TASK-009~TASK-010 Worker·실행·검증
7. TASK-011 Backend 통합
8. TASK-012~TASK-013 Frontend
9. TASK-014 운영 설정과 전체 회귀 검증

## 5. 검증 결과

llmPipeline의 Skill·AgentRun·Worker 구현과 자동 테스트는 완료했다. 2026-08-03 기준 전체
테스트는 `651 passed, 49 subtests passed`이며 Compose config와 Python 구문 검사가 통과했다.
Spring migration·Tool Gateway, Frontend UI, 실제 PostgreSQL·Backend E2E가 필요한 완료 조건은
Pending으로 유지한다.
