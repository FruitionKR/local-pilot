#  CLAUDE 작업 지침

## 1. Instruction Language

**Use English for agent-control rules. Keep Korean for user-facing work.**

- Write agent behavior constraints in short English imperatives when possible.
- Write user-facing explanations, commit messages, changelog entries, and PR titles/bodies in Korean.
- Keep command names, API names, config keys, file paths, branch names, and Conventional Commits prefixes in their original form.
- If a Korean rule is critical, add a short English equivalent next to it.

## 2. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 3. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.
- Every user-facing answer must be written in Korean.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 4. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 5. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

## 6. 작업 승인

**Do not create or modify files before explicit user approval.**

- 파일을 생성하거나 수정하기 전에는 항상 사용자에게 무엇을 구성하거나 변경할지 먼저 설명한다.
- 설명에는 변경 이유와 예상되는 영향 범위를 포함한다.
- 사용자의 피드백과 명시적인 승인을 받은 뒤에만 write 작업을 진행한다.

## 7. 커밋과 PR

**Before any commit, use the commit skill. Before any PR, use the pr skill. Never merge PRs into `main`.**

- 커밋을 생성하거나 준비할 때는 반드시 `commit` 스킬(`.claude/skills/commit/SKILL.md`)의 절차를 따른다.
- PR을 생성하거나 준비할 때는 반드시 `pr` 스킬(`.claude/skills/pr/SKILL.md`)의 절차를 따른다.

## 8. 이슈 문서 관리

- 이슈 문서는 항상 작업 당일 날짜 기준의 `docs/issue/YYYY-MM-DD.md`에 작성하거나 갱신한다.
- 지난 날짜의 이슈 문서에 남아 있는 내용을 새 날짜 기준으로 다시 관리해야 하면, 새 날짜 문서로 내용을 옮기고 기존 문서에는 이동 안내와 링크만 남긴다.
- 이슈 해결을 위해 다른 문서에 API 계약, 설계, 절차, 정책 같은 구체적인 내용을 적었다면, 이슈 문서에도 해당 문서 경로와 확인 가능한 위치(섹션명 또는 line 위치)를 함께 기록한다.
- 다른 문서의 위치를 기록할 때는 추후 검색과 검토가 쉽도록 파일 경로, 섹션 제목, 핵심 endpoint/API 이름을 가능한 한 함께 남긴다.

## 9. 설명과 주석

- 사용자에게 전달하는 설명은 한글로 작성한다.
- 코드 주석과 문서 설명도 특별한 이유가 없으면 한글로 작성한다.
- 외부 표준, API 이름, 설정 키, 명령어는 원문을 유지한다.

## 10. 하위 디렉터리 CLAUDE.md 확인

**Before starting work in a subdirectory, check for and read its CLAUDE.md.**

- 특정 폴더 내부 파일을 작업 대상으로 받으면, 해당 폴더에 `CLAUDE.md`가 있는지 먼저 확인한다.
- `CLAUDE.md`가 존재하면 반드시 읽은 뒤 작업을 시작한다.
- 폴더별 `CLAUDE.md`의 지침은 루트 `CLAUDE.md`보다 우선 적용한다.
