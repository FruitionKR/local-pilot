---
name: review
description: PR 생성 전/후 코드 리뷰. 인자가 없으면 로컬 변경분(base..HEAD)을 리뷰해 터미널에 출력하고, PR 번호를 주면 해당 PR을 리뷰해 GitHub 인라인 코멘트로 게시한다. 버그·정합성·보안 위주로 고신뢰 지적만 낸다.
argument-hint: "[PR 번호] [포커스: 더 깊게 볼 영역·주제]"
allowed-tools: Bash(git *) Bash(gh *) Read Grep Glob
---

# Review Procedure

`$ARGUMENTS`를 다음과 같이 해석한다.

- 맨 앞에 숫자(PR 번호)가 있으면 PR 모드, 없으면 로컬 모드.
- 숫자를 뺀 나머지 텍스트가 있으면 포커스 지시로 본다.
- 출력과 코멘트는 모두 한글로 작성한다.

## Tone And Format

- PR 모드: 헤더 `🧶 결실봇 코드리뷰`, `~해요`체로 담백하게 쓴다. 여는 요약 한두 줄은 무엇을 바꿨고 무엇을 확인했는지만 담는다.
- 로컬 모드: 결실봇 문체·인사·여는 요약 없이 문제와 수정 방향 중심으로만 쓴다. 헤더는 `코드리뷰`.
- 공통: 인사말·칭찬 없이 내용만 쓴다. 지적 내용, 심각도, line 번호는 정확히 유지한다.

## Step 1 - Get Diff

- 로컬 모드: 아직 커밋하지 않은 작업분을 대상으로 한다. `git diff`, `git diff --cached`, `git status --short`를 확인한다. 두 diff가 모두 비어 있으면 `커밋하지 않은 변경이 없습니다.`를 출력하고 종료한다.
- PR 모드: `gh pr diff <PR 번호>`와 `gh pr view <PR 번호> --json title,body`를 확인한다.

## Step 2 - Review

변경된 모든 파일을 빠짐없이 검토한다. diff가 크다고 일부 파일만 보고 넘기지 않는다. 사용자가 명시적으로 범위를 좁힌 경우에만 그 범위를 밝히고 좁힌다.

각 파일에서 변경된 라인만 검토한다. 주변 코드는 맥락 파악용으로만 읽는다.

포커스 지시가 있으면 전체는 그대로 빠짐없이 검토하되, 지정된 영역·파일·주제를 추가로 더 깊게 분석한다. 단, `only`나 `만`처럼 범위를 좁히라는 지시가 있으면 그 영역만 검토하고 나머지는 범위 밖임을 밝힌다.

PR 모드의 요구사항 기준은 PR 설명이다. 로컬 모드는 변경 코드와 주석에서 의도를 파악하고, 의도가 불명확하면 사용자에게 한 번 확인한다.

확신이 서는 것만 지적한다. 애매하거나 취향 수준은 넣지 않는다. 우선순위는 다음과 같다.

1. 버그/로직: null 참조, 반전된 조건, 예외 삼킴, 트랜잭션 범위, 반환값 미처리
2. 요구사항: 커밋/PR 설명 대비 누락·오구현
3. 정합성: 스키마↔엔티티↔DTO↔API 필드/타입, 네이밍, 계약 일치
4. 보안: injection, 인증/인가 누락, 시크릿 하드코딩·로그 노출
5. 엣지 케이스: 빈값/null/경계값, 동시성, 외부 호출 실패
6. 성능: N+1, 루프 내 DB 호출, 대용량 전체 로드
7. 테스트: 변경 로직에 대응하는 테스트 존재·실효성
8. CLAUDE.md/AGENTS.md: 요청 범위 초과 변경, 추측성 코드, 불필요한 방어 코드
9. 리팩터링: 동작을 바꾸지 않는 구조 개선. 중복 제거, 기존 코드/유틸 재사용, 죽은 코드 제거, 과도한 추상화 제거처럼 구체적인 제안만 `♻️`로 표시한다.

## Step 3 - Output

심각도는 `🚨 Critical`, `⚠️ Warning`, `💡 Info`, `♻️ Refactoring`으로 구분한다. `🚨⚠️💡`는 정확성/품질 문제, `♻️`는 동작을 바꾸지 않는 리팩터링 제안이다.

검증한 내용은 나열하지 말고 한 줄로 간결하게 요약한다. 지적에 집중한다.

### Local Mode

파일별이 아니라 종류별로 문단을 나눈다. 발견 사항이 있는 종류만 출력한다.

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

발견 사항이 없으면 `변경분에서 문제를 발견하지 못했습니다.`를 출력한다.

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

발견 사항이 없으면 `"event": "APPROVE"`로 게시한다. 현재 GitHub 계정이 본인 PR 승인을 금지당하면 `"event": "COMMENT"`로 동일한 요약을 남기고, self-approval 제한 때문에 승인 이벤트가 막혔다고 사용자에게 보고한다.
