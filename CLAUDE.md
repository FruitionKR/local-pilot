#  CLAUDE 작업 지침

## 1. Instruction Language

**Use English for agent-control rules. Keep Korean for user-facing work.**

- Write agent behavior constraints in short English imperatives when possible.
- Write user-facing explanations, commit messages, changelog entries, and PR titles/bodies in Korean.
- Write code comments and documentation prose in Korean unless there is a clear reason not to.
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

## 8. 문서 관리

**Keep only `docs/{architecture,api,data-model,demo-script}.md` and `docs/adr/` as current docs. Archive everything else to `docs/backlog/`. Do not create dated issue or changelog docs.**

- 현행 문서는 4개만 유지한다: `docs/architecture.md`(서비스 경계·통신·인가·배포), `docs/api.md`(API 계약 요약), `docs/data-model.md`(저장소·테이블 소유권), `docs/demo-script.md`(구동·데모 절차).
- 문서화할 만큼 중요한 아키텍처 결정은 `docs/adr/NNNN-<kebab-제목>.md`로 기록한다. 형식: 맥락 → 결정 → 대안과 기각 사유 → 결과.
- 코드 변경으로 현행 문서 내용이 실제와 달라지면, 해당 문서를 같은 커밋에서 갱신한다.
- 유효하지 않게 됐거나 역사 기록용이 된 문서는 `docs/backlog/`로 이관하고 `docs/backlog/README.md` 목록을 갱신한다.
- 날짜별 이슈 문서(`docs/issue/`)와 역할별 changelog(`docs/changelog/`) 운영은 2026-08-07에 종료했다. 전부 `docs/backlog/` 아래에 보관돼 있으며, 새로 만들지 않는다.

## 9. 하위 디렉터리 CLAUDE.md 확인

**Before starting work in a subdirectory, check for and read its CLAUDE.md.**

- 특정 폴더 내부 파일을 작업 대상으로 받으면, 해당 폴더에 `CLAUDE.md`가 있는지 먼저 확인한다.
- `CLAUDE.md`가 존재하면 반드시 읽은 뒤 작업을 시작한다.
- 폴더별 `CLAUDE.md`의 지침은 루트 `CLAUDE.md`보다 우선 적용한다.

## 10. 조사 우선

**Study prior art before designing. Check installed dependencies before adding new ones.**

- 해결책을 설계하기 전에, 이미 자리 잡은 제품들이 같은 문제를 어떻게 푸는지 먼저 살펴본다. 접근 방식을 처음부터 발명하지 말고 검증된 패턴과 관례를 채택한다.
- 검증되고 유지보수되는 라이브러리가 전체 복잡도를 낮추거나 안정성을 높인다면 그것을 쓴다. 흔한 기능을 명확한 이유 없이 재구현하지 않는다.
- 직접 구현하거나 패키지를 추가하기 전에 이미 설치된 의존성부터 확인한다. 문서와 타입을 확인하지 않은 채 "이 라이브러리엔 그 기능이 없다"고 단정하지 않는다.

## 11. 아키텍처 원칙

**No compatibility layers for paths you replace. Grow the system in layers. Decide for the long term.**

- 무언가를 교체할 때 하위 호환을 위한 레이어·폴백·마이그레이션 경로를 새로 만들지 않는다. 내가 대체한 경로는 그 변경에서 함께 삭제한다. 단, 내 변경과 무관한 기존 데드코드는 §4에 따라 언급만 하고 남겨둔다.
- 시스템은 레이어로 키운다. 엔드투엔드로 동작하는 최소 버전에서 시작해 이미 동작하는 결과물 위에 기능을 하나씩 얹는다. 동작하는 코드를 미완성 복잡도와 맞바꾸지 않는다.
- 아키텍처 결정은 장기 관점으로 한다. 지금만 넘기고 나중에 교체할 임시방편을 받아들이지 않는다.
