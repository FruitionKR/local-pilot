# Agent 스킬 사용 가이드

이 저장소에는 반복 작업 절차를 분리한 agent 스킬이 포함되어 있습니다.

- Codex용 스킬: `.codex/skills/`
- Claude Code용 스킬: `.claude/skills/`

두 경로에서 이름이 같은 스킬은 Claude Code용 절차를 기준으로 같은 행동을 유지합니다. Codex용 스킬은 필수 frontmatter, agent 지침 파일명, 내부 스킬 경로처럼 실행 환경에 필요한 표현만 다르게 둡니다. Codex에서는 `.codex/skills/`를 우선 사용하고, Claude Code에서는 `.claude/skills/`를 사용합니다.

## 이 저장소의 스킬

| 스킬 | 하는 일 |
|------|---------|
| `commit` | 보안 점검 -> 한글 Conventional Commits 메시지 작성 -> 커밋 생성 |
| `pr` | 미커밋 변경 확인(있으면 commit 스킬 선행) -> PR 보안 점검 -> 브랜치/PR 흐름 확인 -> PR 작성 |
| `refine-user-story` | 사용자 스토리 한 섹션을 코드·테스트·설정과 대조해 제약조건과 성공·실패 시나리오로 정리 |
| `review` | 다음 PR에 담길 로컬 변경분 또는 GitHub PR을 버그·정합성·보안 중심으로 리뷰 |

## 사용 규칙

- 커밋을 생성하거나 준비할 때는 `commit` 스킬을 사용합니다.
- PR을 생성하거나 준비할 때는 `pr` 스킬을 사용합니다.
- 사용자 스토리를 현재 코드와 대조해 정리할 때는 `refine-user-story` 스킬을 사용합니다.
- 사용자가 "PR 리뷰"라고 명시적으로 지칭하거나 PR 번호/URL을 주면 `review` 스킬을 사용합니다. 일반 리뷰 요청은 각 agent 지침의 직접 검토 절차를 따릅니다.
- `main` 대상 PR은 생성과 상태 확인까지만 수행하고, 병합은 사용자가 직접 합니다.

## 새 스킬 추가

Codex와 Claude Code 양쪽에서 같은 절차를 쓰려면 두 경로에 같은 이름의 스킬을 추가합니다.

```text
.codex/skills/<skill-name>/SKILL.md
.claude/skills/<skill-name>/SKILL.md
```

Codex 스킬의 `SKILL.md`에는 frontmatter의 `name`과 `description`이 필요합니다. `description`에는 스킬이 언제 발동되어야 하는지 명확히 적습니다.
