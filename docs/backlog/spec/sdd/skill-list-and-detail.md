# Skill 목록 조회 및 상세 편집

## 1. 문서 정보

- 상태: Approved
- 작성일: 2026-08-08
- 관련 이슈: `docs/backlog/issue/backend/2026-08-09.md`의 `Skill 작성·실행 계약을 기존 llmPipeline 흐름과 재정렬`
- 관련 PR:

상태 흐름: `Draft → Approved → In Progress → Verified`

## 2. 배경

사용자가 생성된 Skill을 확인·관리·실행하려면 Skill 관리 탭과 채팅 입력창에서 접근 가능한 Skill을
조회할 수 있어야 한다. Skill 상세 화면은 기존 생성 화면을 재사용하며 저장된 값을 입력란에 채운다.

Spring Backend를 Skill과 version의 단일 소유자로 사용한다. llmPipeline은 Skill을 저장하거나 Spring
DB를 직접 읽지 않고 AI 구체화·보안 검사와 Spring이 전달한 Skill을 이용한 Agent 실행만 담당한다.

## 3. 목표

- 본인 personal Skill과 Workspace team Skill의 목록·상세를 제공한다.
- 상세 화면에서 수정한 내용을 새 version으로 게시한다.
- 자연어 자동 라우팅과 `/command` 직접 실행 상태를 분리한다.
- `/` command prefix 자동완성을 제공한다.
- Skill을 soft delete하고 version·검사 이력을 보존한다.
- Spring이 Skill과 version을 저장하고 Agent 실행 definition을 llmPipeline에 전달한다.

## 4. 범위

### 포함

- 목록: team → personal → command 오름차순, 빈 목록은 정상 응답
- 목록 필드: ID, command, 이름, 설명, scope, 자동 라우팅, 관리·삭제 가능 여부
- 상세: command, 이름, 지시사항, scope, 참조 문서, description, capabilities, allowed tools
- personal은 소유자만, team은 `OWNER`·`MEMBER` 모두 생성·조회·수정·실행·삭제
- command·scope 변경과 `team → personal` 요청자 소유 전환
- 참조 문서 snapshot과 `available/unavailable` 판정
- 자연어 자동 라우팅 멱등 변경
- `/` 자동완성 최대 10개, 자동 라우팅 OFF Skill 포함
- 자연어 자동 후보 최근 수정 순 최대 20개
- soft delete와 command 즉시 재사용
- Spring DB의 Skill·version 관리와 Agent snapshot 전달

### 제외

- 다른 사용자의 personal Skill 조회
- 일괄 변경·삭제, 삭제 복구·목록, 과거 version UI
- 실행 이력·사용 횟수, 목록 pagination, 이름·설명 자동완성
- llmPipeline·Frontend 구현

## 5. 요구사항

### REQ-001 목록 조회

시스템은 현재 사용자의 personal Skill과 Workspace team Skill 중 미삭제이며 version이 존재하는 Skill을
team, personal, command 순으로 반환해야 한다. 결과가 없으면 빈 배열을 반환한다.

### REQ-002 목록 권한 정보

각 항목은 `can_manage`, `can_delete`를 포함한다. personal은 소유자에게만 노출하고 team은 모든
Workspace 구성원에게 관리·삭제 권한을 제공한다.

### REQ-003 상세 조회

접근 가능한 Skill의 최신 version과 생성 화면 복원 값을 반환한다. 접근 불가·삭제 Skill은 `404`다.

### REQ-004 참조 문서 상태

version의 참조 snapshot을 현재 Document와 비교해 `available/unavailable`을 반환한다. unavailable 참조가
남아 있으면 재검토·업데이트를 거부한다.

### REQ-005 새 version 게시

published version을 수정하지 않고 검토된 변경을 새 version으로 저장한다. 검토 실패 시 저장하지 않는다.

### REQ-006 동시 수정 충돌

게시 시 최신 version이 `base_version_id`와 다르면 `409 SKILL_VERSION_CONFLICT`를 반환한다.

### REQ-007 command·scope 변경

command와 scope를 변경할 수 있다. `team → personal`이면 요청자가 소유자가 되며 변경된 접근 범위로
command 중복을 다시 검사한다.

### REQ-008 자동 라우팅

`auto_routing_enabled=false`는 자연어 후보에서만 제외한다. 목록·자동완성·`/command` 직접 실행은
유지하며 같은 상태 재요청은 성공한다.

### REQ-009 command 자동완성

빈 prefix 또는 command prefix로 접근 가능한 Skill을 최대 10개 반환한다. 완전 일치, command 오름차순,
team 우선으로 정렬한다.

### REQ-010 직접 실행

접근 가능한 미삭제 Skill은 자동 라우팅 상태와 무관하게 `/command`로 실행할 수 있다.

### REQ-011 soft delete

personal 소유자와 모든 team 구성원이 삭제할 수 있다. 삭제 metadata를 기록하고 신규 조회·실행에서
제외하며 version은 보존한다. 재삭제는 `404`다.

### REQ-012 경계와 장애

비구성원 요청은 DB 변경·llmPipeline 호출 전에 거부한다. AI 호출 장애는 `503`으로 변환한다.

## 6. 설계

### 데이터 모델

`skills`는 `id`, `workspace_id`, `scope_type`, `owner_user_id`, `command`,
`auto_routing_enabled`, `deleted_at`, `deleted_by`, 생성·수정 metadata를 가진다.

`skill_versions`는 `id`, `skill_id`, `version`, 이름·설명·지시사항, capabilities, allowed tools,
참조 문서 snapshot, 검사 결과, definition hash, 생성 metadata를 가진다. 검토 전 초안은 저장하지 않으며
가장 높은 version을 현재 실행 definition으로 사용한다. `enabled_version_id`는 사용하지 않는다.

### 공개 API

- `GET /api/workspaces/{workspaceId}/skills`
- `GET /api/workspaces/{workspaceId}/skills/{skillId}`
- `GET /api/workspaces/{workspaceId}/skills/commands?prefix=`
- `PATCH /api/workspaces/{workspaceId}/skills/{skillId}/auto-routing`
- `PUT /api/workspaces/{workspaceId}/skills/{skillId}`
- `DELETE /api/workspaces/{workspaceId}/skills/{skillId}`

### 생성·수정

llmPipeline은 refine·preview 결과만 반환한다. Spring이 definition hash와 10분 만료 review token을
발급·검증한다. 생성은 Skill과 version 1을, 수정은 Skill identity 변경과 다음 version을 하나의 Spring
transaction에서 저장한다. Skill 행 잠금과 base version 비교로 동시 수정을 막는다.

### Agent 실행

자연어 요청에는 자동 라우팅 ON 후보를 최근 수정 순 최대 20개 전달한다. `/command`는 Spring이
접근 가능한 Skill을 확정해 definition 하나만 전달한다. 실행은 전달 시점의 version snapshot을 사용한다.

### 주요 결정

- DEC-001: Spring이 Skill과 version을 소유한다.
- DEC-002: 현재 version pointer 없이 가장 높은 version을 사용한다.
- DEC-003: Spring이 review token과 게시 transaction을 관리한다.
- DEC-004: llmPipeline은 Spring이 전달한 Skill로 실행한다.
- DEC-005: 자동 라우팅·삭제 상태는 `skills`에 저장한다.

## 7. 작업 계획

별도 문서 `docs/spec/sdd/tasks/skill-list-and-detail-tasks.md`를 따른다.

## 8. 검증

| 요구사항 | 검증 방법 | 결과 |
|---|---|---|
| REQ-001~REQ-004 | repository·service·controller 조회 및 전체 회귀 테스트 | Pass |
| REQ-005~REQ-007 | transaction·version 충돌·command 중복 구현 및 전체 회귀 테스트 | Pass |
| REQ-008~REQ-009 | 자동 라우팅·prefix 구현 및 전체 회귀 테스트 | Pass |
| REQ-010 | llmPipeline Agent 계약 확장 후 E2E | Pending |
| REQ-011 | soft delete·재사용 구현 및 전체 회귀 테스트 | Pass |
| REQ-012 | 인증·membership·AI 오류 회귀 테스트 | Pass |

```sh
cd backend
./gradlew test --tests 'fruition.skill.*'
./gradlew test --tests 'fruition.agent.*'
./gradlew test
```

## 9. 미결정 사항

- 없음

## 10. 결과

- 검증일: 2026-08-08
- 최종 상태: Backend Complete, llmPipeline Agent 계약 Pending
- 남은 문제: llmPipeline의 refine·preview 응답 확장과 Spring 전달 Skill Agent 실행
- 후속 작업: `docs/backlog/issue/backend/2026-08-09.md`의 Skill 작성·실행 계약 재정렬 완료 후 Backend↔llmPipeline E2E
