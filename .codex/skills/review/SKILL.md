---
name: review
description: Use when Codex is asked to review local changes or a GitHub PR in this repository. Review changed lines for bugs, requirement mismatches, consistency, security, edge cases, performance, tests, instruction violations, and concrete refactoring opportunities; report only high-confidence findings in Korean.
---

# Review Procedure

Write all review output and comments in Korean.

## 1. Determine Review Mode

- If the user provides a PR number, use PR mode.
- Otherwise, use local mode.
- If the user provides a focus area, review all changed files as usual and inspect that area more deeply.
- If the user explicitly says "only" or "만", limit the review to that scope and state the scope.

## 2. Get The Diff

- Local mode: review uncommitted changes with `git diff`, `git diff --cached`, and `git status --short`.
- If both local diffs are empty, report `커밋하지 않은 변경이 없습니다.` and stop.
- PR mode: review `gh pr diff <number>` and `gh pr view <number> --json title,body`.

## 3. Review Standard

Review every changed file unless the user explicitly narrows the scope. Inspect only added or modified lines for findings; read surrounding code only for context.

Report only high-confidence issues. Do not include vague preferences.

Prioritize findings in this order:

1. Bugs and logic errors: null references, inverted conditions, swallowed exceptions, transaction boundaries, ignored return values.
2. Requirements: missing or incorrect implementation compared with the request, commit, or PR body.
3. Consistency: schema, entity, DTO, API fields/types, naming, and contract mismatches.
4. Security: injection, missing authentication/authorization, hardcoded secrets, leaked secrets in logs.
5. Edge cases: empty/null/boundary values, concurrency, external call failures.
6. Performance: N+1 queries, DB calls inside loops, full loads of large data.
7. Tests: missing or ineffective tests for changed behavior.
8. Repository instructions: scope creep, speculative code, unnecessary defensive code.
9. Refactoring: behavior-preserving improvements such as removing duplication, reusing existing utilities, reducing complexity, deleting dead code, or removing over-abstraction. Mark these with `♻️`.

## 4. Local Output Format

Use this format. Omit empty severity sections.

```markdown
## 코드리뷰

**🚨 필수 수정**
- `파일:line` — 문제와 수정 방향

**⚠️ 권장**
- `파일:line` — 문제와 수정 방향

**💡 참고**
- `파일:line` — 문제와 수정 방향

**♻️ 리팩터링**
- `파일:line` — 무엇을 무엇으로 바꿀지

총계: 🚨 N ⚠️ N 💡 N ♻️ N
```

If there are no findings, write `변경분에서 문제를 발견하지 못했습니다.`

## 5. PR Review Format

- Use GitHub Review API for inline comments when posting to GitHub.
- Put comments only on real diff lines with `side: "RIGHT"`.
- Prefix each comment with severity.
- Do not repeat the PR title in the summary body.
- If there are no findings and the user asked to post the review, use `"event": "APPROVE"`.
- Never merge PRs.
