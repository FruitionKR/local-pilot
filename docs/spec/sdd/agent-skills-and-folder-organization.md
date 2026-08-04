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

- 실행 ReAct decision 최대 step: 40
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
- 매 decision마다 최근 관찰과 operation 상태를 LLM에 제공하며, 필요한 현재 상태는 허용된 read tool로 조회한다.
- LLM은 허용된 read tool 호출, 실행 가능한 승인 operation 하나 선택, 새 계획 요청 중 하나만 선택한다. 남은 operation이 없으면 시스템이 자동으로 검증 단계로 전환한다.
- 상태 변경 tool과 인자는 LLM 출력이 아니라 승인된 plan operation에서 읽는다.
- 의존 operation은 선행 결과가 확인된 뒤 선택할 수 있으며 operation은 한 번에 하나씩 실행한다.
- LLM이 승인되지 않았거나 아직 실행 가능하지 않은 operation을 선택하면 실행하지 않는다.
- 승인된 계획으로 목표를 안전하게 달성할 수 없으면 clarification_required로 중단하고 새 plan version과 hash 승인을 요구한다.
- 실행 decision은 최대 40회이며 AgentRun 전체 tool 호출은 최대 40회다.
- 남은 Tool 호출 수가 pending mutation 수와 같으면 추가 read를 허용하지 않으며, 더 적으면 react_tool_budget_insufficient 사유로 clarification_required 전환한다.
- operation마다 고유한 Idempotency-Key를 사용한다.
- timeout 또는 retryable 오류는 같은 key로 최대 2회 재시도한다.
- 실패한 operation과 독립적인 operation은 계속 실행한다.
- 자동 rollback은 수행하지 않는다.
- 대상 version이 다르면 conflicted로 기록하고 실행하지 않는다.
- LLM의 chain-of-thought는 저장하지 않고 선택한 action, 제한된 관찰 결과와 실행 결과만 사용한다.
- 새 계획 요청 사유는 state_changed, insufficient_information, plan_no_longer_safe, goal_not_achievable 중 하나만 허용하고 AgentRun error_code로 반환한다.

#### 인수 조건

- Given: 폴더 생성에 의존하는 문서 이동이 있다.
- When: 계획을 실행한다.
- Then: 폴더 생성 성공 후 문서 이동을 실행한다.
- Given: 여러 독립 operation이 실행 가능하다.
- When: 다음 행동을 선택한다.
- Then: LLM이 그중 하나를 선택하고 시스템은 승인 당시 저장한 tool과 인자로 실행한다.
- Given: LLM이 계획에 없는 operation이나 변경된 인자를 반환한다.
- When: 상태 변경을 실행하려 한다.
- Then: 해당 출력을 실행하지 않고 승인된 operation 경계를 유지한다.
- Given: 관찰 결과 승인된 계획을 그대로 진행할 수 없다.
- When: LLM이 새 계획을 요청한다.
- Then: 제한된 사유 코드를 반환하고 clarification_required로 중단한 뒤 사용자 수정 지시와 새 승인을 기다린다.
- Given: 남은 Tool 호출 수가 pending mutation 수 이하이다.
- When: 다음 행동을 선택한다.
- Then: mutation 실행 예산을 보존하고 부족하면 계획 축소를 요청한다.
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

#### 6.3.1 공통 Skill 선택 흐름

네 capability는 같은 Skill 선택 절차를 사용하지만 선택 이후의 실행 방식은 서로 다르다.

    사용자 요청
    → skill_mode 해석
      - auto: 접근 가능한 enabled Skill 전체를 후보로 사용
      - explicit: skill_id 또는 /<skill-slug>로 지정한 Skill만 사용
      - off: Skill 후보를 비우고 기존 기능 실행
    → 현재 사용자와 Workspace 기준 접근 권한 확인
    → AgentTurnRouter가 action과 호환 Skill을 함께 선택
    → capability와 action 호환성 재검증
    → 선택된 Skill version의 instructions 적용

action과 capability의 호환 관계는 다음과 같다.

| capability | 호환 action | 실행 방식 | 상태 변경 승인 | allowed_tools |
|---|---|---|---|---|
| document-create | markdown_create | 기존 문서 생성 UseCase | 없음 | 비어 있음 |
| document-edit | markdown_edit | 기존 Markdown 편집 UseCase | 없음 | 비어 있음 |
| template | markdown_create 또는 markdown_edit | 기존 문서 생성·편집 UseCase | 없음 | 비어 있음 |
| folder-organize | folder_organize | AgentRun과 bounded ReAct | mutation 전 plan 승인 | capability 허용 Tool의 부분집합 |

명시한 Skill이 없거나 disabled이거나 접근할 수 없으면 오류를 반환한다. auto 후보 중 하나가 명확히 일치하면 해당 Skill을 적용한다. 여러 후보가 비슷하면 실행하지 않고 Skill 하나 또는 Skill 없이 계속할지를 사용자에게 묻는다. 호환되는 Skill이 없으면 Skill 없이 기존 action을 실행한다. chat_answer에는 Skill을 적용하지 않는다.

Skill instructions는 system·developer 정책, Backend 권한·승인·안전 정책과 사용자 요청보다 낮은 우선순위로 조립한다. 선택 이후에는 정확한 published Skill version을 사용하며 실행 중 enabled version이 바뀌어도 현재 요청에는 영향을 주지 않는다.

#### 6.3.2 document-create 예시: 회의록 작성 Skill

`회의록 작성` Skill은 회의록의 구조와 사실성 규칙을 제공한다. 사용자는 자연어로 요청하거나 Skill을 명시할 수 있다.

    사용자:
    오늘 논의한 내용으로 회의록을 만들어줘.

    Agent:
    `회의록 작성` Skill을 적용해 대화에서 확인된 내용으로 회의록을 작성하겠습니다.
    확인되지 않은 참석자, 결정 사항, 담당자와 기한은 임의로 만들지 않습니다.

    Agent:
    # 2026-08-04 제품 회의록

    ## 참석자
    - 김OO

    ## 논의 내용
    - Agent가 사용할 수 있는 Tool과 권한 경계를 검토했습니다.

    ## 결정 사항
    - Agent는 사용자가 직접 수행할 수 있는 범위의 작업만 실행합니다.

    ## 후속 작업
    - Backend Tool Gateway 구현 — 담당: 김OO, 기한: 2026-08-08

명시적 호출은 같은 방식으로 실행한다.

    사용자:
    /meeting-minutes 오늘 논의한 내용으로 회의록을 만들어줘.

    Agent:
    요청한 `회의록 작성` Skill을 적용해 회의록을 작성했습니다.

이 요청은 업무 Tool로 폴더나 기존 문서 상태를 변경하지 않으므로 별도 계획과 승인을 만들지 않는다. Router는 `markdown_create`와 Skill을 선택하고 기존 문서 생성 UseCase에 Skill instructions를 전달한다. 생성과 저장 권한은 기존 Backend 경계를 그대로 사용한다.

여러 생성 Skill이 비슷하게 일치하면 결과를 만들기 전에 다음과 같이 묻는다.

    Agent:
    이 요청에는 `회의록 작성`과 `간단 회의 요약` Skill이 모두 비슷하게 맞습니다.
    어느 Skill을 사용할까요? Skill 없이 작성할 수도 있습니다.

#### 6.3.3 document-edit 예시: 공식 문체 교정 Skill

`공식 문체 교정` Skill은 의미와 Markdown 구조를 유지하면서 선택 영역의 문체만 변경한다.

    사용자:
    이 문단을 공식적인 문체로 정리해줘.

    Agent:
    `공식 문체 교정` Skill을 적용하겠습니다.
    선택한 문단의 의미, 수치, 링크, 코드와 고유명사는 유지합니다.

    변경 전:
    이번에 우리가 만든 기능은 여러 상황에서 꽤 잘 쓸 수 있을 것 같아요.

    변경 후:
    이번에 구현한 기능은 다양한 상황에서 활용할 수 있습니다.

명시적 호출은 `/formal-writing 선택한 문단을 공식적인 문체로 정리해줘`처럼 입력한다. 활성 문서가 없으면 Agent는 추측하지 않고 문서를 열어 달라고 요청한다. 선택 범위가 있으면 해당 범위, 현재 섹션이 있으면 해당 섹션, 둘 다 없으면 활성 문서 전체를 편집 대상으로 삼는다.

이 요청도 AgentRun 승인 대상이 아니다. Router는 `markdown_edit`와 `style_change`를 선택하고 기존 Markdown 편집 UseCase가 변경안을 만든다. 실제 반영 시 기존 문서 편집 권한, `base_version`과 충돌 검사를 우회하지 않는다.

#### 6.3.4 template 예시: 주간보고서 Template Skill

`주간보고서 Template` Skill은 제공된 업무 내용을 정해진 섹션 순서로 작성한다.

    사용자:
    이번 주 대화 내용을 바탕으로 주간보고서를 새로 만들어줘.

    Agent:
    `주간보고서 Template` Skill을 적용하겠습니다.
    대화에서 확인된 내용만 사용하고, 정보가 없는 항목은 비워 두겠습니다.

    Agent:
    # 주간보고서

    ## 이번 주 목표
    - Agent Skill 실행 구조 확정

    ## 완료한 작업
    - bounded ReAct 실행 경계 구현

    ## 진행 중인 작업
    - 사용자 승인 흐름 문서화

    ## 문제와 위험
    - Backend와 Frontend 통합 검증이 남아 있음

    ## 다음 주 계획
    - 통합 테스트 수행

명시적 호출은 `/weekly-report 이번 주 업무 내용으로 새 주간보고서를 만들어줘`처럼 입력한다. 이 요청은 `markdown_create` 경로를 사용하므로 폴더 정리 계획과 승인을 만들지 않는다.

현재 구현은 기존 문서 전체를 외부 Template 구조로 재구성하는 `template_transform`을 지원하지 않는다. 이 요청은 일반 편집으로 추측하지 않고 다음과 같이 안내한다.

    사용자:
    기존 문서를 회사 주간보고서 Template 구조로 전부 다시 배치해줘.

    Agent:
    기존 문서 전체를 외부 Template 구조로 재배치하는 기능은 현재 지원하지 않습니다.
    선택 영역이나 현재 섹션의 일반 편집은 가능하지만, 전체 재구성은 실행하지 않았습니다.

#### 6.3.5 folder-organize 예시: 프로젝트 자료 정리 Skill

`프로젝트 자료 정리` Skill은 관련성이 명확한 문서만 프로젝트 폴더로 옮긴다. 폴더와 문서 배치를 변경하므로 계획, 사용자 승인, bounded ReAct 실행과 결과 검증을 거친다.

##### 요청과 계획 생성

    사용자:
    Alpha 프로젝트 관련 문서를 한 폴더로 정리해줘.

    Agent:
    `프로젝트 자료 정리` Skill을 적용하겠습니다.
    현재 폴더와 문서를 확인한 뒤 변경 계획을 제안할게요.
    계획을 승인하기 전에는 폴더나 문서를 변경하지 않습니다.

Agent는 읽기 Tool로 현재 hierarchy와 대상 문서를 조사한다. Planner LLM은 조사 결과와 Skill instructions를 바탕으로 operation을 만들고, 시스템은 각 대상 ID, `base_version`, 의존 관계와 `allowed_tools`를 검증한다.

    Agent:
    다음과 같이 정리하려고 합니다.

    1. 루트에 `Alpha` 폴더를 생성합니다.
       이유: 같은 이름의 프로젝트 폴더가 현재 없습니다.
    2. `Alpha 기획서`를 새 `Alpha` 폴더로 이동합니다.
       이유: 문서 제목과 내용에서 Alpha 프로젝트 관련 문서임을 확인했습니다.

    예상 결과:
    /
    └─ Alpha/
       └─ Alpha 기획서

    다른 문서와 폴더는 변경하지 않습니다.
    이 계획을 승인하면 실행을 시작합니다.
    [승인] [수정 요청] [취소]

화면에는 자연어 설명과 함께 승인 대상 `plan_version`과 `operations_hash`가 결합된 계획 카드를 표시한다. 내부 operation은 다음과 같다.

    operation 1: Alpha 폴더 생성
      tool: create_folder
      depends_on: []

    operation 2: Alpha 기획서 이동
      tool: move_document
      depends_on: [operation 1]
      destination: operation 1의 생성 결과 id

##### 계획 수정과 승인

사용자는 버튼이나 같은 채팅의 자연어로 승인할 수 있다. 자연어 승인은 승인 대기 중인 계획이 정확히 하나일 때만 허용한다.

    사용자:
    Alpha 회의록도 같이 옮기는 계획으로 바꿔줘.

    Agent:
    기존 계획은 실행하지 않고 수정하겠습니다.
    `Alpha 회의록`이 관련 문서인지 확인한 뒤 새 계획을 보여드릴게요.

수정 요청은 기존 plan을 덮어쓰지 않고 새 `plan_version`과 `operations_hash`를 만든다. 이전 승인은 무효이며 사용자는 변경된 전체 계획을 다시 승인해야 한다.

    사용자:
    승인해줘.

    Agent:
    승인된 계획으로 실행을 시작합니다.
    계획에 없는 작업이나 승인된 인자와 다른 변경은 수행하지 않습니다.

##### 승인 후 ReAct 실행

승인 후 LLM은 최근 Tool 결과를 관찰하고 다음 행동을 하나씩 고른다. LLM이 새 mutation을 자유롭게 만드는 것이 아니라, 승인된 operation 중 현재 실행 가능한 하나를 선택한다.

    Agent:
    `Alpha` 폴더를 생성하고 있습니다.

    Agent:
    ✓ `Alpha` 폴더를 생성했습니다.
    `Alpha 기획서`를 새 폴더로 이동하고 있습니다.

    Agent:
    ✓ `Alpha 기획서`를 이동했습니다.
    최종 폴더 구조를 확인하고 있습니다.

    Agent:
    ✓ 최종 폴더 구조와 문서 위치를 확인했습니다.
    요청한 정리가 완료됐습니다.

각 mutation의 Tool과 arguments는 LLM 응답이 아니라 승인된 operation에서 읽는다. Backend는 호출할 때마다 실행 사용자의 현재 Workspace·리소스 권한, plan version·hash, `base_version`과 `Idempotency-Key`를 다시 검사한다. 남은 operation이 없으면 추가 LLM 호출 없이 실제 hierarchy 검증으로 전환한다.

##### 실행 중 계획을 계속 사용할 수 없는 경우

계획 후 대상 version이나 hierarchy가 바뀌어 승인된 계획을 안전하게 계속할 수 없으면 Agent는 임의로 우회하지 않는다.

    Agent:
    `Alpha 기획서`의 위치가 계획 승인 후 변경되어 현재 계획을 그대로 실행할 수 없습니다.
    실행을 중단했습니다. 현재 상태를 다시 확인해 새 계획을 만들까요?

사용자가 동의하면 `state_changed` 사유로 새 계획을 만들고 다시 설명한 뒤 승인을 요청한다. Tool 호출 예산이 pending mutation 수보다 적으면 `react_tool_budget_insufficient`로 중단하고 작업 범위를 줄인 새 계획을 요청한다.

Skill은 `allowed_tools`를 넓히지 못한다. 예를 들어 Skill이 `create_folder`와 `move_document`만 허용한다면 rename Tool은 사용할 수 없다. 삭제, SQL, shell, 범용 HTTP Tool은 capability에 존재하지 않으므로 instructions에 적혀 있어도 실행할 수 없다.

### 6.4 Skill 생성·수정·활성화 흐름

MVP에서는 Skill 관리 화면에서 Skill을 만든다. 채팅에서 “회의록 Skill을 만들어줘”라고 요청해 LLM이 Skill을 자동 생성·저장하는 기능은 포함하지 않는다.

#### 6.4.1 새 Skill 생성

사용자는 다음 정보를 입력한다.

    이름: 회의록 작성
    slug: meeting-minutes
    범위: 개인
    설명: 대화 내용을 정해진 회의록 구조로 작성한다.
    capability: document-create
    instructions:
      - 제목, 일시, 참석자, 논의 내용, 결정 사항, 후속 작업 순서로 작성한다.
      - 확인되지 않은 사실은 만들지 않는다.
      - 담당자와 기한은 제공된 경우에만 표시한다.
    allowed_tools: []

개인 Skill은 본인만 생성·관리·사용할 수 있다. 팀 Skill은 Workspace owner/editor만 생성·관리하며 Workspace 멤버가 사용할 수 있다.

    사용자:
    [초안 저장]

    시스템:
    `회의록 작성` Skill 초안을 저장했습니다.
    아직 publish되지 않아 실제 요청에는 적용되지 않습니다.

`POST /skills`는 immutable한 실행 version이 아니라 수정 가능한 draft를 만든다. draft 상태에서는 auto 선택과 명시적 command 대상이 아니다.

#### 6.4.2 lint와 안전성 검사

저장 또는 preview 전에 시스템은 다음 항목을 검사한다.

- 필수 필드와 slug 형식
- capability와 `allowed_tools`의 호환성
- 중복 slug와 접근 범위
- 상위 정책, 승인 또는 권한 우회를 요구하는 instructions
- capability에서 제공하지 않는 Tool 요청

    시스템:
    안전성 검사를 통과했습니다.
    이 Skill은 문서 생성 지침만 제공하며 상태 변경 Tool을 사용하지 않습니다.

검사에 실패하면 publish를 막고 수정할 위치와 이유를 표시한다. 예를 들어 instructions에 “승인 없이 문서를 이동한다”가 포함되면 해당 지시는 상위 승인 정책을 바꿀 수 없다고 안내한다.

#### 6.4.3 preview

사용자는 Skill을 저장하거나 publish하기 전에 현재 입력값의 validation과 safety lint 결과를 확인한다. preview는 LLM으로 예시 문서를 생성하거나 실제 폴더 계획을 실행하는 기능이 아니다.

    사용자:
    [Preview]

    시스템 preview:
    차단 문제 없음
    - capability: document-create
    - allowed_tools: 없음
    - 승인·권한 우회 지시: 없음

`POST /skills/preview`는 입력 정의를 저장하지 않고 `lint_result`와 `has_blocked_issues`를 반환한다. 실제 문서·폴더 조회와 Tool 실행은 하지 않는다. 사용 예시는 publish 후 일반 요청 흐름에서 확인한다.

#### 6.4.4 publish와 활성화

    사용자:
    [Publish]

    시스템:
    `회의록 작성` version 1을 publish했습니다.
    이제 자연어 요청의 auto 후보와 `/meeting-minutes` 명시적 호출에 사용할 수 있습니다.

publish는 차단할 safety issue가 없는 draft를 변경 불가능한 published version으로 만들고, 같은 transaction에서 해당 version을 `enabled_version_id`로 지정한다. 별도의 enable 동작은 disable된 published Skill을 다시 활성화할 때 사용한다.

여러 Skill을 동시에 enabled할 수 있지만 한 요청에는 최대 하나만 적용한다. 비슷한 후보가 여러 개이면 자동으로 임의 선택하지 않고 사용자에게 사용할 Skill을 묻는다.

#### 6.4.5 enabled Skill 수정

enabled version을 직접 수정하지 않는다. 사용자가 편집을 시작하면 새 draft version을 만든다. 새 version을 작성하는 동안 현재 enabled version은 기존 요청에 계속 사용된다.

    시스템:
    `회의록 작성` version 2 초안을 만들었습니다.
    version 1은 새 version을 publish할 때까지 계속 적용됩니다.

version 2가 lint, preview와 publish를 거치면 이후 요청부터 version 2를 사용한다. 이미 시작한 AgentRun은 version 1을 고정해서 사용하며 실행 도중 바뀌지 않는다. disable하면 신규 요청의 후보에서 제외하지만 이미 진행 중인 AgentRun을 임의로 변경하거나 취소하지 않는다. 다시 enable하면 마지막 published version을 후보로 복원한다.

#### 6.4.6 채팅 기반 Skill 생성의 후속 범위

MVP에서 다음 요청은 Skill을 바로 저장하지 않고 관리 화면을 안내한다.

    사용자:
    회의록 작성 Skill을 만들어줘.

    Agent:
    현재 채팅에서는 Skill을 자동 생성하거나 publish할 수 없습니다.
    Skill 관리 화면에서 이름, capability와 instructions를 작성한 뒤 preview할 수 있습니다.

향후 채팅 기반 생성을 지원하더라도 LLM은 draft 제안까지만 수행한다. 사용자 확인 없이 publish하거나 enable하지 않으며, 동일한 lint, preview, 관리 권한 검사를 거쳐야 한다.

### 6.5 AgentRun 저장 모델

AgentRun은 다음 테이블로 관리한다.

    agent_runs
    agent_plans
    agent_plan_operations
    agent_approvals
    agent_jobs
    agent_tool_executions

AgentRun은 사용자·Workspace, action, 선택 Skill version, 상태와 현재 plan을 저장한다. Plan은 version, summary, canonical operation hash를 저장한다. Operation은 대상 ID·version·위치·인자·이유·의존 관계를 저장한다. 승인과 tool 실행은 plan·operation에 연결한다.

문서 본문, 전체 prompt, 인증 token은 AgentRun 감사 데이터에 저장하지 않는다.

### 6.6 비동기 Worker

별도 llmPipeline worker는 PostgreSQL row lock으로 job을 lease한다. Worker가 중단되면 lease 만료 후 다른 worker가 가져가며 동일한 Idempotency-Key로 중복 변경을 방지한다.

- heartbeat: 30초
- lease: 90초
- Worker job 최대 시도: 3회
- retryable operation 재시도: 최대 2회

### 6.7 사용자 권한 경계

    capability 허용 tool
    ∩ Skill allowed_tools
    ∩ AgentRun 사용자의 현재 권한
    ∩ 승인된 plan operation

llmPipeline은 DB를 직접 변경하지 않는다. Spring backend는 내부 tool 요청마다 service 인증, AgentRun·operation, 승인된 plan version·hash, Workspace, 실행 사용자, 현재 멤버십·리소스 권한, 대상 version과 tool 허용 여부를 검사한다.

Backend→llmPipeline Agent·Skill 요청과 llmPipeline Worker→Backend tool 요청은 모두
`X-Agent-Service-Token`에 `AGENT_INTERNAL_TOKEN`을 전달해 service를 인증한다. 이 token은
사용자 권한을 대신하지 않으며 양쪽 모두 요청의 사용자·Workspace 권한을 별도로 검증한다.

실제 변경은 기존 FolderService, DocumentService, DocumentPlacementService를 사용한다. Agent 경로는 기존 권한, version, idempotency, hierarchy 검증을 우회할 수 없다. 계획 후 권한이 회수되면 해당 operation은 forbidden이 된다.

### 6.8 계획·승인·실행

사용자는 operation JSON을 직접 편집하지 않고 자연어로 계획 수정을 요청한다. 수정은 같은 AgentRun에 새 plan version을 만들고 기존 승인을 무효화한다.

Worker는 최근 실행 결과를 관찰한 뒤 LLM이 다음 read 또는 실행 가능한 승인 operation 하나를 선택하는 bounded ReAct loop를 수행한다. 상태 변경 tool과 인자는 승인된 plan에서만 가져오며 남은 mutation의 Tool 호출 예산을 우선 보존한다. 실패한 operation의 의존 작업은 skipped로 처리하고 독립 작업은 계속한다. 계획 변경이 필요하면 제한된 사유 코드와 함께 clarification_required로 중단하며 새 plan version과 hash를 다시 승인받는다. 남은 operation이 없으면 LLM 호출 없이 검증 단계로 전환한다. 실행 후 관련 상태를 재조회하며 자동 rollback과 자동 복구는 수행하지 않는다.

### 6.9 API

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

### 6.10 UI, 보관, 배포

계획과 승인은 채팅 카드로 제공한다. Frontend는 종료될 때까지 2초 간격으로 AgentRun을 조회하며 화면을 다시 열어도 run_id로 복원한다.

종료된 AgentRun 기록은 90일 보관하고 하루 한 번 정리한다. 종료되지 않은 AgentRun에는 만료를 적용하지 않는다.

기능은 Workspace별 rollout 없이 한 번에 배포한다. AGENT_SKILLS_ENABLED=false는 신규 Skill 선택과 AgentRun 생성을 차단하되 기존 query/create/edit는 유지한다.

### 6.11 주요 설계 결정

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

#### DEC-009 승인 경계 안의 bounded ReAct 실행

- 결정: 승인 후에는 LLM이 관찰 결과를 바탕으로 다음 read 또는 실행 가능한 승인 operation 하나를 선택한다.
- 이유: 계획의 목표와 승인 경계를 유지하면서 실행 결과에 따라 다음 행동을 조정한다.
- 대안: 승인 후 operation 순서를 코드가 고정 실행하거나 LLM이 새 mutation을 자유롭게 생성한다.
- 영향: 독립 operation 병렬 실행을 포기하며, 계획 변경 시 중단 후 새 version과 hash를 재승인해야 한다.

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
| REQ-006 | ReAct 선택 경계·멱등성·재시도·충돌 | 관찰 기반 다음 행동·부분 실패 | Pending |
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
- [ ] 관찰 기반 다음 행동, 의존 순서, 부분 실패, 취소를 확인한다.
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
- 자동 검증: llmPipeline `665 passed, 49 subtests passed`, Compose config와 Python 구문 검사 통과
- 남은 문제: Backend migration·양방향 service 인증·Tool Gateway와 Frontend UI/E2E
- 후속 작업:
  1. 요구사항 테스트부터 작성한다.
  2. 작업 계획 순서대로 구현한다.
  3. 요구사항별 자동·수동 검증을 실행한다.
  4. 검증 결과를 기록하고 상태를 Verified로 변경한다.
