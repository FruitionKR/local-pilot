# Agent Markdown 계약

이 문서는 프론트엔드와 Spring backend가 `/agent/turn` 응답을 처리할 때 필요한 Markdown 편집/생성 계약을 정리한다.

`/agent/turn`은 editor가 있는 채팅 화면을 위한 endpoint다. 사용자의 한 문장을 먼저 분류한 뒤, 일반 채팅 답변, 기존 Markdown 편집, 새 Markdown 생성 중 하나로 실행한다. 단순 질문만 처리하는 화면은 `/query`를 직접 호출해도 된다.

## 1. 처리 플로우

```text
사용자 입력
  -> POST /agent/turn
  -> AgentTurnRouter
  -> action 판정
      chat_answer      -> 기존 query pipeline으로 답변 생성
      markdown_edit    -> active Markdown target 또는 문서 전체를 replace할 edit 생성
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
| `markdown_edit` | `edit` | 선택 영역 또는 문서 전체에 대한 preview/diff 표시 |
| `markdown_create` | `generated_markdown` | 새 문서 탭 또는 새 editor buffer 생성 |
| `clarify` | `message` | target 선택이나 추가 입력 유도 |
| `reject` | `message` | 지원하지 않는 요청 안내 |

Spring backend 책임:

- 사용자 인증, workspace/document 접근 권한을 먼저 검증한다.
- 현재 editor Markdown과 selection line range를 `active_markdown_context`로 전달한다.
- 대화 저장, 문서 저장, Wiki ingestion 등록은 backend 도메인 API에서 처리한다.
- `/agent/turn` 응답만으로 기존 문서를 자동 저장하지 않는다.

`llmPipeline` 책임:

- 사용자 발화를 `action`으로 분류한다.
- `markdown_edit`이면 교체할 Markdown 조각만 생성한다.
- `markdown_create`이면 새 draft에 넣을 Markdown 본문만 생성한다.
- 저장, 권한 확인, 문서 버전 관리는 수행하지 않는다.

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
| `active_markdown_context.target` | 아니오 | 교체 대상 selection/current_section 범위. 없으면 문서 전체를 대상으로 본다 |
| `active_markdown_context.document_kind` | 아니오 | 문서 종류 힌트 |

`markdown_create`는 active Markdown target 없이도 실행될 수 있다. `markdown_edit`는 active Markdown target이 없으면 전체 문서를 대상으로 실행하고, active Markdown 본문이 없을 때만 `clarify`로 응답한다.

line range 규칙:

- `start_line`, `end_line`은 1-base다.
- `end_line`은 포함 범위다.
- 프론트는 editor buffer 기준 line 번호를 보내야 한다.
- backend가 저장된 문서 버전과 editor buffer 버전을 관리한다면, 적용 직전에 버전 충돌을 확인해야 한다.

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

`action == "markdown_edit"`이면 프론트는 기존 Markdown 문서의 선택 영역, 현재 섹션, 또는 문서 전체를 교체할 수 있는 preview/diff를 보여준다.

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
| `target.type` | `selection`, `current_section`, 또는 `whole_document` |
| `target.start_line` | 교체 시작 line. 1-base |
| `target.end_line` | 교체 종료 line. 1-base, inclusive |
| `summary` | 편집 요약 |
| `replacement_markdown` | target range를 대체할 Markdown |

프론트 처리:

- `operation == "replace"`만 지원한다.
- `target.start_line`부터 `target.end_line`까지 `replacement_markdown`으로 교체한다.
- 선택 영역이 있으면 프론트는 `active_markdown_context.target`을 보낸다.
- 선택 영역이 없으면 target을 생략하고, `llmPipeline`이 `whole_document` target을 만들어 응답한다.
- 사용자가 Apply를 누르기 전까지 원본 Markdown은 변경하지 않는다.
- `edit_goal`은 preview label이나 처리 힌트로 사용할 수 있지만, 실제 적용 기준은 `edit.operation`과 `edit.target`이다.
- 적용 시점에 editor 내용이 바뀌었으면 line range가 어긋날 수 있다. 프론트 또는 backend는 적용 전 문서 버전, checksum, 최신 line range 중 하나로 충돌을 막아야 한다.

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

## 5.1 채팅의 Wiki page화와의 차이

"지금까지의 채팅을 문서로 만들어줘"는 먼저 `markdown_create`로 새 Markdown draft를 만드는 흐름이다. 이 단계에서는 아직 Wiki page가 생성되지 않는다.

```text
채팅 진행
  -> 사용자가 문서 draft 생성을 요청
  -> POST /agent/turn
  -> action=markdown_create
  -> 프론트가 새 Markdown draft 표시
  -> 사용자가 저장
  -> Spring backend가 저장된 Markdown/text를 ingestion 대상으로 등록
  -> 기존 Wiki page 생성 pipeline이 source page와 concept page 생성
```

저장된 원 채팅 자체를 검색 가능한 Wiki page로 편입하는 흐름은 `/agent/turn`이 아니라 [chat-to-wiki-contract.md](./chat-to-wiki-contract.md)를 따른다.

## 6. Clarify

`action == "clarify"`이면 `message`를 사용자에게 보여주고 추가 입력이나 target 선택을 유도한다.

대표 케이스:

- 편집 요청인데 active Markdown 본문이 없음
- template 기반 전체 문서 재구성처럼 현재 보류한 범위

## 7. Conversation Context

`markdown_create` 품질은 `conversation_context.recent_conversation_summary`에 크게 의존한다. 프론트 또는 conversation memory는 사용자가 “지금까지 이야기한 내용”을 문서로 만들라고 요청할 때 최근 합의 내용, 결정 사항, 주요 제약을 요약해서 넘겨야 한다.

## 8. 구현 계획 체크리스트

AI agent나 backend/frontend 작업자가 계획을 세울 때는 아래 순서로 나누는 것이 안전하다.

1. editor 상태 전달: 현재 Markdown, 선택 범위, 문서 id, 문서 버전 정보를 프론트에서 확보한다.
2. backend proxy: 인증과 권한을 확인한 뒤 `/agent/turn`에 `message`, `conversation_context`, `active_markdown_context`를 전달한다.
3. action 분기: `chat_answer`, `markdown_edit`, `markdown_create`, `clarify`, `reject`별 UI 처리를 분리한다.
4. 편집 적용: `markdown_edit`는 preview/diff를 먼저 보여주고 사용자 승인 후 editor buffer에만 적용한다.
5. 저장 연결: 적용된 editor buffer나 새 draft를 저장하는 API는 별도 backend 문서 API를 사용한다.
6. Wiki ingestion 연결: 저장된 Markdown을 지식화해야 할 때만 기존 Wiki page 생성 pipeline에 등록한다.
