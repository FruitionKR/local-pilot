# Markdown Edit Agent 테스트 리포트

작성일: 2026-06-28

## 범위

이번 테스트는 `llmPipeline`에 추가한 agent turn / markdown edit 흐름의 동작 계약을 확인했다.

검증 대상:

- `/agent/turn` 응답 구조
- `chat_answer | markdown_edit | clarify | reject` 분기
- Markdown 편집 요청의 `replace` operation 생성
- 선택 영역이 없을 때 clarify 처리
- template 기반 전체 문서 재구성 요청 보류
- 멀티턴에서 애매한 요청을 편집 요청으로 처리할 수 있는 구조

주의:

- 실제 LLM 호출 결과가 아니라 fake router/editor로 코드 경로와 응답 계약을 검증했다.
- 실제 모델 라우팅 품질은 `AGENT_ROUTER_LLM_API_KEY`, `MARKDOWN_EDIT_LLM_API_KEY` 또는 `UPSTAGE_API_KEY`가 설정된 환경에서 추가 확인이 필요하다.

## 자동 테스트 결과

실행 명령:

```bash
cd /Users/jaehyeong/local-pilot/llmPipeline
.venv/bin/python -m pytest tests/modules/markdown_edit tests/modules/agent -v
```

결과:

```text
tests/modules/markdown_edit/test_generate_markdown_edit.py::GenerateMarkdownEditUseCaseTest::test_rejects_empty_replacement_markdown PASSED
tests/modules/markdown_edit/test_generate_markdown_edit.py::GenerateMarkdownEditUseCaseTest::test_returns_replace_operation_for_requested_target PASSED
tests/modules/agent/test_handle_agent_turn.py::HandleAgentTurnUseCaseTest::test_asks_for_target_when_edit_has_no_markdown_target PASSED
tests/modules/agent/test_handle_agent_turn.py::HandleAgentTurnUseCaseTest::test_defers_template_transform PASSED
tests/modules/agent/test_handle_agent_turn.py::HandleAgentTurnUseCaseTest::test_executes_markdown_edit_action PASSED
tests/modules/agent/test_handle_agent_turn.py::HandleAgentTurnUseCaseTest::test_routes_chat_to_query_use_case PASSED

6 passed in 0.02s
```

전체 `llmPipeline` 테스트:

```bash
cd /Users/jaehyeong/local-pilot/llmPipeline
.venv/bin/python -m pytest
```

결과:

```text
47 passed in 0.28s
```

## 시나리오 테스트 결과

아래 시나리오는 fake router/editor를 사용해 실제 response schema 변환까지 통과시킨 결과다.

## Replace operation 적용 방식

현재 `llmPipeline`은 원본 파일이나 draft를 직접 수정하지 않는다. 대신 프론트가 적용할 수 있는 명령을 반환한다.

반환되는 핵심 명령:

```json
{
  "operation": "replace",
  "target": {
    "type": "selection",
    "start_line": 4,
    "end_line": 8
  },
  "replacement_markdown": "새 Markdown 내용"
}
```

프론트 적용 규칙:

```text
1. 현재 draft Markdown을 line 단위로 나눈다.
2. target.start_line ~ target.end_line 범위를 제거한다.
3. 같은 위치에 replacement_markdown을 삽입한다.
4. 적용 전/후를 diff 또는 preview로 보여준다.
5. 사용자가 Apply를 누를 때만 draft 상태에 반영한다.
```

주의:

- line number는 1-based다.
- `end_line`은 포함 범위다.
- 저장은 이 단계에서 하지 않는다.
- 실제 저장 API가 생기기 전까지는 프론트 draft에만 반영한다.

### 적용 예시 A. 선택 문단을 표로 변환

원본 Markdown:

```text
1  # Markdown AI Agent
2
3  ## MVP 범위
4  목표는 Markdown 편집 agent를 만드는 것이다.
5  먼저 선택 영역 편집부터 시작한다.
6  전체 문서 template 변환은 보류한다.
7  저장은 나중에 붙인다.
8
9  ## 다음 작업
10 프론트 preview와 diff를 검토한다.
```

operation:

```json
{
  "operation": "replace",
  "target": {
    "type": "selection",
    "start_line": 4,
    "end_line": 7
  },
  "summary": "선택 영역을 표 형식으로 변환했습니다.",
  "replacement_markdown": "| 항목 | 내용 |\n| --- | --- |\n| 목표 | Markdown 편집 agent 구현 |\n| 시작 범위 | 선택 영역 기반 편집 |\n| 보류 | 전체 문서 template 변환 |\n| 저장 | 후속 단계에서 처리 |"
}
```

적용 후 Markdown:

```text
1  # Markdown AI Agent
2
3  ## MVP 범위
4  | 항목 | 내용 |
5  | --- | --- |
6  | 목표 | Markdown 편집 agent 구현 |
7  | 시작 범위 | 선택 영역 기반 편집 |
8  | 보류 | 전체 문서 template 변환 |
9  | 저장 | 후속 단계에서 처리 |
10
11 ## 다음 작업
12 프론트 preview와 diff를 검토한다.
```

프론트가 보여줄 수 있는 diff 개념:

```diff
 ## MVP 범위
-목표는 Markdown 편집 agent를 만드는 것이다.
-먼저 선택 영역 편집부터 시작한다.
-전체 문서 template 변환은 보류한다.
-저장은 나중에 붙인다.
+| 항목 | 내용 |
+| --- | --- |
+| 목표 | Markdown 편집 agent 구현 |
+| 시작 범위 | 선택 영역 기반 편집 |
+| 보류 | 전체 문서 template 변환 |
+| 저장 | 후속 단계에서 처리 |

 ## 다음 작업
```

### 적용 예시 B. 선택 문단을 checklist로 변환

원본 Markdown:

```text
1  ## 구현 순서
2  Agent turn API를 만든다.
3  Markdown edit use case를 만든다.
4  프론트 preview와 diff를 연결한다.
5  Apply 전에는 원본을 바꾸지 않는다.
```

operation:

```json
{
  "operation": "replace",
  "target": {
    "type": "selection",
    "start_line": 2,
    "end_line": 5
  },
  "summary": "선택 영역을 TODO checklist로 변환했습니다.",
  "replacement_markdown": "- [ ] Agent turn API 추가\n- [ ] Markdown edit use case 추가\n- [ ] 프론트 preview/diff 연결\n- [ ] Apply 전 원본 불변 유지"
}
```

적용 후 Markdown:

```text
1  ## 구현 순서
2  - [ ] Agent turn API 추가
3  - [ ] Markdown edit use case 추가
4  - [ ] 프론트 preview/diff 연결
5  - [ ] Apply 전 원본 불변 유지
```

### 적용 예시 C. 애매한 멀티턴 요청

대화 맥락:

```text
사용자: 이 문단 너무 장황하지?
assistant: 핵심이 반복돼서 줄일 수 있습니다.
사용자: 그럼 그렇게 해줘
```

프론트가 `conversation_context.recent_conversation_summary`와 현재 선택 영역을 함께 보내면, agent는 아래와 같은 명령을 반환할 수 있다.

operation:

```json
{
  "operation": "replace",
  "target": {
    "type": "selection",
    "start_line": 3,
    "end_line": 5
  },
  "summary": "선택 문단을 간결하게 줄였습니다.",
  "replacement_markdown": "Markdown 편집 기능은 선택 영역 기반 replace operation부터 구현한다."
}
```

원본 Markdown:

```text
1  ## 방향
2
3  Markdown 편집 agent는 다양한 문서 편집 상황을 처리해야 한다.
4  다만 처음부터 전체 문서 재구성까지 포함하면 범위가 커진다.
5  그래서 선택 영역 기반 replace operation부터 구현하는 것이 좋다.
6
7  ## 보류
8  template 변환은 나중에 다룬다.
```

적용 후 Markdown:

```text
1  ## 방향
2
3  Markdown 편집 기능은 선택 영역 기반 replace operation부터 구현한다.
4
5  ## 보류
6  template 변환은 나중에 다룬다.
```

### 적용하지 않는 예시. Target 없음

편집 요청이지만 프론트가 선택 영역이나 현재 섹션 정보를 보내지 않으면 `llmPipeline`은 operation을 만들지 않는다.

응답:

```json
{
  "action": "clarify",
  "message": "수정할 Markdown 범위를 선택한 뒤 다시 요청해 주세요.",
  "chat": null,
  "edit": null
}
```

프론트 처리:

```text
1. 문서를 수정하지 않는다.
2. 사용자에게 선택 영역 또는 현재 섹션 지정이 필요하다고 표시한다.
3. 사용자가 범위를 선택한 뒤 같은 요청을 다시 보낼 수 있게 한다.
```

### 1. 표 변환

입력:

```text
이 부분을 표로 바꿔줘
```

결과:

```json
{
  "action": "markdown_edit",
  "route": {
    "action": "markdown_edit",
    "confidence": 0.91,
    "reason": "선택 영역에 대한 편집 요청입니다.",
    "edit_goal": "other"
  },
  "message": null,
  "chat": null,
  "edit": {
    "operation": "replace",
    "target": {
      "type": "selection",
      "start_line": 4,
      "end_line": 8
    },
    "summary": "선택 영역을 표 형식으로 변환했습니다.",
    "replacement_markdown": "| 항목 | 내용 |\n| --- | --- |\n| 목표 | Markdown 편집 기능 구현 |\n| 범위 | 선택 영역 기반 replace operation |"
  }
}
```

판단:

- `markdown_edit`으로 분기됐다.
- `replace` operation만 반환했다.
- target line range가 입력 그대로 보존됐다.

### 2. TODO checklist 변환

입력:

```text
TODO checklist로 바꿔줘
```

결과:

```json
{
  "action": "markdown_edit",
  "route": {
    "action": "markdown_edit",
    "confidence": 0.9,
    "reason": "체크리스트 형식 변환 요청입니다.",
    "edit_goal": "checklist"
  },
  "message": null,
  "chat": null,
  "edit": {
    "operation": "replace",
    "target": {
      "type": "selection",
      "start_line": 4,
      "end_line": 8
    },
    "summary": "선택 영역을 TODO checklist로 변환했습니다.",
    "replacement_markdown": "- [ ] Agent turn API 연결\n- [ ] Markdown edit preview 확인\n- [ ] Apply/Reject UX 검토"
  }
}
```

판단:

- 체크리스트 형식 편집도 동일한 `replace` 계약으로 처리 가능하다.

### 3. 간결화

입력:

```text
너무 장황해. 짧게 줄여줘
```

결과:

```json
{
  "action": "markdown_edit",
  "route": {
    "action": "markdown_edit",
    "confidence": 0.88,
    "reason": "선택 영역 축약 요청입니다.",
    "edit_goal": "shorten"
  },
  "message": null,
  "chat": null,
  "edit": {
    "operation": "replace",
    "target": {
      "type": "selection",
      "start_line": 4,
      "end_line": 8
    },
    "summary": "선택 문단을 간결하게 줄였습니다.",
    "replacement_markdown": "Markdown 편집 기능은 선택 영역 기반 replace operation부터 구현한다."
  }
}
```

판단:

- `shorten` 계열 편집도 동일한 흐름으로 처리된다.

### 4. 영어 번역 / 문체 변경

입력:

```text
영어 초록 스타일로 바꿔줘
```

결과:

```json
{
  "action": "markdown_edit",
  "route": {
    "action": "markdown_edit",
    "confidence": 0.86,
    "reason": "선택 영역 번역/문체 변경 요청입니다.",
    "edit_goal": "translate"
  },
  "message": null,
  "chat": null,
  "edit": {
    "operation": "replace",
    "target": {
      "type": "selection",
      "start_line": 4,
      "end_line": 8
    },
    "summary": "선택 문단을 영어로 번역했습니다.",
    "replacement_markdown": "Start with scoped Markdown edits based on the current selection, and defer full-document template transformation."
  }
}
```

판단:

- 번역/문체 변경도 selection 기반 편집으로 처리 가능하다.

### 5. 회의록 형식 변환

입력:

```text
회의록 형식으로 정리해줘
```

결과:

```json
{
  "action": "markdown_edit",
  "route": {
    "action": "markdown_edit",
    "confidence": 0.89,
    "reason": "선택 영역 형식 변경 요청입니다.",
    "edit_goal": "convert_format"
  },
  "message": null,
  "chat": null,
  "edit": {
    "operation": "replace",
    "target": {
      "type": "selection",
      "start_line": 4,
      "end_line": 8
    },
    "summary": "선택 문단을 회의록 형식으로 정리했습니다.",
    "replacement_markdown": "## 회의록\n\n- 논의 사항: Markdown 편집 agent 범위\n- 결정 사항: 선택 영역 기반 편집부터 진행\n- 보류 사항: template 기반 전체 문서 재구성"
  }
}
```

판단:

- 형식 변환은 `convert_format` 계열 edit goal로 다룰 수 있다.

### 6. 표현 다듬기

입력:

```text
말이 좀 어색한데 자연스럽게 다듬어줘
```

결과:

```json
{
  "action": "markdown_edit",
  "route": {
    "action": "markdown_edit",
    "confidence": 0.82,
    "reason": "선택 영역 문장 개선 요청입니다.",
    "edit_goal": "cleanup"
  },
  "message": null,
  "chat": null,
  "edit": {
    "operation": "replace",
    "target": {
      "type": "selection",
      "start_line": 4,
      "end_line": 8
    },
    "summary": "선택 문장의 표현을 자연스럽게 다듬었습니다.",
    "replacement_markdown": "Markdown 편집 기능은 선택 영역을 기준으로 먼저 구현하고, 전체 문서 template 변환은 후속 작업으로 분리한다."
  }
}
```

판단:

- 표현 개선처럼 가벼운 rewrite도 MVP 범위에 잘 맞는다.

### 7. 멀티턴 애매한 요청

이전 대화 요약:

```text
사용자는 선택 문단이 장황하다고 했고, assistant는 간결하게 줄일 수 있다고 답했다.
```

입력:

```text
그럼 그렇게 해줘
```

결과:

```json
{
  "action": "markdown_edit",
  "route": {
    "action": "markdown_edit",
    "confidence": 0.72,
    "reason": "최근 대화에서 선택 문단을 줄이자는 맥락이 있습니다.",
    "edit_goal": "shorten"
  },
  "message": null,
  "chat": null,
  "edit": {
    "operation": "replace",
    "target": {
      "type": "selection",
      "start_line": 4,
      "end_line": 8
    },
    "summary": "선택 영역을 요청에 맞게 수정했습니다.",
    "replacement_markdown": "현재 선택 영역을 요청 의도에 맞게 정리했습니다."
  }
}
```

판단:

- 멀티턴에서 애매한 발화를 `conversation_context`로 보강할 수 있는 구조다.
- 실제 모델 테스트에서는 이 케이스가 가장 중요하다.
- 모델이 `chat_answer`로 잘못 분류할 가능성이 있어 라우터 prompt와 평가셋 보강이 필요하다.

### 8. 편집 요청이지만 target 없음

입력:

```text
이 부분을 표로 바꿔줘
```

상태:

```text
active_markdown_context 없음
```

결과:

```json
{
  "action": "clarify",
  "route": {
    "action": "markdown_edit",
    "confidence": 0.77,
    "reason": "편집 요청이지만 활성 선택 영역이 없습니다.",
    "edit_goal": "convert_format"
  },
  "message": "수정할 Markdown 범위를 선택한 뒤 다시 요청해 주세요.",
  "chat": null,
  "edit": null
}
```

판단:

- target이 없으면 edit을 실행하지 않는다.
- 프론트는 이 응답을 보고 selection/current_section 선택을 유도할 수 있다.

### 9. Template 변환 보류

입력:

```text
전체 문서를 회사 template에 맞춰줘
```

결과:

```json
{
  "action": "clarify",
  "route": {
    "action": "clarify",
    "confidence": 1.0,
    "reason": "template/full-document transform is deferred",
    "edit_goal": "template_transform"
  },
  "message": "현재는 선택 영역이나 현재 섹션 단위 편집만 지원합니다. template 기반 전체 문서 재구성은 이후 단계에서 다루겠습니다.",
  "chat": null,
  "edit": null
}
```

판단:

- 이번 MVP 범위에서 제외한 template 기반 전체 문서 재구성을 실행하지 않는다.
- 나중에 `template_transform` action을 추가할 자리는 남아 있다.

## 현재 결론

현재 구조는 편집 기능 MVP에 필요한 기본 계약을 충족한다.

확인된 점:

- 편집 요청은 `markdown_edit`으로 표현 가능하다.
- 다양한 편집 유형을 모두 `replace` operation 하나로 수용할 수 있다.
- target이 없으면 안전하게 `clarify`로 돌아간다.
- template/전체 문서 재구성은 보류된다.
- 멀티턴 애매한 발화도 구조적으로 처리 가능하다.

남은 리스크:

- 실제 LLM router가 애매한 멀티턴 요청을 안정적으로 `markdown_edit`으로 분류하는지 아직 검증하지 않았다.
- 실제 LLM editor가 `replacement_markdown`에서 원문 의미를 훼손하지 않는지 아직 검증하지 않았다.
- Markdown table, code fence, citation/link 보존 품질은 실제 모델 평가셋이 필요하다.

## 다음 검증 제안

실제 모델을 연결한 뒤 아래 케이스를 평가셋으로 고정하는 것이 좋다.

```text
1. “이 부분을 표로 바꿔줘”
2. “TODO checklist로 바꿔줘”
3. “너무 장황해. 짧게 줄여줘”
4. “영어 초록 스타일로 바꿔줘”
5. “회의록 형식으로 정리해줘”
6. “말이 좀 어색한데 자연스럽게 다듬어줘”
7. “그럼 그렇게 해줘”
8. “아까 말한 방식으로 바꿔줘”
9. “이 문서를 회사 template에 맞춰줘”
10. “전체 구조를 원문 PDF처럼 다시 만들어줘”
```

특히 7, 8번은 멀티턴 라우팅 품질을 보는 핵심 케이스다.
