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
- 코드 리뷰: 사용자가 "PR 리뷰"라고 명시적으로 지칭하거나 PR 번호/URL을 줄 때만 `review` 스킬(`.claude/skills/review/SKILL.md`)을 사용한다. 그 외 일반 리뷰 요청은 스킬 없이 커밋된 코드(HEAD) 및 `origin/dev` 로그와 비교해 수정된 코드를 직접 검토한다(`git diff HEAD`, `git diff origin/dev...HEAD`, 신규 파일은 `git status --short`로 확인).

## 8. 문서 관리

**Keep only `docs/{architecture,api,data-model,demo-script}.md` and `docs/adr/` as current docs. Archive everything else to `docs/backlog/`. Do not create dated issue or changelog docs.**

- 현행 문서는 4개만 유지한다: `docs/architecture.md`(서비스 경계·통신·인가·배포), `docs/api.md`(API 계약 요약), `docs/data-model.md`(저장소·테이블 소유권), `docs/demo-script.md`(구동·데모 절차).
- 문서화할 만큼 중요한 아키텍처 결정은 `docs/adr/NNNN-<kebab-제목>.md`로 기록한다. 형식: 맥락 → 결정 → 대안과 기각 사유 → 결과.
- 코드 변경으로 현행 문서 내용이 실제와 달라지면, 해당 문서를 같은 커밋에서 갱신한다.
- 유효하지 않게 됐거나 역사 기록용이 된 문서는 `docs/backlog/`로 이관하고 `docs/backlog/README.md` 목록을 갱신한다.
- 날짜별 이슈 문서(`docs/issue/`)와 역할별 changelog(`docs/changelog/`) 운영은 2026-08-07에 종료했다. 전부 `docs/backlog/` 아래에 보관돼 있으며, 새로 만들지 않는다.

## 9. 설명과 주석

- 사용자에게 전달하는 설명은 한글로 작성한다.
- 코드 주석과 문서 설명도 특별한 이유가 없으면 한글로 작성한다.
- 외부 표준, API 이름, 설정 키, 명령어는 원문을 유지한다.

## 10. 하위 디렉터리 CLAUDE.md 확인

**Before starting work in a subdirectory, check for and read its CLAUDE.md.**

- 특정 폴더 내부 파일을 작업 대상으로 받으면, 해당 폴더에 `CLAUDE.md`가 있는지 먼저 확인한다.
- `CLAUDE.md`가 존재하면 반드시 읽은 뒤 작업을 시작한다.
- 폴더별 `CLAUDE.md`의 지침은 루트 `CLAUDE.md`보다 우선 적용한다.
