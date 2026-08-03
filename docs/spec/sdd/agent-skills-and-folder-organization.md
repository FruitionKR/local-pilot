# Agent Skill과 승인형 폴더 정리

## 1. 문서 정보

- 상태: Draft
- 작성일: 2026-08-03
- 관련 이슈: 미정
- 관련 PR:
- 관련 작업 계획: docs/spec/sdd/tasks/agent-skills-and-folder-organization-tasks.md
- 관련 문서:
  - docs/spec/wiki-schema-contract.md
  - docs/spec/sdd/markdown-document-hierarchy.md
  - docs/spec/sdd/markdown-document-ai-editing.md

상태 흐름: Draft → Approved → In Progress → Verified

## 2. 배경

현재 llmPipeline의 /agent/turn은 요청을 분류한 뒤 query, Markdown 생성, Markdown 편집 UseCase 중 하나를 직접 실행한다. 여러 tool의 결과를 바탕으로 다음 행동을 결정하는 agent workflow는 제공하지 않는다.

현재 wiki_schema는 workspace/user별 활성 prompt 설정이다. 프론트엔드는 이를 “스킬”로 표시하지만 요청별 Skill 선택, 여러 enabled Skill, 명시적 Skill 커맨드, tool 실행, 변경 계획 승인과 실행 후 검증은 지원하지 않는다.

Backend에는 Workspace 단위 폴더 트리와 조회, 검색, 생성, 이름 변경, 이동 기능이 있다. 폴더·문서 변경에는 권한, current_version, Idempotency-Key, hierarchy cycle 검사가 적용된다.

사용자는 자연어나 명시적 Skill 커맨드로 문서 생성·편집 규칙을 적용하고 폴더 정리를 요청하고자 한다. Prompt 규칙만 필요한 작업은 기존 UseCase로 처리하고, 폴더 정리는 구조 조사, 계획, 승인, 실행, 검증을 포함한 AgentRun으로 처리해야 한다.

## 3. 목표

- 자연어 또는 /<skill-name> <요청>으로 Skill을 선택한다.
- 요청별 Skill 미적용 모드를 제공한다.
- 개인 Skill과 Workspace 팀 Skill을 지원한다.
- Skill은 Markdown 생성·편집, Template 적용, 폴더 정리에 적용한다.
- query와 기존 Markdown UseCase는 기존 실행 경로를 유지한다.
- 상태 변경 작업만 승인형 비동기 AgentRun으로 실행한다.
- 승인 전에는 상태를 변경하지 않고 승인된 operation만 실행한다.
- 계획이나 대상 version이 바뀌면 기존 승인을 무효화한다.
- 실행 후 operation별 결과와 최종 폴더 구조를 보여준다.
- Agent는 실행 사용자의 현재 권한을 넘지 않는다.

## 4. 범위

### 포함

- Skill 생성·수정·preview·publish·enable/disable
- 여러 enabled Skill, 개인 Skill, Workspace 팀 Skill
- 자연어 자동 선택, 명시적 선택, Skill 미적용 모드
- Markdown 생성·편집과 Template Skill
- 폴더 구조·문서 metadata 조회와 검색
- 폴더 생성·이름 변경·이동
- 문서 이름 변경·이동
- 계획 생성·preview·수정·승인·거절·취소
- 승인된 operation 실행과 결과 검증
- AgentRun, plan, 승인, job, tool 실행 이력
- PostgreSQL 기반 비동기 llmPipeline worker
- 채팅 안의 계획·승인·진행 상태 UI

### 제외

- query에 Skill 적용
- 폴더·문서 삭제와 복구
- 외부 Drive, 로컬 filesystem, S3 폴더 조작
- Workspace 사이의 항목 이동
- shell, SQL, 범용 HTTP tool
- Agent의 tool·권한 자체 확장
- 승인 bypass와 승인 후 계획의 임의 확장
- 예약 실행과 장시간 background automation
- 여러 Agent의 병렬 계획 실행
- SKILL.md import/export
- 별도 AgentRun 상세 화면과 SSE·WebSocket

### 사용자와 권한

- 기존 Backend 권한이 허용하는 모든 Workspace 멤버가 Agent Skill을 사용할 수 있다.
- 개인 Skill은 본인만 관리·사용한다.
- 팀 Skill은 owner/editor가 관리하고 모든 Workspace 멤버가 사용한다.
- 실제 폴더·문서 작업은 기존 Backend 권한 검사를 다시 통과해야 한다.

## 5. 요구사항

### REQ-001 Skill 호출 모드

시스템은 auto, explicit, off 호출 모드를 지원해야 한다.

- auto: enabled Skill 중 요청에 맞는 Skill을 자동 선택한다.
- explicit: /<skill-name> <요청>으로 Skill을 명시한다.
- off: 채팅 UI와 API의 skill_mode=off로 해당 요청에 Skill을 적용하지 않는다.
- 명시적 호출 API는 표시 이름이 아니라 skill_id를 전달한다.

#### 인수 조건

- Given: 별도 Skill 선택이 없는 자연어 요청이다.
- When: 요청을 처리한다.
- Then: auto 모드로 처리한다.
- Given: 접근 가능한 enabled Skill을 명시했다.
- When: 요청을 처리한다.
- Then: 명시한 Skill을 자동 선택보다 우선한다.
- Given: 명시한 Skill이 없거나 disabled 상태다.
- When: 요청을 처리한다.
- Then: 실행하지 않고 SKILL_NOT_FOUND 또는 SKILL_DISABLED를 반환한다.
- Given: off 모드다.
- When: 요청을 처리한다.
- Then: Skill 없이 기존 기능을 실행한다.

### REQ-002 Skill 자동 선택

AgentTurnRouter는 action과 Skill을 한 번에 선택해야 한다. 후보는 enabled 상태이고 현재 사용자가 접근할 수 있으며 action과 capability가 호환되는 개인 Skill 또는 현재 Workspace 팀 Skill이어야 한다. 한 요청에는 작업 Skill을 최대 하나만 적용한다.

#### 인수 조건

- Given: 하나의 Skill이 명확히 일치한다.
- When: Router가 요청을 분류한다.
- Then: action과 selected_skill_id를 반환한다.
- Given: 호환되는 Skill이 없다.
- When: 요청을 처리한다.
- Then: Skill 없이 기존 기능을 실행한다.
- Given: 여러 Skill이 비슷하게 일치한다.
- When: 하나를 명확히 선택할 수 없다.
- Then: 실행하지 않고 clarify와 후보 설명을 반환한다.
- And: 사용자는 Skill 하나 또는 Skill 없이 계속을 선택한 뒤 요청을 재개한다.
- Given: action이 chat_answer다.
- When: 요청을 처리한다.
- Then: query에는 Skill을 적용하지 않는다.

### REQ-003 Skill 적용과 우선순위

우선순위는 다음과 같다.

    system·developer 정책
    → Backend 권한·승인·안전 정책
    → 현재 사용자 요청
    → 명시적으로 선택한 Skill
    → 자동 선택한 Skill
    → Wiki Schema 기본 설정

Skill은 상위 정책, Backend 권한, 승인 정책, allowed tool을 변경하거나 약화할 수 없다. AgentRun은 시작 당시 선택한 Skill version을 끝까지 사용한다.

#### 인수 조건

- Given: Skill이 승인 생략이나 허용되지 않은 tool 사용을 지시한다.
- When: Agent가 Skill을 적용한다.
- Then: 해당 지시를 따르지 않고 기존 승인·권한·tool 정책을 유지한다.
- Given: 실행 중인 AgentRun의 Skill이 새 version으로 바뀌었다.
- When: 기존 AgentRun을 계속 처리한다.
- Then: 시작 당시 기록한 Skill version을 사용한다.

### REQ-004 폴더 정리 계획

시스템은 읽기 전용 tool로 현재 구조를 조사하고 상태를 변경하지 않은 채 create_folder, rename_folder, move_folder, move_document, rename_document 계획을 생성해야 한다.

사용자에게 대상 이름, 현재·변경 위치, 이유, 예상 최종 트리를 표시한다. 내부 계획에는 대상 ID, base_version, operation 의존 관계를 저장한다.

- AgentRun 최대 step: 10
- 계획 최대 operation: 20
- AgentRun 최대 tool 호출: 40
- 계획 생성 timeout: 3분

20개를 초과하는 작업은 현재 계획 완료 후 새 계획으로 처리한다. 대상을 명확히 식별할 수 없으면 clarification_required로 전환한다.

#### 인수 조건

- Given: 폴더 정리 요청이 들어왔다.
- When: 계획 생성이 완료됐다.
- Then: 상태 변경 없이 최대 20개의 operation을 반환한다.
- Given: 대상이 모호하거나 제한을 초과했다.
- When: 계획을 생성한다.
- Then: 추측하거나 제한을 넘기지 않고 확인 또는 제한 초과 사유를 반환한다.

### REQ-005 변경 승인과 취소

읽기, 검색, 계획 생성에는 승인이 필요하지 않다. 모든 상태 변경 operation은 실행 전에 승인을 받아야 하며 MVP에서는 bypass를 지원하지 않는다.

승인은 run_id, plan_id, plan_version, operations_hash, 승인자와 승인 시각에 결합한다. 버튼 또는 같은 채팅의 자연어로 승인·거절할 수 있다. 자연어 승인은 해당 채팅에 승인 대기 계획이 정확히 하나일 때만 인증된 승인 API로 처리한다.

- 미승인 plan은 실행 job을 만들 수 없다.
- plan version이나 hash가 달라지면 재승인을 요구한다.
- 일부 operation 제외 요청은 새 plan version을 만들고 전체 재승인을 요구한다.
- 실행 중 취소하면 현재 operation 이후 새 operation을 시작하지 않는다.

#### 인수 조건

- Given: plan이 승인되지 않았다.
- When: 변경 tool 호출을 시도한다.
- Then: 호출을 거부한다.
- Given: 승인 후 plan version이나 hash가 바뀌었다.
- When: 실행을 시도한다.
- Then: 기존 승인을 무효화하고 재승인을 요구한다.

### REQ-006 승인된 계획 실행

- 계획에 없는 operation을 추가하지 않는다.
- 의존 operation은 직렬 실행한다.
- 독립 operation은 최대 4개까지 병렬 실행한다.
- operation마다 고유한 Idempotency-Key를 사용한다.
- timeout 또는 retryable 오류는 같은 key로 최대 2회 재시도한다.
- 실패한 operation과 독립적인 operation은 계속 실행한다.
- 자동 rollback은 수행하지 않는다.
- 대상 version이 다르면 conflicted로 기록하고 실행하지 않는다.

#### 인수 조건

- Given: 폴더 생성에 의존하는 문서 이동이 있다.
- When: 계획을 실행한다.
- Then: 폴더 생성 성공 후 문서 이동을 실행한다.
- Given: 독립 operation이 5개 이상이다.
- When: 계획을 실행한다.
- Then: 동시에 실행하는 operation은 최대 4개다.
- Given: operation 하나가 최종 실패했다.
- When: 독립 operation이 남아 있다.
- Then: 남은 작업을 계속하고 최종 상태를 partial_failed로 기록한다.

### REQ-007 실행 결과 검증

Tool 호출 후 실제 위치, 이름, version을 다시 조회해야 한다. API 호출이 성공했더라도 실제 상태가 계획과 다르면 verification_failed로 기록하고 추가 변경을 수행하지 않는다.

Operation 상태는 succeeded, failed, skipped, forbidden, conflicted, verification_failed, cancelled 중 하나다. 검증 후 operation별 상태와 최종 폴더 구조를 보여준다.

#### 인수 조건

- Given: 변경 tool 호출이 성공했다.
- When: 검증 단계를 실행한다.
- Then: 실제 위치, 이름, version을 재조회해 계획과 비교한다.
- Given: 재조회 결과가 계획과 다르다.
- When: 결과를 기록한다.
- Then: verification_failed로 표시하고 추가 변경을 수행하지 않는다.

### REQ-008 비동기 AgentRun

폴더 정리 AgentRun은 PostgreSQL job과 별도 llmPipeline worker가 비동기로 실행해야 한다.

    queued → planning → awaiting_approval
    → queued_for_execution → executing → verifying
    → completed | partial_failed | failed | conflicted | cancelled

API는 완료를 기다리지 않고 run_id를 반환한다. 화면이나 HTTP 연결이 종료돼도 Worker는 저장된 상태를 기준으로 계속 처리하며 사용자는 run_id로 상태를 다시 조회할 수 있다.

#### 인수 조건

- Given: 폴더 정리 AgentRun을 시작했다.
- When: API가 요청을 접수했다.
- Then: 완료를 기다리지 않고 run_id와 현재 상태를 반환한다.
- Given: 화면이나 API 연결이 종료됐다.
- When: Worker가 job을 처리한다.
- Then: 저장된 상태를 기준으로 계속 실행하고 run_id 조회를 지원한다.

### REQ-009 기존 기능과 Endpoint 경계

기존 /agent/turn은 query, Markdown 생성, Markdown 편집을 직접 처리한다. Router가 folder_organize로 판정하면 내부적으로 AgentRun을 생성하고 run_id를 반환한다. Frontend는 요청을 미리 분류해 별도 생성 endpoint를 다시 호출하지 않는다. Kill switch 상태에서도 기존 query/create/edit는 유지한다.

#### 인수 조건

- Given: 기존 query/create/edit 요청이다.
- When: /agent/turn으로 요청한다.
- Then: 기존 요청·응답 계약을 유지한다.
- Given: folder_organize 요청이다.
- When: Router가 여러 단계와 승인이 필요하다고 판정한다.
- Then: AgentRun을 생성하고 run_id를 반환한다.
- Given: Agent Skill kill switch가 꺼져 있다.
- When: 기존 query/create/edit를 실행한다.
- Then: 기존 UseCase가 정상 작동한다.

## 6. 설계

### 6.1 설계 요약

    Frontend
    → Spring backend
    → llmPipeline Router
       ├─ query/create/edit → 기존 UseCase
       └─ folder_organize   → AgentRun
                               → 계획
                               → 승인 대기
                               → 승인된 tool 실행
                               → 검증

Wiki Schema는 모든 요청에 적용되는 기본 설정으로 유지하고 Skill은 요청별 작업 지침으로 별도 관리한다. Agent는 Spring backend의 업무 단위 tool만 사용한다.

### 6.2 Skill과 version

    skills
    - id, workspace_id, scope_type, owner_user_id, slug
    - enabled_version_id, status, created_at, updated_at

    skill_versions
    - id, skill_id, version, name, description
    - instructions_markdown, capabilities, allowed_tools
    - lint_result, status, created_by, created_at, published_at

Enabled Skill 수정은 새 draft version을 만든다. 안전성 검사와 preview 후 publish하면 enabled_version_id를 교체한다. 기존 AgentRun은 시작 당시 version을 유지한다.

### 6.3 Capability와 Tool

MVP capability는 document-create, document-edit, folder-organize, template이다. 시스템은 capability별 허용 tool 목록을 제한하고 allowed_tools는 후보를 더 좁힌다.

읽기 tool:

    list_root_items
    list_folder_children
    search_hierarchy
    get_breadcrumb
    get_document_metadata

변경 tool:

    create_folder
    rename_folder
    move_folder
    move_document
    rename_document

### 6.4 AgentRun 저장 모델

AgentRun은 다음 테이블로 관리한다.

    agent_runs
    agent_plans
    agent_plan_operations
    agent_approvals
    agent_jobs
    agent_tool_executions

AgentRun은 사용자·Workspace, action, 선택 Skill version, 상태와 현재 plan을 저장한다. Plan은 version, summary, canonical operation hash를 저장한다. Operation은 대상 ID·version·위치·인자·이유·의존 관계를 저장한다. 승인과 tool 실행은 plan·operation에 연결한다.

문서 본문, 전체 prompt, 인증 token은 AgentRun 감사 데이터에 저장하지 않는다.

### 6.5 비동기 Worker

별도 llmPipeline worker는 PostgreSQL row lock으로 job을 lease한다. Worker가 중단되면 lease 만료 후 다른 worker가 가져가며 동일한 Idempotency-Key로 중복 변경을 방지한다.

- heartbeat: 30초
- lease: 90초
- Worker job 최대 시도: 3회
- 독립 operation 최대 병렬 실행: 4
- retryable operation 재시도: 최대 2회

### 6.6 사용자 권한 경계

    capability 허용 tool
    ∩ Skill allowed_tools
    ∩ AgentRun 사용자의 현재 권한
    ∩ 승인된 plan operation

llmPipeline은 DB를 직접 변경하지 않는다. Spring backend는 내부 tool 요청마다 service 인증, AgentRun·operation, 승인된 plan version·hash, Workspace, 실행 사용자, 현재 멤버십·리소스 권한, 대상 version과 tool 허용 여부를 검사한다.

Backend→llmPipeline Agent·Skill 요청과 llmPipeline Worker→Backend tool 요청은 모두
`X-Agent-Service-Token`에 `AGENT_INTERNAL_TOKEN`을 전달해 service를 인증한다. 이 token은
사용자 권한을 대신하지 않으며 양쪽 모두 요청의 사용자·Workspace 권한을 별도로 검증한다.

실제 변경은 기존 FolderService, DocumentService, DocumentPlacementService를 사용한다. Agent 경로는 기존 권한, version, idempotency, hierarchy 검증을 우회할 수 없다. 계획 후 권한이 회수되면 해당 operation은 forbidden이 된다.

### 6.7 계획·승인·실행

사용자는 operation JSON을 직접 편집하지 않고 자연어로 계획 수정을 요청한다. 수정은 같은 AgentRun에 새 plan version을 만들고 기존 승인을 무효화한다.

의존 operation은 직렬, 독립 operation은 최대 4개 병렬 실행한다. 실패한 operation의 의존 작업은 skipped로 처리하고 독립 작업은 계속한다. 실행 후 관련 상태를 재조회하며 자동 rollback과 자동 복구는 수행하지 않는다.

### 6.8 API

Frontend는 Spring backend만 호출한다.

    POST /api/workspaces/{workspace_id}/agent/turn

    GET  /api/workspaces/{workspace_id}/agent/runs/{run_id}
    POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/approve
    POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/reject
    POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/cancel
    POST /api/workspaces/{workspace_id}/agent/runs/{run_id}/revise

    POST  /api/workspaces/{workspace_id}/skills/preview
    POST  /api/workspaces/{workspace_id}/skills
    GET   /api/workspaces/{workspace_id}/skills
    GET   /api/workspaces/{workspace_id}/skills/{skill_id}
    PATCH /api/workspaces/{workspace_id}/skills/{skill_id}
    POST  /api/workspaces/{workspace_id}/skills/{skill_id}/publish
    POST  /api/workspaces/{workspace_id}/skills/{skill_id}/enable
    POST  /api/workspaces/{workspace_id}/skills/{skill_id}/disable

### 6.9 UI, 보관, 배포

계획과 승인은 채팅 카드로 제공한다. Frontend는 종료될 때까지 2초 간격으로 AgentRun을 조회하며 화면을 다시 열어도 run_id로 복원한다.

종료된 AgentRun 기록은 90일 보관하고 하루 한 번 정리한다. 종료되지 않은 AgentRun에는 만료를 적용하지 않는다.

기능은 Workspace별 rollout 없이 한 번에 배포한다. AGENT_SKILLS_ENABLED=false는 신규 Skill 선택과 AgentRun 생성을 차단하되 기존 query/create/edit는 유지한다.

### 6.10 주요 설계 결정

#### DEC-001 하이브리드 실행 구조

- 결정: 기존 UseCase를 유지하고 상태 변경형 작업만 AgentRun으로 실행한다.
- 이유: 기존 기능 회귀와 불필요한 Agent 복잡도를 줄인다.
- 대안: 모든 요청을 Agent loop로 통합한다.
- 영향: direct 요청과 AgentRun 경로를 함께 유지한다.

#### DEC-002 명시적 승인과 plan hash

- 결정: 상태 변경은 plan version과 operation hash 승인을 요구한다.
- 이유: 사용자가 본 계획과 실제 실행의 일치를 보장한다.
- 대안: 대화 내용만으로 승인한다.
- 영향: 승인 저장 모델과 API가 필요하다.

#### DEC-003 업무 단위 Tool

- 결정: folder/document 업무 단위 tool만 제공한다.
- 이유: 기존 Backend 권한과 도메인 검증을 재사용한다.
- 대안: shell, SQL, 범용 HTTP tool을 제공한다.
- 영향: 새 작업마다 tool 계약이 필요하다.

#### DEC-004 여러 enabled Skill

- 결정: 여러 Skill을 enabled하고 요청마다 최대 하나를 선택한다.
- 이유: 작업 절차를 독립적으로 재사용한다.
- 대안: 하나의 전역 Schema에 모든 규칙을 합친다.
- 영향: selector와 version 관리가 필요하다.

#### DEC-005 사용자 권한을 넘지 않는 Agent

- 결정: Agent는 실행 사용자가 현재 수행할 수 있는 작업만 실행한다.
- 이유: Skill과 Agent가 권한 확대 수단이 되어서는 안 된다.
- 대안: Agent service account의 독립 권한으로 실행한다.
- 영향: 계획과 실행 시점에 권한을 각각 확인한다.

#### DEC-006 Schema와 Skill 분리

- 결정: Wiki Schema는 기본 설정, Skill은 요청별 작업 절차로 유지한다.
- 이유: 전역 설정과 선택형 지침의 역할이 다르다.
- 대안: 기존 Schema를 Skill로 migration한다.
- 영향: UI에서 두 개념을 구분해야 한다.

#### DEC-007 PostgreSQL 기반 비동기 Worker

- 결정: DB job과 별도 llmPipeline worker로 AgentRun을 실행한다.
- 이유: HTTP·API 프로세스 수명과 독립적으로 복구한다.
- 대안: 동기 HTTP 또는 FastAPI BackgroundTasks.
- 영향: lease, heartbeat, worker 배포가 필요하다.

#### DEC-008 일괄 배포와 Kill Switch

- 결정: 한 번에 배포하되 kill switch를 제공한다.
- 이유: 장애 시 신규 Agent 실행을 차단한다.
- 대안: Workspace별 단계적 rollout.
- 영향: 배포 전 전체 경로 검증이 필요하다.

## 7. 작업 계획

구현 작업은 docs/spec/sdd/tasks/agent-skills-and-folder-organization-tasks.md에서 관리한다.

## 8. 검증

| 요구사항 | 자동 검증 | 수동 검증 | 결과 |
|---|---|---|---|
| REQ-001 | 호출 모드·명시적 오류 계약 | slash command와 off UI | Pending |
| REQ-002 | 자동 선택·모호성·재선택 | 후보 선택 흐름 | Pending |
| REQ-003 | capability·우선순위·안전성 | Schema와 Skill 동시 적용 | Pending |
| REQ-004 | 계획 제한·hash·의존 관계 | 계획·예상 트리 | Pending |
| REQ-005 | 미승인 차단·재승인·자연어 승인 | 승인·수정·취소 UI | Pending |
| REQ-006 | 병렬도·멱등성·재시도·충돌 | 부분 실패 | Pending |
| REQ-007 | 재조회·검증 실패 | 실행 전후 트리 | Pending |
| REQ-008 | lease·heartbeat·worker 복구 | Worker 강제 종료 | Pending |
| REQ-009 | 기존 /agent/turn 회귀 | 기존 질문·생성·편집 | Pending |

### 실행 명령

    cd llmPipeline
    .venv/bin/python -m pytest -q tests

    cd backend
    ./gradlew test
    ./gradlew flywayValidate

    cd frontend
    npm run lint
    npm exec tsc -- --noEmit --incremental false
    npm run test:markdown
    npm run test:user-preferences
    npm run build

    git diff --check

### 수동 검증

- [ ] 개인·팀 Skill의 관리·사용 권한을 확인한다.
- [ ] 자연어, 명시적 Skill, off 모드를 확인한다.
- [ ] 모호한 Skill 후보 선택 후 요청이 재개된다.
- [ ] 계획·승인 전에는 상태가 변경되지 않는다.
- [ ] 버튼과 자연어 승인, 계획 수정·재승인을 확인한다.
- [ ] 의존 순서, 병렬도, 부분 실패, 취소를 확인한다.
- [ ] 권한 회수와 version 충돌이 안전하게 차단된다.
- [ ] 실행 결과와 최종 폴더 트리를 확인한다.
- [ ] Worker 중단 후 job이 복구되고 중복 변경이 없다.
- [ ] kill switch 상태에서 기존 기능 회귀가 없다.
- [ ] 감사 로그에 본문, 전체 prompt, token이 남지 않는다.

## 9. 미결정 사항

- [ ] Frontend Agent Skill·AgentRun 단위 테스트 package script 이름

## 10. 결과

- 검증일: 2026-08-03
- 최종 상태: llmPipeline 자동 검증 완료, Backend·Frontend 통합 Pending
- 자동 검증: llmPipeline `650 passed, 49 subtests passed`, Compose config와 Python 구문 검사 통과
- 남은 문제: Backend migration·양방향 service 인증·Tool Gateway와 Frontend UI/E2E
- 후속 작업:
  1. 요구사항 테스트부터 작성한다.
  2. 작업 계획 순서대로 구현한다.
  3. 요구사항별 자동·수동 검증을 실행한다.
  4. 검증 결과를 기록하고 상태를 Verified로 변경한다.
