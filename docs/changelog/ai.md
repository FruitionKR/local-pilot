# AI/Pipeline 변경 기록

## 2026-08-08

### refactor: Skill 작성 재생성 흐름 정리

- Skill 재생성 intent 판정과 생성 재시도를 하나의 bounded loop로 통합했다.
- repository port 반환 타입과 재시도 회귀 테스트를 보강하고, Skill 작성 흐름 문서를 추가했다.
- llmPipeline 전체 테스트 `790 passed, 61 subtests passed`로 검증했다.
