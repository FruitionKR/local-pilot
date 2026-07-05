---
name: commit
description: Use when Codex is asked to create, prepare, amend, or help with a git commit in this repository. Follow security checks, changelog updates when applicable, Korean Conventional Commits message writing, and commit creation in order.
---

# Commit Procedure

Run in order: security check -> changelog -> commit message -> commit. Do not skip steps.

## 1. Pre-Commit Security Check

Stop before commit if real secrets are found.

- Before creating a commit, always inspect changed files and tracked files for real secrets.
- Check for API keys, access keys, secret keys, tokens, passwords, private keys, credentials, certificates/keystores, real `.env` files, production endpoints, and account information.
- Use `git status --short`, `git diff --cached`, `git diff`, `git ls-files`, and search tools to identify files that will be committed.
- Distinguish placeholders in `.env.example`, docs, and test fixtures from real secrets. If a value looks real or ambiguous, ask the user before proceeding.
- If a secret or non-public value is found, stop the commit, report the file and item, and continue only after it is removed or replaced.

## 2. Update Changelog

- Update changelog when preparing or creating the commit, not immediately after editing files.
- Update files under `docs/changelog/` only for frontend, backend, AI/pipeline, infrastructure, DevOps, Docker, or deployment behavior changes.
- For Java/Spring backend feature changes, update `docs/changelog/backend.md`.
- For frontend feature changes, update `docs/changelog/frontend.md`.
- For AI/pipeline (`llmPipeline`) feature changes, update `docs/changelog/ai.md`.
- For infrastructure, DevOps, Docker, deployment environment code, or configuration changes, update `docs/changelog/infra.md`.
- If a commit spans multiple functional areas, update every relevant changelog.
- Do not update changelog for issue-document cleanup, agent instruction changes, simple document moves, or other changes without functional code impact.
- Write changelog entries in Korean. Keep them concise and include change background, changed behavior, verification result, or remaining cautions.

## 3. Write Commit Message

- Use Conventional Commits prefixes such as `fix:`, `feat:`, `docs:`, `chore:`, `refactor:`, and `test:`.
- Write the commit title and body explanation in Korean.
- Preserve English for Conventional Commits prefixes, branch names, commands, file names, API names, and other original identifiers.
- Keep the title concise and clearly describe the commit scope in Korean.
- Example: `feat: 로컬 Docker 개발 환경 추가`

## 4. Create Commit

- Create the commit only after the security check passes and applicable changelog updates are included.
- If changelog updates are required, include the changelog file in the same commit.
