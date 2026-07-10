---
name: review
description: Use for code review before or after PR creation. Triggers include "리뷰해줘", "코드리뷰", "PR 리뷰", "결실봇 리뷰", "review", "gh review". With no PR number, review local changes and print findings to the terminal. With a PR number, review the PR and post GitHub inline comments. Report only high-confidence findings, focused on bugs, consistency, and security.
allowed-tools: Bash(git *) Bash(gh *) Read Grep Glob
---

# Review Procedure

Interpret `$ARGUMENTS` as follows.

- If the first token is a number, treat it as the PR number and use PR mode. Otherwise use local mode.
- If text remains after removing the PR number, treat it as the review focus.
- Write all user-facing output and GitHub comments in Korean.

## Tone And Format

- PR mode: use the header `🧶 결실봇 코드리뷰`. Use a plain Korean `~해요` tone. The opening summary should be one or two lines that only state what changed and what was reviewed.
- Local mode: do not use the 결실봇 tone, greetings, or an opening summary. Focus only on issues and fixes. Use the header `코드리뷰`.
- Common: no greetings or praise. Keep finding details, severity, and line numbers accurate.

## Step 1 - Get Diff

- Local mode: review uncommitted work. Check `git diff`, `git diff --cached`, and `git status --short`. If both diffs are empty, print `커밋하지 않은 변경이 없습니다.` and stop.
- PR mode: inspect `gh pr diff <PR 번호>` and `gh pr view <PR 번호> --json title,body`.

## Step 2 - Review

Review every changed file. Do not skip files because the diff is large. Narrow the scope only when the user explicitly asks for a limited scope, and state that scope.

Inspect only changed lines for findings. Read surrounding code only for context.

If a focus instruction is present, still review the full diff and inspect the requested area, file, or topic more deeply. If the user says `only` or `만`, review only that scope and state that the rest is out of scope.

In PR mode, use the PR body as the requirement baseline. In local mode, infer intent from changed code and comments. If intent is unclear, ask the user once.

Report only high-confidence findings. Do not include ambiguous or preference-only comments. Prioritize findings in this order:

1. Bugs/logic: null references, inverted conditions, swallowed exceptions, transaction boundaries, ignored return values.
2. Requirements: missing or incorrect behavior compared with the commit or PR description.
3. Consistency: schema/entity/DTO/API field or type mismatches, naming, and contract drift.
4. Security: injection, missing authentication/authorization, hardcoded secrets, leaked secrets in logs.
5. Edge cases: empty/null/boundary values, concurrency, external call failures.
6. Performance: N+1 queries, DB calls in loops, large full-load operations.
7. Tests: missing or ineffective tests for changed behavior.
8. CLAUDE.md/AGENTS.md: scope creep, speculative code, unnecessary defensive code.
9. Refactoring: behavior-preserving improvements. Mark only concrete suggestions with `♻️`, such as removing duplication, reusing existing code/utilities, deleting dead code, or removing over-abstraction.

## Step 3 - Output

Use `🚨 Critical`, `⚠️ Warning`, `💡 Info`, and `♻️ Refactoring` severity groups. `🚨⚠️💡` are correctness or quality issues; `♻️` is for behavior-preserving refactoring suggestions.

Do not list everything verified. Summarize verification briefly in one line and focus on findings.

### Local Mode

Group findings by severity, not by file. Omit empty severity sections.

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

If there are no findings, print `변경분에서 문제를 발견하지 못했습니다.`

### PR Mode

- Use GitHub Review API for inline comments when posting to GitHub.
- Put comments only on real diff lines with `side: "RIGHT"`.
- Prefix each comment with severity.
- Do not repeat the PR title in the summary body.
- If there are no findings and the user asked to post the review, use `"event": "APPROVE"`.
- Never merge PRs.
- The opening summary goes in a Markdown blockquote. Findings are not blockquoted.

```bash
gh repo view --json nameWithOwner
echo '<JSON>' | gh api repos/{owner}/{repo}/pulls/{PR 번호}/reviews --method POST --input -
```

```json
{
  "body": "> 무엇을 바꿨고 무엇을 확인했는지 한두 줄로 요약해요.\n\n지적 요약 또는 발견 사항 없음",
  "event": "COMMENT",
  "comments": [
    {
      "path": "파일경로",
      "line": 123,
      "side": "RIGHT",
      "body": "🚨 문제 설명과 수정 방향"
    }
  ]
}
```

If there are no findings, post with `"event": "APPROVE"`. If GitHub blocks self-approval for the current account, post the same summary with `"event": "COMMENT"` and tell the user that the approval event was blocked by the self-approval restriction.
