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

현재 구현 상태:

| 구성 요소 | 상태 | 범위 |
| --- | --- | --- |
| `llmPipeline` | 구현됨 | routing, target slicing, Markdown 구조 보호, sLLM 편집, 검증, 1회 재시도, 오류 응답 |
| Spring backend | 미구현 | 인증·권한 확인, proxy DTO, 문서 버전 envelope, 오류 전달 |
| frontend | 미구현 | editor selection 수집, preview/diff, Apply/Reject, 충돌 감지 |

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
    }
  }
}
```

입력 필드:

| 필드 | 타입 | 필수 | 생성 주체 | 설명 |
| --- | --- | --- | --- | --- |
| `message` | string | 예 | frontend | 사용자가 이번 turn에 입력한 원문 발화다. |
| `conversation_context` | object | 아니오 | backend 또는 대화 memory | 멀티턴 문맥을 담는 묶음이다. 없으면 현재 발화만으로 판단한다. |
| `conversation_context.recent_conversation_summary` | string 또는 null | 아니오 | backend 또는 대화 memory | 이전 대화 전체가 아니라 최근 합의·요구사항을 압축한 요약이다. follow-up routing과 문서 생성에 사용한다. |
| `conversation_context.reference_context` | object | 아니오 | backend | 현재 요청에 필요한 구조화된 추가 참고 정보다. 기본값은 빈 object다. |
| `active_markdown_context` | object | 편집 시 필요 | frontend snapshot | 요청 시점의 editor 본문과 target을 묶는다. 새 문서 생성이나 일반 질문에는 생략할 수 있다. |
| `active_markdown_context.markdown` | string | 편집 시 필요 | frontend editor | 저장본이 아니라 사용자가 보고 있는 저장 전 editor 전체 Markdown이다. |
| `active_markdown_context.target` | object | 아니오 | frontend editor | 실제 교체 대상 line 범위다. 생략하면 문서 전체 편집으로 처리한다. |
| `active_markdown_context.target.type` | enum string | target 사용 시 | frontend | `selection`, `current_section`, `whole_document` 중 하나다. |
| `active_markdown_context.target.start_line` | integer | target 사용 시 | frontend | 교체 시작 line이다. 1-base이며 1 이상이어야 한다. |
| `active_markdown_context.target.end_line` | integer | target 사용 시 | frontend | 교체 마지막 line이다. 1-base inclusive이며 Markdown 전체 line 수를 넘을 수 없다. |

`markdown_create`는 active Markdown target 없이도 실행될 수 있다. `markdown_edit`는 active Markdown target이 없으면 전체 문서를 대상으로 실행하고, active Markdown 본문이 없을 때만 `clarify`로 응답한다.

line range 규칙:

- `start_line`, `end_line`은 1-base다.
- `end_line`은 포함 범위다.
- 프론트는 editor buffer 기준 line 번호를 보내야 한다.
- backend가 저장된 문서 버전과 editor buffer 버전을 관리한다면, 적용 직전에 버전 충돌을 확인해야 한다.
- `selection`은 사용자가 실제로 선택한 line 전체 범위다. 문자 단위 selection은 현재 계약에서 지원하지 않는다.
- `current_section`은 frontend가 현재 heading부터 다음 동일·상위 heading 직전까지 계산해 line 범위를 보낸다. `llmPipeline`은 section 경계를 다시 계산하지 않는다.
- `whole_document`를 명시할 때는 `start_line=1`, `end_line=현재 Markdown 전체 line 수`를 보낸다. `target`을 생략해도 `llmPipeline`이 같은 범위를 만든다.
- fenced/indented code block, HTML block, GFM table, frontmatter, 여러 줄 footnote definition, display math의 일부만 포함하는 target은 허용되지 않는다. 구조 전체를 포함하도록 selection을 확장해야 한다.

`target.type` 값:

| 값 | 의미 | 범위 계산 주체 | 적용 방식 |
| --- | --- | --- | --- |
| `selection` | 사용자가 선택한 line 범위 | frontend | 해당 line만 replacement로 교체 |
| `current_section` | cursor가 속한 Markdown heading section 전체 | frontend | heading부터 다음 동일·상위 heading 직전까지 교체 |
| `whole_document` | editor Markdown 전체 | frontend 또는 target 생략 시 pipeline | 전체 buffer를 replacement로 교체 |

### 2.1 편집 요청 예시

선택 영역 cleanup:

```json
{
  "message": "선택한 문장만 자연스럽고 간결하게 다듬어줘.",
  "conversation_context": {
    "recent_conversation_summary": "배포 가이드 문체를 정리하고 있다.",
    "reference_context": {}
  },
  "active_markdown_context": {
    "markdown": "# 배포 안내\n\n배포를 하기 전에 테스트를 한다.\n\n문제가 없으면 승인한다.",
    "target": {
      "type": "selection",
      "start_line": 3,
      "end_line": 3
    }
  }
}
```

현재 section 번역:

```json
{
  "message": "현재 섹션의 보이는 영어 문장을 한국어로 번역해줘.",
  "active_markdown_context": {
    "markdown": "# Guide\n\n## Install\n\nRead the guide.\n\n## Deploy\n\nRun the command.",
    "target": {
      "type": "current_section",
      "start_line": 3,
      "end_line": 6
    }
  }
}
```

문서 전체 편집은 `target`을 생략하는 방식을 권장한다. 명시적으로 보낼 경우 전체 line 범위와 정확히 일치해야 한다.

## 3. 공통 응답

`action`은 프론트 처리 방식을 결정한다.

```json
{
  "action": "chat_answer | markdown_edit | markdown_create | clarify | reject",
  "route": {
    "action": "chat_answer | markdown_edit | markdown_create | clarify | reject",
    "confidence": 0.92,
    "reason": "brief reason",
    "edit_goal": "shorten | style_change | convert_format | bullet_list | checklist | translate | cleanup | template_transform | create_from_chat | other"
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

`action` 값:

| 값 | 의미 | 주요 결과 필드 | frontend 처리 |
| --- | --- | --- | --- |
| `chat_answer` | 문서 변경이 아닌 일반 질문에 답변 | `chat` | 기존 채팅 답변 UI로 표시 |
| `markdown_edit` | 현재 Markdown의 일부 또는 전체를 변경 | `edit` | diff preview 후 Apply/Reject |
| `markdown_create` | 대화나 참고 정보로 새 Markdown draft 생성 | `generated_markdown` | 기존 문서와 분리된 새 draft로 열기 |
| `clarify` | 편집 대상이나 요청 범위가 부족함 | `message` | 추가 입력 또는 target 선택 유도 |
| `reject` | 안전 또는 지원 범위 밖의 요청 | `message` | 원본을 유지하고 거절 사유 안내 |

`route.edit_goal` 값은 router와 pipeline 내부 편집 전략을 선택하기 위한 힌트다. frontend가 Markdown을 적용할 때 직접 분기 기준으로 사용하지 않는다.

| 값 | 의미 | 대표 요청 |
| --- | --- | --- |
| `cleanup` | 뜻과 구조를 유지하면서 어색한 문장이나 중복 표현 정리 | “문장을 자연스럽게 다듬어줘” |
| `shorten` | 핵심 정보와 literal을 유지하면서 내용 축약 | “한 문장으로 짧게 줄여줘” |
| `style_change` | Markdown 구조 변경 없이 문체·톤 변경 | “더 공식적인 문체로 바꿔줘” |
| `translate` | 보이는 문장만 번역하고 URL·code·Markdown marker 보존 | “영어 문장을 한국어로 번역해줘” |
| `bullet_list` | 일반 bullet 또는 중첩 bullet 목록으로 변환 | “항목을 bullet 목록으로 바꿔줘” |
| `checklist` | checkbox가 있는 task list로 변환 | “TODO checklist로 만들어줘” |
| `convert_format` | 표, 번호 목록, 인용문, heading, code block, 수식, Mermaid, 회의록처럼 Markdown 구조 변경 | “표로 정리해줘” |
| `template_transform` | 외부 template 적용 또는 전체 구조 재구성 요청 | 현재는 `clarify`로 응답하는 보류 범위 |
| `create_from_chat` | 현재 대화로 새 Markdown 문서 생성 | “지금까지 얘기한 내용으로 문서 만들어줘” |
| `other` | 위 목표로 명확히 분류되지 않는 요청 | router가 추가 문맥을 기준으로 처리 |

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
- 응답의 `edit.target`이 요청 target과 같은지 확인한다. `llmPipeline`도 이를 검사하지만 Apply 직전 방어 검사를 유지한다.
- `replacement_markdown`은 target 조각만 포함한다. 요청에서 보낸 앞뒤 문맥이나 문서 전체가 포함된 것으로 간주하면 안 된다.
- line ending은 editor buffer 형식에 맞춰 적용한다. `replacement_markdown`의 `\n`을 그대로 문자열 split/join해 문서 마지막 newline을 유실하지 말고 editor의 line-range edit API를 사용한다.

권장 frontend 상태 전이:

```text
idle
  -> requesting: editor snapshot + target 고정
  -> preview: 원본 target과 replacement_markdown diff 표시
  -> applied: snapshot이 현재 buffer와 같을 때만 line range 교체
  -> rejected: 원본 유지
  -> conflict: 요청 이후 buffer가 바뀌었으면 재요청 유도
```

Apply는 다음 조건을 모두 만족할 때만 수행한다.

1. `action == "markdown_edit"`
2. `edit.operation == "replace"`
3. 응답 target이 요청 target과 일치
4. 요청 당시 editor revision 또는 buffer checksum이 현재 값과 일치
5. 사용자가 preview에서 Apply를 명시적으로 선택

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

### 6.1 Markdown 편집 계약 실패

모델이 재시도 후에도 Markdown 문법 또는 구조 보존 조건을 충족하지 못하면 `/agent/turn`은 HTTP 422를 반환한다.

```json
{
  "detail": {
    "code": "markdown_output_contract_failed",
    "message": "Markdown 편집 결과가 문법 및 보존 조건을 충족하지 못했습니다."
  }
}
```

- 실패한 replacement는 적용하거나 preview하지 않는다.
- 내부 validator 항목, 보호 token과 model 원문 출력은 외부 응답에 포함하지 않는다.
- 사용자는 같은 요청을 다시 시도하거나 편집 범위를 줄일 수 있다.

### 6.2 분리할 수 없는 Markdown 구조 선택

여러 줄 Markdown 구조의 일부만 target으로 보내면 HTTP 422를 반환한다.

```json
{
  "detail": {
    "code": "markdown_target_crosses_structure",
    "message": "선택 범위가 분리할 수 없는 Markdown 구조의 일부만 포함합니다.",
    "structure": "fence",
    "start_line": 3,
    "end_line": 5
  }
}
```

- frontend는 `start_line~end_line` 구조 전체를 다시 선택할 수 있도록 안내한다.
- backend는 이 422를 generic 500으로 바꾸지 말고 `detail`을 그대로 전달한다.
- 같은 잘못된 target으로 자동 재시도하지 않는다.

### 6.3 오류 처리 표

| HTTP | 조건 | backend 처리 | frontend 처리 |
| ---: | --- | --- | --- |
| 400 | 빈 instruction, 잘못된 line 범위, 문서 line 수 초과 | 요청 오류로 전달하고 기록 | editor snapshot과 target을 다시 계산 |
| 422 + `markdown_output_contract_failed` | sLLM 출력이 재시도 후에도 문법·보존 계약 실패 | 자동 저장·적용 금지 | 재시도 또는 범위 축소 안내 |
| 422 + `markdown_target_crosses_structure` | target이 여러 줄 구조 일부만 포함 | detail 유지 | 구조 전체 선택 안내 |
| 500 | LLM 연결 실패 또는 예상하지 못한 pipeline 오류 | correlation id와 함께 서버 오류 처리 | 원본 유지, 일반 재시도 안내 |

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

## 9. Backend 연동 명세

Spring backend는 browser가 `llmPipeline`을 직접 호출하지 않게 하는 proxy이자 권한·버전 경계다. backend public endpoint 이름은 구현 시 기존 API naming에 맞춰 결정하되, 아래 정보는 보존해야 한다.

권장 browser → backend 요청 envelope:

```json
{
  "documentId": "document-id",
  "baseVersion": 12,
  "message": "선택한 문장을 다듬어줘.",
  "conversationContext": {
    "recentConversationSummary": "최근 대화 요약",
    "referenceContext": {}
  },
  "editorSnapshot": {
    "markdown": "# 현재 editor의 저장 전 본문",
    "target": {
      "type": "selection",
      "startLine": 3,
      "endLine": 5
    }
  }
}
```

Backend public 요청 필드:

| 필드 | 타입 | 필수 | 설명 | Pipeline 변환 |
| --- | --- | --- | --- | --- |
| `documentId` | string | 예 | 사용자가 편집 중인 일반 문서 식별자다. backend의 권한 검사와 저장 대상 확인에 사용한다. | 전달하지 않음 |
| `baseVersion` | integer | 예 | editor가 시작된 저장 문서 version이다. Apply 이후 optimistic locking 기준이다. | 전달하지 않음 |
| `message` | string | 예 | 사용자의 현재 편집 지시다. | `message` |
| `conversationContext` | object | 아니오 | 멀티턴 대화 요약과 추가 참고 정보 묶음이다. | `conversation_context` |
| `conversationContext.recentConversationSummary` | string 또는 null | 아니오 | 직전 합의와 편집 의도를 압축한 요약이다. | `conversation_context.recent_conversation_summary` |
| `conversationContext.referenceContext` | object | 아니오 | 요청에 필요한 구조화된 참고 정보다. | `conversation_context.reference_context` |
| `editorSnapshot` | object | 편집 시 필요 | 요청 시점의 저장 전 editor 상태다. 응답을 기다리는 동안 변경되지 않는 snapshot으로 취급한다. | `active_markdown_context` |
| `editorSnapshot.markdown` | string | 편집 시 필요 | 사용자가 현재 보고 있는 전체 Markdown이다. | `active_markdown_context.markdown` |
| `editorSnapshot.target` | object | 아니오 | 교체할 line 범위다. 생략하면 전체 문서 편집이 될 수 있다. | `active_markdown_context.target` |
| `editorSnapshot.target.type` | enum string | target 사용 시 | `selection`, `current_section`, `whole_document` 중 하나다. | `active_markdown_context.target.type` |
| `editorSnapshot.target.startLine` | integer | target 사용 시 | 1-base 교체 시작 line이다. | `active_markdown_context.target.start_line` |
| `editorSnapshot.target.endLine` | integer | target 사용 시 | 1-base inclusive 교체 마지막 line이다. | `active_markdown_context.target.end_line` |

`documentId`와 `baseVersion`은 backend/frontend 동시성 계약이며 `llmPipeline` 입력이 아니다. `llmPipeline`은 저장소나 문서 version을 알지 못한다.

권장 backend → browser 응답 envelope:

```json
{
  "documentId": "document-id",
  "baseVersion": 12,
  "requestId": "correlation-id",
  "result": {
    "action": "markdown_edit",
    "route": {
      "action": "markdown_edit",
      "confidence": 0.95,
      "reason": "선택 문장 정리 요청",
      "edit_goal": "cleanup"
    },
    "message": null,
    "chat": null,
    "edit": {
      "operation": "replace",
      "target": {
        "type": "selection",
        "start_line": 3,
        "end_line": 5
      },
      "summary": "선택 문장을 자연스럽게 정리했습니다.",
      "replacement_markdown": "교체할 Markdown"
    },
    "generated_markdown": null
  }
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `documentId` | string | 요청 대상 문서 식별자다. 요청 값과 같아야 한다. |
| `baseVersion` | integer | 요청 snapshot이 기준으로 삼은 저장 version이다. Apply 충돌 검사에 사용한다. |
| `requestId` | string | backend log와 오류를 연결하는 correlation id다. |
| `result` | object | pipeline의 `AgentTurnResponse`다. 내부 필드는 `3. 공통 응답`부터 `6. Clarify`까지의 계약을 따른다. |

backend는 다음 순서로 처리한다.

1. 사용자, workspace와 `documentId` 접근 권한을 검증한다.
2. `baseVersion`이 현재 저장 버전보다 오래됐는지 확인하되, 저장 전 editor snapshot 자체는 변경하지 않는다.
3. camelCase public DTO를 pipeline snake_case 계약으로 변환해 `POST /agent/turn`에 전달한다.
4. pipeline 응답을 `documentId`, `baseVersion`, 요청 correlation id와 묶어 frontend에 반환한다.
5. `markdown_edit` 응답만으로 문서를 저장하지 않는다. Apply 이후 별도 문서 저장 API가 optimistic version 조건으로 저장한다.

backend proxy 주의사항:

- `active_markdown_context.markdown`은 저장된 본문이 아니라 요청 당시 editor snapshot이어야 한다.
- Wiki page처럼 읽기 전용인 문서는 frontend에서 편집 UI를 제공하지 않고 backend 권한·문서 유형 검사에서도 `/agent/turn` 전달 전에 차단한다. Pipeline 요청에 문서 종류 힌트를 보내 편집 가능 여부를 판단하게 하지 않는다.
- pipeline timeout은 router 호출과 editor 1회 재시도를 포함할 수 있게 구성한다.
- 400/422의 구조화된 `detail`을 보존한다.
- prompt, 보호 token, 원문 전체와 잘못된 model 출력을 application log에 남기지 않는다.
- pipeline 응답의 target이나 replacement를 backend가 임의 보정하지 않는다.

## 10. Frontend 연동 명세

frontend는 요청 직전에 다음 값을 하나의 immutable snapshot으로 묶는다.

- 현재 editor Markdown 전체
- target type과 1-base inclusive line 범위
- editor revision 또는 전체 buffer checksum
- backend가 제공한 document id와 base version

선택 범위 계산:

- selection이 있으면 시작 line과 종료 line을 포함하는 `selection` target을 만든다.
- cursor만 있고 “현재 섹션” 요청이면 Markdown heading 구조를 기준으로 `current_section` 전체 범위를 계산한다.
- selection이 fence/table/frontmatter 같은 구조 일부만 포함하면 가능하면 요청 전에 구조 전체로 확장한다. 확장하지 못해도 pipeline 422를 처리해야 한다.
- target이 없으면 전체 문서 편집이 될 수 있으므로, destructive scope를 preview에 명확히 표시한다.

응답 처리:

```text
backend response
  -> action 분기
  -> markdown_edit이면 요청 snapshot의 target과 응답 target 비교
  -> 원본 target vs replacement_markdown diff 생성
  -> Apply/Reject 대기
  -> Apply 시 revision 재확인
  -> editor line-range API로 한 번의 replace transaction 수행
  -> 별도 저장 API 호출
```

frontend는 `route.reason`을 사용자에게 그대로 노출하지 않는 것을 권장한다. 이 값은 모델이 생성한 진단 힌트이며 UI 계약이나 신뢰 가능한 설명이 아니다.

## 11. 검증 및 재현 방법

자동 테스트:

```bash
cd llmPipeline
.venv/bin/python -m pytest -q
```

- 결과: 220 passed, warning 1개, subtest 28개 통과
- domain validator, source-range 추출·치환, target slicing, 구조 경계, editor retry, HTTP 오류 mapping과 agent use case를 포함한다.
- warning은 FastAPI `TestClient`가 사용하는 Starlette/httpx 호환성 deprecation warning이다.

실제 `/agent/turn` + 로컬 Qwen E2E:

```bash
cd llmPipeline
.venv/bin/python markdown_agent_http_lab.py
```

- `qwen2.5:7b` router와 editor를 실제 FastAPI dependency에 연결한다.
- selection cleanup, structured translation의 URL/code 보존, 부분 fence 선택 422를 검사한다.
- 최종 결과: 3/3 통과

Markdown/GFM 전체 기능 반복 평가:

```bash
cd llmPipeline
.venv/bin/python markdown_edit_gfm_lab.py --with-router --repeat 3 --failures-only
```

- 19개 case를 3회씩 실행한다.
- 최신 결과: 55/57
- 두 실패는 `시작 안내`를 의미상 같은 `시작 방법`으로 바꾼 축약 결과를 exact 문자열 evaluator가 실패로 본 경우다.
- Markdown 구조, 문법과 literal anchor 위반은 없었다.

장문 context benchmark:

```bash
cd llmPipeline
.venv/bin/python markdown_context_benchmark.py
```

- 1천 줄: 원문 31,999자, 실제 editor JSON 기준 model request payload 1,786자, 평균 전처리 6.828ms
- 5천 줄: 원문 159,999자, 실제 editor JSON 기준 model request payload 1,788자, 평균 전처리 33.539ms
- 두 경우 모두 target 앞뒤 각 20줄만 읽기 전용 context로 전달한다.
