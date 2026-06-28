# Agent Markdown 계약

이 문서는 프론트엔드가 `/agent/turn` 응답을 처리할 때 필요한 Markdown 편집/생성 계약을 정리한다.

## 1. 처리 플로우

```text
사용자 입력
  -> POST /agent/turn
  -> AgentTurnRouter
  -> action 판정
      chat_answer      -> 기존 query pipeline으로 답변 생성
      markdown_edit    -> active Markdown target을 replace할 edit 생성
      markdown_create  -> 대화/context 기반 새 Markdown 문서 생성
      clarify          -> target 선택 또는 추가 입력 요청
      reject           -> 지원하지 않는 요청 거절
  -> AgentTurnResponse 반환
  -> 프론트가 action별 UI 처리
```

프론트 분기 기준:

| action | 결과 필드 | 프론트 처리 |
| --- | --- | --- |
| `chat_answer` | `chat` | 기존 채팅 답변으로 렌더링 |
| `markdown_edit` | `edit` | 기존 문서의 target range에 대한 preview/diff 표시 |
| `markdown_create` | `generated_markdown` | 새 문서 탭 또는 새 editor buffer 생성 |
| `clarify` | `message` | target 선택이나 추가 입력 유도 |
| `reject` | `message` | 지원하지 않는 요청 안내 |

## 2. 요청 입력

```json
{
  "message": "지금까지 이야기한 내용 md로 만들어줘",
  "conversation_context": {
    "recent_conversation_summary": "최근 대화 요약",
    "reference_context": {}
  },
  "active_markdown_context": {
    "markdown": "# 현재 문서",
    "target": {
      "type": "selection",
      "start_line": 3,
      "end_line": 5
    },
    "document_kind": "wiki_page"
  }
}
```

입력 필드:

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `message` | 예 | 사용자의 현재 발화 |
| `conversation_context.recent_conversation_summary` | 아니오 | 멀티턴 후속 요청과 새 문서 생성에 쓰는 최근 대화 요약 |
| `conversation_context.reference_context` | 아니오 | LLM 판단에 필요한 추가 참조 context |
| `active_markdown_context.markdown` | 편집 시 필요 | 현재 편집 중인 Markdown 본문 |
| `active_markdown_context.target` | 편집 시 필요 | 교체 대상 selection/current_section 범위 |
| `active_markdown_context.document_kind` | 아니오 | 문서 종류 힌트 |

`markdown_create`는 active Markdown target 없이도 실행될 수 있다. `markdown_edit`는 target이 없으면 `clarify`로 응답한다.

## 3. 공통 응답

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

응답 필드:

| 필드 | 설명 |
| --- | --- |
| `action` | 실제 프론트 분기 기준 |
| `route.action` | router가 판정한 action |
| `route.confidence` | router 판정 confidence |
| `route.reason` | router 판정 이유 |
| `route.edit_goal` | 편집/생성 목표 힌트. 예: `shorten`, `checklist`, `convert_format`, `create_from_chat` |
| `message` | clarify/reject 시 사용자에게 보여줄 안내 |
| `chat` | `chat_answer` 결과 |
| `edit` | `markdown_edit` 결과 |
| `generated_markdown` | `markdown_create` 결과 |

## 4. 기존 문서 편집

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

`edit` 필드:

| 필드 | 설명 |
| --- | --- |
| `operation` | 현재는 `replace`만 지원 |
| `target.type` | `selection` 또는 `current_section` |
| `target.start_line` | 교체 시작 line. 1-base |
| `target.end_line` | 교체 종료 line. 1-base, inclusive |
| `summary` | 편집 요약 |
| `replacement_markdown` | target range를 대체할 Markdown |

프론트 처리:

- `operation == "replace"`만 지원한다.
- `target.start_line`부터 `target.end_line`까지 `replacement_markdown`으로 교체한다.
- 사용자가 Apply를 누르기 전까지 원본 Markdown은 변경하지 않는다.
- `edit_goal`은 preview label이나 처리 힌트로 사용할 수 있지만, 실제 적용 기준은 `edit.operation`과 `edit.target`이다.

## 5. 새 Markdown 문서 생성

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

`generated_markdown` 필드:

| 필드 | 설명 |
| --- | --- |
| `title` | 새 문서 제목 후보 |
| `summary` | 생성 결과 요약 |
| `markdown` | 새 editor buffer에 넣을 Markdown 본문 |

프론트 처리:

- 기존 문서의 selection/current section을 교체하지 않는다.
- `generated_markdown.title`을 새 문서 제목 후보로 사용한다.
- `generated_markdown.markdown`을 새 editor buffer의 본문으로 사용한다.
- 저장 API가 연결되기 전까지는 draft 상태로 유지한다.

## 6. Clarify

`action == "clarify"`이면 `message`를 사용자에게 보여주고 추가 입력이나 target 선택을 유도한다.

대표 케이스:

- 편집 요청인데 active Markdown target이 없음
- template 기반 전체 문서 재구성처럼 현재 보류한 범위

## 7. Conversation Context

`markdown_create` 품질은 `conversation_context.recent_conversation_summary`에 크게 의존한다. 프론트 또는 conversation memory는 사용자가 “지금까지 이야기한 내용”을 문서로 만들라고 요청할 때 최근 합의 내용, 결정 사항, 주요 제약을 요약해서 넘겨야 한다.
