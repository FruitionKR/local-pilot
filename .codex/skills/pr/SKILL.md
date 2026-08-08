---
name: pr
description: PR을 생성하거나 준비할 때 반드시 사용. 미커밋 변경이 있으면 commit 스킬을 먼저 수행하고, 브랜치/PR 흐름과 PR 전 보안 점검을 거쳐 PR을 작성하는 절차.
---

# PR 절차

**Confirm the PR target branch first. If uncommitted changes exist, run the commit skill only after the target branch is confirmed. Never merge PRs into `main`.**

## 1. PR 대상 브랜치 확정

PR 준비나 실행을 시작하기 전에 PR 대상(base) 브랜치를 확정한다.

- 현재 checkout 브랜치는 source 브랜치로, 사용자가 확정한 브랜치는 target 브랜치로 취급한다.
- 사용자가 요청에 target 브랜치를 직접 입력했으면 해당 브랜치를 PR 절차 전체에서 그대로 사용한다.
- target 브랜치를 입력하지 않았으면 현재 브랜치와 저장소의 PR 흐름을 확인한 뒤, 정확한 source와 target 브랜치를 사용자에게 알리고 명시적인 확인을 기다린다.
- target 브랜치가 확인되기 전에는 `commit` 스킬, 보안 점검, target 기준 검증, `push`, PR 생성/갱신을 진행하지 않는다.
- 한 번 확정한 target 브랜치는 이후 고정한다. 이후 `main` 등 다른 대상이 적절해 보여도 자동으로 바꾸지 말고 새 target에 대한 확인을 다시 받는다.

## 2. 커밋 상태 확인

- `git status --short`로 미커밋 변경이 있는지 확인한다.
- 미커밋 변경이 있으면 commit 스킬(`.codex/skills/commit/SKILL.md`)의 절차를 먼저 수행해 커밋을 완료한 뒤 PR 작업을 진행한다.
- 모든 변경이 이미 커밋되어 있으면 바로 PR 작성으로 진행한다.

## 3. PR 전 보안 점검

**Stop before PR if real secrets are found.**

- PR 생성 전에는 항상 PR 변경분 전체(`git diff <confirmed-target-branch>...HEAD`)에 실제 비밀값이 포함되어 있지 않은지 점검한다.
- 점검 대상에는 API key, access key, secret key, token, password, private key, credential, 인증서/keystore, 실제 `.env` 파일, 운영 endpoint나 계정 정보가 포함된다.
- `.env.example`, 문서, 테스트 fixture에 있는 예시값과 placeholder는 실제 비밀값과 구분하되, 실제 값처럼 보이거나 혼동 가능성이 있으면 사용자에게 확인한다.
- 비밀값 또는 공개하면 안 되는 정보가 발견되면 PR 생성을 중단하고, 어떤 파일과 항목이 문제인지 사용자에게 보고한 뒤 제거 또는 교체가 완료된 경우에만 진행한다.
- 외부 코드 리뷰 도구나 public repo에 PR을 올리기 전에는 저장소 전체가 외부 시스템에 노출되어도 되는지 확인하고, 문제가 없을 때만 진행한다.

## 4. 브랜치와 PR 흐름

**Do not work directly on `dev` for feature or fix changes.**

- 기능 개발이나 수정 작업은 `dev`에서 직접 진행하지 않고, 작업 목적이 드러나는 별도 브랜치를 생성해 진행한다.
- 기능 브랜치 작업이 완료되면 확정된 target 브랜치를 대상으로 PR을 생성한다. 다른 target을 명시적으로 확정하지 않은 경우 저장소 기본 대상은 `dev`로 안내한다.
- PR 생성 전, 각 커밋의 변경 내용이 기능 단위로 분리되어 있는지 확인한다.
- 서로 다른 기능이 한 PR에 섞이지 않도록, 같은 기능에 속하는 변경만 묶어 PR을 나눠서 작성한다.
- PR을 작성하기 전에는 항상 전체 변경분이 기능별로 제대로 분할되어 있는지 다시 확인한 뒤 진행한다.
- 확정된 target 브랜치를 기준으로 전체 검사와 테스트를 실행해 변경사항이 합당한지 확인한다.
- 이후 `main` 반영이 적절하다고 판단되더라도 target을 바꾸기 전에 새 명시적 확인을 받는다.

## 5. PR 작성

- PR 제목과 본문은 한글로 작성한다.
- PR 본문에는 변경 요약, 테스트 결과, 검토자가 알아야 할 주의사항을 한글로 정리한다.

## 6. main 대상 PR 제한

**Never merge PRs into `main`, even if asked.**

- `main` 대상 PR은 agent가 생성과 상태 확인까지만 수행한다.
- `main` 대상 PR의 승인, 병합, merge queue 실행, 배포 반영 등 최종 실행은 반드시 사용자가 직접 수행한다.
- 사용자가 별도로 요청하더라도 agent는 `main` 대상 PR을 병합하지 않는다.
