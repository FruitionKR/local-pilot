# Agent Markdown 계약

이 문서는 프론트엔드가 `/agent/turn` 응답을 처리할 때 필요한 Markdown 편집/생성 계약을 정리한다.

## 1. 공통 응답

`action`은 프론트 처리 방식을 결정한다.

```json
{
  "action": "chat_answer | markdown_edit | markdown_create | clarify | reject",
  "route": {
    "action": "chat_answer | markdown_edit | markdown_create | clarify | reject",
    "confidence": 0.92,
    "reason": "brief reason",
    "edit_goal": "shorten | style_change | convert_format | checklist | translate | cleanup | template_transform | create_from_chat | other"
  },
  "message": null,
  "chat": null,
  "edit": null,
  "generated_markdown": null
}
```

## 2. 기존 문서 편집

`action == "markdown_edit"`이면 프론트는 기존 Markdown 문서의 선택 영역이나 현재 섹션을 교체할 수 있는 preview/diff를 보여준다.

```json
{
  "action": "markdown_edit",
  "edit": {
    "operation": "replace",
    "target": {
      "type": "selection",
      "start_line": 3,
      "end_line": 5
    },
    "summary": "선택 영역을 짧게 줄였습니다.",
    "replacement_markdown": "교체할 Markdown"
  }
}
```

프론트 처리:

- `operation == "replace"`만 지원한다.
- `target.start_line`부터 `target.end_line`까지 `replacement_markdown`으로 교체한다.
- 사용자가 Apply를 누르기 전까지 원본 Markdown은 변경하지 않는다.
- `edit_goal`은 preview label이나 처리 힌트로 사용할 수 있지만, 실제 적용 기준은 `edit.operation`과 `edit.target`이다.

## 3. 새 Markdown 문서 생성

`action == "markdown_create"`이면 프론트는 기존 문서를 수정하지 않고 `generated_markdown`을 새 문서 탭이나 새 editor buffer로 연다.

```json
{
  "action": "markdown_create",
  "route": {
    "action": "markdown_create",
    "edit_goal": "create_from_chat"
  },
  "generated_markdown": {
    "title": "Agent 설계 메모",
    "summary": "대화 내용을 Markdown 문서로 정리했습니다.",
    "markdown": "# Agent 설계 메모\n\n- 편집과 생성을 분리한다."
  }
}
```

프론트 처리:

- 기존 문서의 selection/current section을 교체하지 않는다.
- `generated_markdown.title`을 새 문서 제목 후보로 사용한다.
- `generated_markdown.markdown`을 새 editor buffer의 본문으로 사용한다.
- 저장 API가 연결되기 전까지는 draft 상태로 유지한다.

## 4. Clarify

`action == "clarify"`이면 `message`를 사용자에게 보여주고 추가 입력이나 target 선택을 유도한다.

대표 케이스:

- 편집 요청인데 active Markdown target이 없음
- template 기반 전체 문서 재구성처럼 현재 보류한 범위

## 5. Conversation Context

`markdown_create` 품질은 `conversation_context.recent_conversation_summary`에 크게 의존한다. 프론트 또는 conversation memory는 사용자가 “지금까지 이야기한 내용”을 문서로 만들라고 요청할 때 최근 합의 내용, 결정 사항, 주요 제약을 요약해서 넘겨야 한다.
