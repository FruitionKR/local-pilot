# Skill 목록 조회 및 상세 편집 작업 계획

- 관련 SDD: `docs/spec/sdd/skill-list-and-detail.md`
- 구현 범위: Spring Backend·Spring DB
- 제외: llmPipeline·Frontend 코드

## TASK-001 Skill DB migration

- `skills`, `skill_versions`, 제약·index를 추가한다.
- verify: migration 통합 테스트

## TASK-002 Entity·Repository

- Skill·SkillVersion entity와 최신 version, 접근 범위, prefix, 자동 후보 query를 구현한다.
- verify: repository 테스트

## TASK-003 권한·command 정책

- personal 소유자, team 구성원, scope 전환, command 충돌과 transaction lock을 구현한다.
- verify: service 단위·동시성 테스트

## TASK-004 생성 영속화 전환

- llmPipeline은 refine·preview에만 사용하고 Spring이 token과 Skill/version 저장을 담당한다.
- verify: token·원자적 생성 테스트

## TASK-005 목록·상세

- 공개 목록·상세와 참조 문서 상태를 구현한다.
- verify: controller·service 테스트

## TASK-006 자동완성·자동 라우팅

- prefix 최대 10개와 멱등 자동 라우팅 변경을 구현한다.
- verify: 정렬·OFF 직접 실행 테스트

## TASK-007 수정 version

- base version 충돌, identity 변경과 새 version 저장을 구현한다.
- verify: stale 요청 `409`, 이전 version 보존

## TASK-008 soft delete

- 삭제 metadata와 모든 조회의 미삭제 조건을 구현한다.
- verify: 재삭제 `404`, command 재사용

## TASK-009 Agent snapshot

- 자동 후보 최대 20개와 명시적 Skill definition을 `/agent/turn`에 전달한다.
- verify: Agent requester·service 회귀 테스트

## TASK-010 오류·공개 API

- Skill 도메인 오류와 인증·membership 경계를 구현한다.
- verify: controller 오류 계약

## TASK-011 문서·전체 검증

- changelog와 AI 이슈를 동기화한다.
- verify: Skill·Agent 집중 테스트, Backend 전체 테스트, `git diff --check`
