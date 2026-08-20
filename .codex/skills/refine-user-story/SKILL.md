---
name: refine-user-story
description: Refine one user-story section at a time by reconciling the document with implemented backend, frontend, tests, configuration, and error handling. Use when the user asks to audit, rewrite, organize, or continue Fruition user stories, requirements, constraints, success scenarios, or failure scenarios against the current codebase, including domain-by-domain work such as signup, login, workspace, document, wiki, or chat.
---

# Refine User Story

Reconcile one domain section of `docs/Fruition_User_Story.md` with the current implementation. Produce compact Korean user stories with verified constraints and observable success and failure scenarios.

## Ground Rules

- Write every user-facing update, draft, and explanation in Korean.
- Read the repository `AGENTS.md` before starting and follow its approval rules.
- Handle only one user-selected domain or subsection per cycle.
- Treat executable code and active configuration as the current-behavior source of truth unless the user explicitly requests a target-state specification.
- Distinguish backend enforcement, frontend-only enforcement, database constraints, configurable defaults, and unimplemented intent.
- Treat a capability as implemented when either the backend or frontend provides it.
- When only one side implements a capability, document its implemented scope and the missing counterpart.
- Use a separate `#### 구현 범위` section whenever only one side is implemented, implementations are disconnected, or an end-to-end path is incomplete.
- Do not place implementation-scope information under `제약조건`.
- Leave constraints and scenarios empty only when neither backend nor frontend implements the planned capability.
- Surface contradictions instead of silently choosing one side.
- Do not create a separate user story for an automatic technical mechanism that requires no user action. Put it under the related constraints or system conditions.
- Keep a planned but unimplemented capability as a user story only, with empty constraint, success-scenario, and failure-scenario sections.
- Omit an intentionally removed capability from the user-story document.
- Do not modify code, tests, API contracts, or adjacent user-story sections unless the user separately requests and approves them.
- Do not create or modify files before presenting the exact scope, reason, impact, and draft and receiving explicit approval.

## Workflow

### 1. Fix the Scope

Identify the single section to review. Default to `docs/Fruition_User_Story.md` when the user refers to the Fruition user-story document without naming a path.

State a short plan with verifiable outcomes:

1. Read the current section.
2. Trace relevant implementation and tests.
3. Draft grouped constraints and scenarios.
4. Obtain approval.
5. Edit only the approved section and verify the diff.

Do not begin another domain in the same cycle.

### 2. Inspect the Current Story

Read the full target section and enough neighboring headings to preserve document structure. Identify:

- distinct user goals incorrectly combined into one user story;
- implementation details incorrectly presented as user actions;
- missing constraints;
- missing normal, boundary, duplicate, unauthorized, timeout, and partial-failure scenarios;
- claims that may describe a planned state instead of current behavior.

Create one subsection for each independently testable user goal. Do not combine actions with different outcomes, such as signup, login, logout, account deletion, workspace creation, workspace listing, or workspace switching.

Group only interaction variants that produce the same outcome. For example, express file selection and drag-and-drop as one upload story, then place allowed extensions and multiple-file support under constraints.

### 3. Trace Code Evidence

Use `rg` and targeted file reads. Follow the behavior end to end instead of relying on filenames alone.

Check relevant evidence in this order:

1. Runtime service and domain logic.
2. Request DTO validation and database constraints.
3. Controllers, security rules, and exception-to-HTTP mappings.
4. Active configuration and migrations.
5. Frontend input validation, request construction, redirects, and visible errors.
6. Unit and integration tests.
7. API specifications, changelogs, issue documents, and backlog records as secondary context.

Verify exact limits, normalization, fallback behavior, uniqueness scope, transaction boundaries, side effects, token or lock lifetime, status codes, and error redirects. Label configurable values as defaults.

Do not treat stale documentation or test names as stronger evidence than current runtime code. If tests and implementation disagree, report the disagreement.

### 4. Reconcile the Findings

Separate facts into:

- behavior enforced consistently;
- frontend-only or backend-only behavior;
- desired behavior not implemented;
- planned but unimplemented behavior to retain as a story without constraints or scenarios;
- intentionally removed behavior to omit from the document;
- automatic technical behavior to place under related system conditions;
- ambiguous behavior requiring a user decision.

If the user's proposed policy differs from the codebase, show the difference before drafting. Ask whether to document current behavior or target behavior only when the user has not already established that choice.

Do not invent constraints or scenarios for an implementation gap.

### 5. Draft the Section

Use this structure unless the existing document requires an equivalent heading level:

```md
### [번호] [기능명]

#### 사용자 스토리

- 사용자는 [하나로 묶인 사용자 기능]을 할 수 있다.

#### 구현 범위

- 백엔드: [구현 여부와 범위]
- 프론트엔드: [구현 여부와 범위]
- 연동 상태: [연결 여부 또는 end-to-end 동작 여부, 필요한 경우]

#### 제약조건

- [코드로 확인한 입력, 권한, 상태, 수명, 범위 또는 부수효과]

#### 성공 시나리오

- [유효한 조건]인 경우 [관찰 가능한 성공 결과]가 발생한다. (`HTTP status`, 확인된 경우)

#### 실패 시나리오

- [실패 조건]인 경우 [관찰 가능한 실패 결과]가 발생한다. (`HTTP status`, 확인된 경우)
```

Keep each user story focused on one independently testable goal. Move file types, counts, lengths, uniqueness, normalization, permissions, lifetimes, ordering, and transaction behavior into constraints.

Omit `구현 범위` when backend and frontend provide the same connected behavior without a meaningful scope difference. Include it when only one side exists, both sides implement disconnected behavior, or a downstream dependency prevents end-to-end operation.

Include success scenarios for the main path, meaningful fallback paths, creation or state-change side effects, and frontend navigation when verified. Include failure scenarios only when code establishes the outcome.

For a planned but unimplemented capability, write only its user story and leave the other three headings empty. For an automatic mechanism such as token rotation, use a related `시스템 조건` section instead of inventing a user action.

Use HTTP statuses only after confirming controller validation, security behavior, exception handling, or redirect flow. Do not assign an API status to a browser redirect.

### 6. Request Approval

Before writing:

1. Summarize discrepancies found in the codebase.
2. Show the exact proposed section text.
3. List every file to modify, the reason, and the impact scope.
4. Ask for explicit approval.

Treat short approvals such as `ㄱㄱ`, `진행`, or `그대로` as approval for the presented draft and file scope only.

### 7. Edit Surgically

After approval, use `apply_patch`. Change only the approved section.

Preserve unrelated content and heading order. When the old section mixes the target behavior with other capabilities, move the untouched capabilities under the smallest necessary neighboring heading instead of rewriting them.

Do not clean up unrelated wording or formatting.

### 8. Verify and Report

After editing:

1. Re-read the changed section.
2. Run `git diff --check`.
3. Inspect `git diff -- <document-path>`.
4. Confirm `git status --short` contains only expected files or clearly identify pre-existing changes.

For a documentation-only change, do not run product tests unless the user asks or repository rules require them. Report that tests were not run.

Return the changed file as a clickable link, summarize the verified scope, and mention any remaining code-versus-policy gap. Do not start the next section until the user requests it.
