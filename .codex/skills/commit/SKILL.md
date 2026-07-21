---
name: commit
description: Use when Codex is asked to create, prepare, amend, or help with a git commit in this repository. Triggers include "커밋해줘", "커밋하자", "커밋 준비", "저장해둬", "commit", "git commit". Follow security checks, related issue review and completion archival, changelog updates when applicable, Korean Conventional Commits message writing, and commit creation in order.
---

# Commit Procedure

Run in order: security check -> issue review -> changelog -> commit message -> commit. Do not skip steps.

## 1. Pre-Commit Security Check

Stop before commit if real secrets are found.

- Before creating a commit, always inspect changed files and tracked files for real secrets.
- Check for API keys, access keys, secret keys, tokens, passwords, private keys, credentials, certificates/keystores, real `.env` files, production endpoints, and account information.
- Use `git status --short`, `git diff --cached`, `git diff`, `git ls-files`, and search tools to identify files that will be committed.
- Distinguish placeholders in `.env.example`, docs, and test fixtures from real secrets. If a value looks real or ambiguous, ask the user before proceeding.
- If a secret or non-public value is found, stop the commit, report the file and item, and continue only after it is removed or replaced.

## 2. Review Related Issues

- Before updating the changelog, compare staged and unstaged changes with unresolved documents under `docs/issue/frontend/`, `docs/issue/backend/`, `docs/issue/ai/`, and `docs/issue/infra/`.
- Never create a date file directly under `docs/issue/`. If a changed or directly related legacy root issue file is found, move unresolved sections to the current day's role-folder issue document and archive resolved sections as described below.
- Use changed paths, implemented behavior, API names, and verification evidence to find related issues. Do not mark an issue complete only because its title or topic resembles the change.
- Treat an issue as resolved only when the current changes satisfy its completion conditions and the relevant tests or verification pass.
- If every unresolved item in an issue document is resolved, record the completion evidence, move its content to `docs/backlog/issue-YYYY-MM-DD.md`, remove the role-folder issue document, and update `docs/backlog/README.md` in the same commit.
- If the destination backlog file already exists, merge the completed content under a role-labeled section. Never overwrite or discard existing backlog content.
- If only part of an issue document is resolved, move only the completed sections to the matching backlog document. Move the remaining sections to the current day's `docs/issue/<role>/YYYY-MM-DD.md` and leave a move notice with links in the older issue document.
- Keep completed implementation history in the applicable `docs/changelog/` file; keep `docs/issue/` limited to work another team member still needs to perform.
- If issue maintenance would modify files outside the user's approved scope, explain the files, reason, and impact, then obtain explicit approval before writing.
- When feature work reveals a related unresolved requirement, tell the user before committing what remains and why the current changes do not resolve it. Do not silently ignore it, mark it complete, or expand implementation scope without approval.

## 3. Update Changelog

- Update changelog when preparing or creating the commit, not immediately after editing files.
- Update files under `docs/changelog/` only for frontend, backend, AI/pipeline, infrastructure, DevOps, Docker, or deployment behavior changes.
- For Java/Spring backend feature changes, update `docs/changelog/backend.md`.
- For frontend feature changes, update `docs/changelog/frontend.md`.
- For AI/pipeline (`llmPipeline`) feature changes, update `docs/changelog/ai.md`.
- For infrastructure, DevOps, Docker, deployment environment code, or configuration changes, update `docs/changelog/infra.md`.
- If a commit spans multiple functional areas, update every relevant changelog.
- Do not update changelog for issue-document cleanup, agent instruction changes, simple document moves, or other changes without functional code impact.
- Write changelog entries in Korean. Keep them concise and include change background, changed behavior, verification result, or remaining cautions.

## 4. Write Commit Message

- Use Conventional Commits prefixes such as `fix:`, `feat:`, `docs:`, `chore:`, `refactor:`, and `test:`.
- Write the commit title and body explanation in Korean.
- Preserve English for Conventional Commits prefixes, branch names, commands, file names, API names, and other original identifiers.
- Keep the title concise and clearly describe the commit scope in Korean.
- Example: `feat: 로컬 Docker 개발 환경 추가`

## 5. Create Commit

- Create the commit only after the security check passes and applicable issue and changelog updates are included.
- If issue or changelog updates are required, include those files in the same commit.
