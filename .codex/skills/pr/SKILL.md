---
name: pr
description: Use when Codex is asked to create, prepare, update, inspect, push changes for, or help with a pull request in this repository. Triggers include "PR 만들어줘", "PR 올려줘", "PR 갱신", "push해줘", "dev rebase", "pull request", "gh pr". Check uncommitted changes, run the commit skill when needed, perform PR-wide security checks, follow branch and PR flow rules, and write Korean PR title/body.
---

# PR Procedure

Confirm the PR target branch first. If uncommitted changes exist, use the commit skill only after the target branch is confirmed. Never merge PRs into `main`.

## 1. Confirm PR Target Branch

Confirm the PR target (base) branch before doing any PR preparation or execution.

- Treat the current checkout branch as the source branch and the user-confirmed branch as the target branch.
- If the user explicitly provides a target branch, use that exact branch for the rest of the PR workflow.
- If the user does not provide a target branch, inspect the current branch and repository PR flow, state the exact source and target branches to the user, and wait for explicit confirmation.
- Until the target branch is confirmed, do not run the `commit` skill, security checks, target-specific verification, `push`, or PR creation/update.
- After confirmation, keep the target branch fixed. If another target such as `main` later seems appropriate, stop and obtain confirmation for the new target before continuing.

## 2. Check Commit State

- Run `git status --short` to check for uncommitted changes.
- If uncommitted changes exist, use the `commit` skill first and complete the commit before continuing PR work.
- If all changes are already committed, continue to PR preparation.

## 3. Pre-PR Security Check

Stop before PR if real secrets are found.

- Before creating a PR, inspect the full PR diff with `git diff <confirmed-target-branch>...HEAD` for real secrets.
- Check for API keys, access keys, secret keys, tokens, passwords, private keys, credentials, certificates/keystores, real `.env` files, production endpoints, and account information.
- Distinguish placeholders in `.env.example`, docs, and test fixtures from real secrets. If a value looks real or ambiguous, ask the user before proceeding.
- If a secret or non-public value is found, stop PR creation, report the file and item, and continue only after it is removed or replaced.
- Before opening a PR in an external review tool or public repo, confirm the repository can be exposed to that external system.

## 4. Branch and PR Flow

Do not work directly on `dev` for feature or fix changes.

- Do feature or fix work on a separate branch whose name reflects the task.
- When the feature branch is ready, create a PR targeting the confirmed target branch. The repository default is `dev` when no other target is explicitly confirmed.
- Before creating the PR, check that each commit is separated by functional unit.
- Split unrelated features into separate PRs.
- Recheck the full diff before writing the PR to ensure it contains one coherent feature scope.
- Run full checks and tests against the confirmed target branch to verify the change is appropriate.
- If `main` later becomes appropriate, obtain new explicit confirmation before changing the target branch.

## 5. Write PR

- Write the PR title and body in Korean.
- Include change summary, test results, and reviewer notes in Korean.

## 6. Restrict Main PRs

Never merge PRs into `main`, even if asked.

- For `main` target PRs, Codex may only create and inspect PR status.
- The user must directly perform approval, merge, merge queue execution, and deployment-related final actions for `main`.
- Do not merge a `main` target PR even if the user asks.
