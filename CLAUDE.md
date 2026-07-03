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

## 7. 커밋 메시지

- 커밋 메시지는 `fix:`, `feat:`, `docs:`, `chore:`, `refactor:`, `test:` 같은 Conventional Commits 형식을 사용한다.
- 커밋 메시지 제목과 본문 설명은 한글로 작성한다.
- Conventional Commits 접두사, 브랜치명, 명령어, 파일명, API 이름처럼 원문 유지가 필요한 값은 영어를 그대로 사용한다.
- 제목은 간결하게 작성하고, 한 커밋이 담는 변경 범위를 한글로 명확히 표현한다.
- 예: `feat: 로컬 Docker 개발 환경 추가`

## 8. 커밋 시 changelog 갱신

- changelog는 파일 수정 직후가 아니라 커밋을 준비하거나 생성할 때 갱신한다.
- 프론트엔드, 백엔드, AI/pipeline 기능 코드의 수정 또는 추가가 포함된 커밋에만 `docs/changelog/` 아래의 관련 changelog를 갱신한다.
- Java/Spring 백엔드 기능 코드 변경은 `docs/changelog/backend.md`에 기록한다.
- 프론트엔드 기능 코드 변경은 `docs/changelog/frontend.md`에 기록한다.
- AI/pipeline(llmPipeline) 기능 코드 변경은 `docs/changelog/ai.md`에 기록한다.
- 인프라, DevOps, Docker, 배포 환경 코드 또는 설정 변경은 `docs/changelog/infra.md`에 기록한다.
- 여러 기능 영역에 걸친 코드 변경은 해당하는 changelog를 모두 갱신한다.
- 이슈 문서 정리, 작업 지침 변경, 단순 문서 이동처럼 기능 코드 변경이 없는 커밋은 changelog를 갱신하지 않는다.
- changelog에는 변경 배경, 추가/변경된 내용, 검증 결과 또는 남은 주의사항을 한글로 간결하게 정리한다.

## 9. 커밋/PR 전 보안 점검

**Stop before commit or PR if real secrets are found.**

- 커밋 생성 전과 PR 생성 전에는 항상 변경분과 추적 대상 파일에 실제 비밀값이 포함되어 있지 않은지 점검한다.
- 점검 대상에는 API key, access key, secret key, token, password, private key, credential, 인증서/keystore, 실제 `.env` 파일, 운영 endpoint나 계정 정보가 포함된다.
- `git status --short`, `git diff --cached`, `git diff`, `git ls-files`와 검색 도구를 활용해 커밋에 포함될 파일과 PR 변경분을 확인한다.
- `.env.example`, 문서, 테스트 fixture에 있는 예시값과 placeholder는 실제 비밀값과 구분하되, 실제 값처럼 보이거나 혼동 가능성이 있으면 사용자에게 확인한다.
- 비밀값 또는 공개하면 안 되는 정보가 발견되면 커밋이나 PR 생성을 중단하고, 어떤 파일과 항목이 문제인지 사용자에게 보고한 뒤 제거 또는 교체가 완료된 경우에만 진행한다.
- 외부 코드 리뷰 도구나 public repo에 PR을 올리기 전에는 저장소 전체가 외부 시스템에 노출되어도 되는지 확인하고, 문제가 없을 때만 진행한다.

## 10. 이슈 문서 관리

- 이슈 문서는 항상 작업 당일 날짜 기준의 `docs/issue/YYYY-MM-DD.md`에 작성하거나 갱신한다.
- 지난 날짜의 이슈 문서에 남아 있는 내용을 새 날짜 기준으로 다시 관리해야 하면, 새 날짜 문서로 내용을 옮기고 기존 문서에는 이동 안내와 링크만 남긴다.
- 이슈 해결을 위해 다른 문서에 API 계약, 설계, 절차, 정책 같은 구체적인 내용을 적었다면, 이슈 문서에도 해당 문서 경로와 확인 가능한 위치(섹션명 또는 line 위치)를 함께 기록한다.
- 다른 문서의 위치를 기록할 때는 추후 검색과 검토가 쉽도록 파일 경로, 섹션 제목, 핵심 endpoint/API 이름을 가능한 한 함께 남긴다.

## 11. 브랜치와 PR 흐름

**Do not work directly on `dev` for feature or fix changes. Never merge PRs into `main`.**

- 기능 개발이나 수정 작업은 `dev`에서 직접 진행하지 않고, 작업 목적이 드러나는 별도 브랜치를 생성해 진행한다.
- 기능 브랜치 작업이 완료되면 `dev`를 대상으로 PR을 생성한다.
- PR 생성 전, 각 커밋의 변경 내용이 기능 단위로 분리되어 있는지 확인한다.
- 서로 다른 기능이 한 PR에 섞이지 않도록, 같은 기능에 속하는 변경만 묶어 PR을 나눠서 작성한다.
- PR을 작성하기 전에는 항상 전체 변경분이 기능별로 제대로 분할되어 있는지 다시 확인한 뒤 진행한다.
- `dev`에서는 전체 검사와 테스트를 실행해 변경사항이 합당한지 확인한다.
- `dev` 검증이 통과하고 main 반영이 적절하다고 판단되면 `main`을 대상으로 PR을 생성한다.
- PR 제목과 본문은 한글로 작성한다.
- PR 본문에는 변경 요약, 테스트 결과, 검토자가 알아야 할 주의사항을 한글로 정리한다.
- `main` 대상 PR은 agent가 생성과 상태 확인까지만 수행한다.
- `main` 대상 PR의 승인, 병합, merge queue 실행, 배포 반영 등 최종 실행은 반드시 사용자가 직접 수행한다.
- 사용자가 별도로 요청하더라도 agent는 `main` 대상 PR을 병합하지 않는다.

## 12. 설명과 주석

- 사용자에게 전달하는 설명은 한글로 작성한다.
- 코드 주석과 문서 설명도 특별한 이유가 없으면 한글로 작성한다.
- 외부 표준, API 이름, 설정 키, 명령어는 원문을 유지한다.

## 13. 하위 디렉터리 CLAUDE.md 확인

**Before starting work in a subdirectory, check for and read its CLAUDE.md.**

- 특정 폴더 내부 파일을 작업 대상으로 받으면, 해당 폴더에 `CLAUDE.md`가 있는지 먼저 확인한다.
- `CLAUDE.md`가 존재하면 반드시 읽은 뒤 작업을 시작한다.
- 폴더별 `CLAUDE.md`의 지침은 루트 `CLAUDE.md`보다 우선 적용한다.
