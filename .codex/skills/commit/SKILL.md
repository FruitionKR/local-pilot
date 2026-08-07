---
name: commit
description: Use when Codex is asked to create, prepare, amend, or help with a git commit in this repository. Triggers include "커밋해줘", "커밋하자", "커밋 준비", "저장해둬", "commit", "git commit". Follow security checks, current-docs updates when applicable, Korean Conventional Commits message writing, and commit creation in order.
---

# Commit Procedure

Run in order: security check -> current docs update -> commit message -> commit. Do not skip steps.

## 1. Pre-Commit Security Check

Stop before commit if real secrets are found.

- Before creating a commit, always inspect changed files and tracked files for real secrets.
- Check for API keys, access keys, secret keys, tokens, passwords, private keys, credentials, certificates/keystores, real `.env` files, production endpoints, and account information.
- Use `git status --short`, `git diff --cached`, `git diff`, `git ls-files`, and search tools to identify files that will be committed.
- Distinguish placeholders in `.env.example`, docs, and test fixtures from real secrets. If a value looks real or ambiguous, ask the user before proceeding.
- If a secret or non-public value is found, stop the commit, report the file and item, and continue only after it is removed or replaced.

## 2. Update Current Docs

- Dated issue docs (`docs/issue/`) and role changelogs (`docs/changelog/`) were retired on 2026-08-07 and archived under `docs/backlog/`. Never create new ones (root `CLAUDE.md` §8).
- Instead, when the commit changes behavior that current docs describe, update the matching doc in the same commit:
  - Service boundary, communication, authorization, or deployment changes -> `docs/architecture.md`
  - API contract changes -> `docs/api.md`
  - Storage, table, or ownership changes -> `docs/data-model.md`
  - Run or demo procedure changes -> `docs/demo-script.md`
  - Significant architecture decisions -> `docs/adr/NNNN-<kebab-title>.md`
- Do not touch docs for commits that leave documented behavior unchanged.
- When feature work reveals a related unresolved requirement, tell the user before committing what remains and why the current changes do not resolve it. Do not silently ignore it or expand implementation scope without approval.

## 3. Write Commit Message

- Use Conventional Commits prefixes such as `fix:`, `feat:`, `docs:`, `chore:`, `refactor:`, and `test:`.
- Write the commit title and body explanation in Korean.
- Preserve English for Conventional Commits prefixes, branch names, commands, file names, API names, and other original identifiers.
- Keep the title concise and clearly describe the commit scope in Korean.
- Example: `feat: 로컬 Docker 개발 환경 추가`

## 4. Create Commit

- Create the commit only after the security check passes and applicable current-doc updates are included.
- If doc updates are required, include those files in the same commit.
