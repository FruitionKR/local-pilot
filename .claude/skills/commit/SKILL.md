---
name: commit
description: 커밋을 생성하거나 준비할 때 반드시 사용. 커밋 전 보안 점검 → Conventional Commits 형식의 한글 커밋 메시지 작성 순서로 진행하는 절차.
---

# 커밋 절차

**Run in order: security check → commit message → commit. Do not skip steps.**

## 1. 커밋 전 보안 점검

**Stop before commit if real secrets are found.**

- 커밋 생성 전에는 항상 변경분과 추적 대상 파일에 실제 비밀값이 포함되어 있지 않은지 점검한다.
- 점검 대상에는 API key, access key, secret key, token, password, private key, credential, 인증서/keystore, 실제 `.env` 파일, 운영 endpoint나 계정 정보가 포함된다.
- `git status --short`, `git diff --cached`, `git diff`, `git ls-files`와 검색 도구를 활용해 커밋에 포함될 파일을 확인한다.
- `.env.example`, 문서, 테스트 fixture에 있는 예시값과 placeholder는 실제 비밀값과 구분하되, 실제 값처럼 보이거나 혼동 가능성이 있으면 사용자에게 확인한다.
- 비밀값 또는 공개하면 안 되는 정보가 발견되면 커밋 생성을 중단하고, 어떤 파일과 항목이 문제인지 사용자에게 보고한 뒤 제거 또는 교체가 완료된 경우에만 진행한다.

## 2. 커밋 메시지

- 커밋 메시지는 `fix:`, `feat:`, `docs:`, `chore:`, `refactor:`, `test:` 같은 Conventional Commits 형식을 사용한다.
- 커밋 메시지 제목과 본문 설명은 한글로 작성한다.
- Conventional Commits 접두사, 브랜치명, 명령어, 파일명, API 이름처럼 원문 유지가 필요한 값은 영어를 그대로 사용한다.
- 제목은 간결하게 작성하고, 한 커밋이 담는 변경 범위를 한글로 명확히 표현한다.
- 예: `feat: 로컬 Docker 개발 환경 추가`

## 3. 커밋 생성

- 보안 점검 통과와 커밋 메시지 검토를 확인한 뒤 커밋을 생성한다.
