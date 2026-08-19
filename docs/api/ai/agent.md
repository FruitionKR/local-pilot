# Agent API

[API 문서](../README.md) / [ai-svc](README.md)

Agent turn과 run·artifact·Tool 인가 내부 API다. 공개 Gateway 계약은
[`document-svc Agent API`](../document/agent.md)다. Agent turn은 운영 경로에서 Kafka로 실행하며,
`/agent/runs/**`와 artifact 조회·Tool 인가·상태 조회는 Backend가 내부 HTTP로 호출한다.
`POST /internal/agent/runs/artifacts/register`는 현재 저장소 안에 운영 호출자가 없다.

- API 수: 12

## API 목차

| API | 목적 |
|---|---|
| [`POST /agent/turn`](#summary-post-agent-turn) | Agent 요청을 분류하고 Query·문서 생성·편집 작업을 실행합니다. |
| [`GET /agent/runs/{run_id}`](#summary-get-agent-runs-run-id) | 승인형 Agent run의 현재 계획과 상태를 조회합니다. |
| [`POST /agent/runs/{run_id}/approve`](#summary-post-agent-runs-run-id-approve) | 현재 계획의 version과 operation hash를 검증해 승인합니다. |
| [`POST /agent/runs/{run_id}/cancel`](#summary-post-agent-runs-run-id-cancel) | 실행 가능한 Agent run을 취소합니다. |
| [`POST /agent/runs/{run_id}/reject`](#summary-post-agent-runs-run-id-reject) | 현재 Agent 계획을 거절합니다. |
| [`POST /agent/runs/{run_id}/revise`](#summary-post-agent-runs-run-id-revise) | 사용자 지시로 새 계획을 요청합니다. |
| [`POST /internal/agent/runs/artifacts/list`](#summary-post-internal-agent-runs-artifacts-list) | Agent 실행에 등록된 artifact 목록을 조회합니다. |
| [`POST /internal/agent/runs/artifacts/register`](#summary-post-internal-agent-runs-artifacts-register) | Agent 실행 결과 artifact를 등록합니다. |
| [`POST /internal/agent/runs/artifacts/resolve`](#summary-post-internal-agent-runs-artifacts-resolve) | Agent artifact의 저장 위치와 메타데이터를 확인합니다. |
| [`POST /internal/agent/runs/tool-authorizations/execute`](#summary-post-internal-agent-runs-tool-authorizations-execute) | Agent Tool 변경 작업의 실행 권한을 검증합니다. |
| [`POST /internal/agent/runs/tool-authorizations/read`](#summary-post-internal-agent-runs-tool-authorizations-read) | Agent Tool 읽기 작업의 실행 권한을 검증합니다. |
| [`GET /internal/agent/runs/{run_id}`](#summary-get-internal-agent-runs-run-id) | Markdown Agent 실행 상태와 결과를 조회합니다. |

`/agent/runs/*` API는 `AGENT_SKILLS_ENABLED=true`일 때만 노출되며
`X-Agent-Service-Token`으로 보호한다.

## 한눈에 보기

<a id="summary-post-agent-turn"></a>
### `POST /agent/turn`

| 항목 | 내용 |
|---|---|
| 목적 | Agent 요청을 분류하고 Query·문서 생성·편집 작업을 실행합니다. |
| 입력 | **Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string` / `null`, `X-Agent-Service-Token`(조건부 필수: `AGENT_SKILLS_ENABLED=true`): `string` / `null`<br>**Body** — `AgentTurnRequestBody` |
| 출력 | `200` 성공 — `AgentTurnResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>`AGENT_SKILLS_ENABLED=true`이면 Agent 서비스 토큰도 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 또는 Agent 서비스 인증 토큰 누락·불일치<br>`503` 내부 또는 Agent 서비스 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-agent-turn"></a>
### `POST /agent/turn` 상세

#### 1. Method + Path

`POST /agent/turn`

#### 2. 목적

Agent 요청을 분류하고 Query·문서 생성·편집 작업을 실행합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.
- `AGENT_SKILLS_ENABLED=true`이면 `X-Agent-Service-Token`도 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 예 (인증 계층 검증) | - |
| header | `X-Agent-Service-Token` | `string` | 조건부 (`AGENT_SKILLS_ENABLED=true`) | - |

- Content-Type: `application/json` (`AgentTurnRequestBody`)

아래 예시는 직전 Markdown 편집 미리보기를 그대로 저장하는 요청입니다.

```json
{
  "message": "이대로 저장해줘",
  "provider": "openai",
  "model": "gpt-5-nano",
  "workspace_id": "workspace_123",
  "user_id": "user_123",
  "document_id": "doc_123",
  "base_version": 3,
  "active_markdown_context": {
    "markdown": "# 회의록\n\n기존 내용",
    "target": {
      "type": "whole_document",
      "start_line": 1,
      "end_line": 3
    }
  },
  "conversation_context": {
    "recent_conversation_summary": null,
    "recent_messages": [
      {
        "role": "assistant",
        "content": "편집 미리보기를 만들었습니다.",
        "action": "markdown_edit",
        "run_id": "agent_preview_123",
        "agent_route": {
          "action": "markdown_edit",
          "retrieval_source": "workspace",
          "document_operation": "edit",
          "persist": false,
          "edit_goal": "other",
          "selected_skill_id": null
        }
      }
    ],
    "reference_context": null,
    "pending_skill_proposal": null
  }
}
```

저장·반영을 명시한 열린 문서 편집을 승인 계획으로 만들 때는 `active_markdown_context`와 함께
`document_id`·`base_version`을 전달한다. 파이프라인은 해당 문서와 버전에만 적용 가능한
`apply_document_edit` 아티팩트를 만들어 계획 범위를 제한한다.
미리보기만 필요한 일반 편집은 두 필드를 생략할 수 있다.

Query·Markdown·Ingest·Lint의 LLM 호출은 공통 client에서 문서·검색·대화 내용을 untrusted data로
격리하고 숫자 개인정보를 provider 전송 전에 마스킹한다. 일반 문서 안의 보안 예문이나 연락처는
Skill 지시문처럼 실행 가능한 명령으로 간주하지 않으며, 저장은 승인 계획을 거친다. Skill 지시문과
Agent 실행 계획에는 별도의 권한·tool·승인 검사를 계속 적용한다.

`conversation_context.recent_messages[].action`은 이전 assistant 응답의 action을 전달하는 선택 필드다.
라우터는 이를 멀티턴 연속성 힌트로만 사용하며 현재 요청의 명시적 의도를 우선한다.
`recent_messages[].agent_route`는 Backend가 저장된 실행 결과에서 조립한 이전 route 힌트다.
라우터는 생략된 검색 출처·문서 작업을 이해하는 데만 사용하며 현재 요청과 충돌하면 현재 요청을 우선한다.
`recent_messages[].run_id`도 선택 필드이며, 사용자가 이전 Markdown 미리보기를 저장하겠다고 확인하면
같은 workspace·user의 완료 결과를 조회해 생성 또는 편집 결과를 재사용한다. 편집은 document·base version과
`source_markdown_sha256`까지 일치해야 한다. 일치하는 실행을 확인할 수 없으면 새 승인 작업을 만들지 않고
미리보기를 다시 요청한다.
복합 요청은 `retrieval_source`(`none|workspace|web`),
`document_operation`(`none|create|edit`), `persist`, `required_capabilities`로 분해한 뒤 전체 조합을
대표하는 action을 선택한다. `required_capabilities`는 요청의 모든 절에 필요한
`document-create|document-edit|folder-organize|template`을 담는다. 서버는 일부 capability만 가진
Skill을 선택하지 않으며, 선택된 Skill의 capability별 확정 Tool 권한을 합쳐 planner에 전달한다.
서버는 이 의미를 문장 패턴으로 덮어쓰지 않고, action과 필드 조합이 모순될 때만
LLM에 한 번 재요청한다. 두 번째 응답도 계약을 만족하지 못하면 HTTP 422로 종료한다.
응답 action은 내부 문서 근거 조회인 `chat_answer`, 대화 맥락만으로 작성·형식을 이어가는
`conversation_reply`, 열린 Markdown을 변경하는 `markdown_edit`를 구분한다.
내부 문서 근거 조회와 새 문서 저장 또는 열린 문서 편집을 함께 요청하면 Query 파이프라인이 먼저
평가한 답변과 evidence snippet을 Markdown 생성·편집 입력에 포함한다. 허용된 웹 검색은 새 문서
생성 요청에서 같은 흐름을 사용하며 `allow_web_search=true`일 때만 실행한다. 생성 결과는
`create_document`, 편집 결과는 `apply_document_edit` 아티팩트로 `workspace_workflow` 승인 계획에
전달한다.

#### 5. Response body

- HTTP `200`: `AgentTurnResponse`
- action에 따라 아래 결과 필드 하나를 중심으로 사용합니다.

| action | 주요 결과 |
|---|---|
| `chat_answer` | `chat` |
| `conversation_reply`, `clarify`, `reject` | `message` |
| `markdown_edit` | `edit`, `source_markdown_sha256` |
| `markdown_create` | `generated_markdown` |
| `workspace_workflow`, `folder_organize` | `run_id`, `run_status` |
| `skill_authoring`, `skill_draft_proposal` | `skill_authoring` |

Markdown 편집 미리보기 응답 예시:

```json
{
  "action": "markdown_edit",
  "route": {
    "action": "markdown_edit",
    "confidence": 0.98,
    "reason": "현재 문서 편집 미리보기 요청",
    "edit_goal": "other",
    "selected_skill_id": null,
    "skill_candidates": [],
    "retrieval_source": "workspace",
    "document_operation": "edit",
    "persist": false,
    "required_capabilities": ["document-edit"]
  },
  "updated_conversation_summary": null,
  "message": null,
  "chat": null,
  "edit": {
    "operation": "replace",
    "requested_target": {
      "type": "whole_document",
      "start_line": 1,
      "end_line": 3
    },
    "actual_target": {
      "type": "whole_document",
      "start_line": 1,
      "end_line": 3
    },
    "scope_expanded": false,
    "changed": true,
    "summary": "회의록을 정리했습니다.",
    "replacement_markdown": "# 회의록\n\n- 결정 사항"
  },
  "source_markdown_sha256": "1f2f993be5295526ba6702d640d759663846f1605fa86254905978244c3451d3",
  "generated_markdown": null,
  "skill_candidates": [],
  "run_id": null,
  "run_status": null,
  "skill_authoring": null
}
```

#### 6. Error response

- HTTP `401`: 내부 또는 Agent 서비스 인증 토큰 누락·불일치
- HTTP `503`: 내부 또는 Agent 서비스 인증 미설정

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- `AGENT_SKILLS_ENABLED=true`이면 올바른 Agent 서비스 토큰도 필요하다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

직전 assistant 메시지의 `run_id`와 canonical `agent_route`를 함께 보내면, AI는 저장된
미리보기 결과와 현재 editor snapshot을 검증한 뒤 새 LLM 편집을 만들지 않고 승인 run을 시작합니다.

```bash
curl -X POST "$PIPELINE/agent/turn" \
  -H 'X-Internal-Token: <value>' \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"message":"이대로 저장해줘","provider":"openai","model":"gpt-5-nano","workspace_id":"workspace_123","user_id":"user_123","document_id":"doc_123","base_version":3,"active_markdown_context":{"markdown":"# 회의록\n\n기존 내용","target":{"type":"whole_document","start_line":1,"end_line":3}},"conversation_context":{"recent_messages":[{"role":"assistant","content":"편집 미리보기를 만들었습니다.","action":"markdown_edit","run_id":"agent_preview_123","agent_route":{"action":"markdown_edit","retrieval_source":"workspace","document_operation":"edit","persist":false,"edit_goal":"other","selected_skill_id":null}}]}}'
```

```json
{
  "action": "workspace_workflow",
  "route": {
    "action": "workspace_workflow",
    "confidence": 1.0,
    "reason": "확인한 편집안 저장 요청",
    "edit_goal": "other",
    "selected_skill_id": null,
    "skill_candidates": [],
    "retrieval_source": "workspace",
    "document_operation": "edit",
    "persist": true,
    "required_capabilities": ["document-edit"]
  },
  "updated_conversation_summary": null,
  "message": null,
  "chat": null,
  "edit": null,
  "source_markdown_sha256": null,
  "generated_markdown": null,
  "skill_candidates": [],
  "run_id": "run_123",
  "run_status": "queued",
  "skill_authoring": null
}
```

현재 Markdown의 SHA-256, document, base version 또는 소유권이 이전 미리보기와 다르면
`workspace_workflow` run을 만들지 않고 `action=clarify`를 반환합니다.

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: handle_agent_turn_agent_turn_post`)

[↑ 요약으로 돌아가기](#summary-post-agent-turn)

</details>

<a id="summary-get-agent-runs-run-id"></a>
### `GET /agent/runs/{run_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 승인형 Agent run의 현재 계획과 상태를 조회합니다. |
| 입력 | **Path** — `run_id`: `string`<br>**Query** — `workspace_id`, `user_id`: `string`<br>**Header** — `X-Agent-Service-Token`: 필수 |
| 출력 | `200` — `AgentRunResponse` |
| 조건 | `AGENT_SKILLS_ENABLED=true`일 때만 노출되며 workspace·user 소유권을 확인합니다. |
| 주요 오류 | `401` 토큰 불일치<br>`404` run 없음<br>`503` 토큰 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-agent-runs-run-id"></a>
### `GET /agent/runs/{run_id}` 상세

#### 1. Method + Path

`GET /agent/runs/{run_id}`

#### 2. 목적

계획 승인 화면에 필요한 plan version, operation hash, 작업 목록과 run 상태를 반환합니다.

#### 3. Auth 필요 여부

- 필요: `X-Agent-Service-Token`

#### 4. Request body

- Body 없음
- path: `run_id`
- query: `workspace_id`, `user_id`

#### 5. Response body

- HTTP `200`: 조회 성공

```json
{
  "id": "run_123",
  "workspace_id": "workspace_123",
  "action": "workspace_workflow",
  "skill_version_id": null,
  "status": "awaiting_approval",
  "request_summary": "현재 문서에 편집안을 반영해줘",
  "error_code": null,
  "plan": {
    "id": "plan_123",
    "version": 1,
    "summary": "승인된 편집안을 현재 문서에 반영합니다.",
    "operation_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "status": "awaiting_approval",
    "operations": [
      {
        "id": "operation_123",
        "sequence": 1,
        "tool_name": "apply_document_edit",
        "target_type": "document",
        "target_id": "doc_123",
        "base_version": 3,
        "source_parent_id": null,
        "destination_parent_id": null,
        "arguments": {},
        "reason": "사용자가 확인한 편집안을 반영합니다.",
        "depends_on": [],
        "status": "pending",
        "error_code": null
      }
    ]
  }
}
```

#### 6. Error response

- `401`: Agent 서비스 토큰 누락·불일치
- `404`: 요청 scope에 해당하는 run 없음
- `503`: Agent 서비스 토큰 미설정

#### 7. Pagination / filtering

- 페이지네이션 없음
- `workspace_id`, `user_id`로 소유권 scope 제한

#### 8. 권한 규칙

서버가 인증한 workspace·user와 run 소유권이 모두 일치해야 합니다.

#### 9. 예시 요청/응답

```bash
curl "$PIPELINE/agent/runs/run_123?workspace_id=workspace_123&user_id=user_123" \
  -H 'X-Agent-Service-Token: <value>'
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_agent_run_agent_runs__run_id__get`)

</details>

<a id="summary-post-agent-runs-run-id-approve"></a>
### `POST /agent/runs/{run_id}/approve`

| 항목 | 내용 |
|---|---|
| 목적 | 사용자가 확인한 현재 plan version과 operation hash를 검증해 실행을 승인합니다. |
| 입력 | **Path** — `run_id`<br>**Body** — workspace·user·plan version·operation hash |
| 출력 | `200` — `status=executing`인 `AgentRunResponse` |
| 조건 | run과 plan이 모두 `awaiting_approval`이고 승인값이 현재 계획과 정확히 일치해야 합니다. |
| 주요 오류 | `409` 계획 변경·상태 충돌<br>`422` 요청 검증 실패 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-agent-runs-run-id-approve"></a>
### `POST /agent/runs/{run_id}/approve` 상세

#### 1. Method + Path

`POST /agent/runs/{run_id}/approve`

#### 2. 목적

조회한 계획과 같은 version·hash에만 승인을 기록하고 execution job을 등록합니다.

#### 3. Auth 필요 여부

- 필요: `X-Agent-Service-Token`

#### 4. Request body

```json
{
  "workspace_id": "workspace_123",
  "user_id": "user_123",
  "plan_version": 1,
  "operation_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

#### 5. Response body

- HTTP `200`: 승인 성공
- `AgentRunResponse`; 승인 성공 직후 `status`는 `executing`입니다.

```json
{
  "id": "run_123",
  "workspace_id": "workspace_123",
  "action": "workspace_workflow",
  "skill_version_id": null,
  "status": "executing",
  "request_summary": "현재 문서에 편집안을 반영해줘",
  "error_code": null,
  "plan": null
}
```

#### 6. Error response

- `401` 토큰 불일치, `409` 현재 계획·상태 불일치, `422` 입력 검증 실패, `503` 토큰 미설정

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

workspace·user scope, plan version, operation hash를 모두 검증합니다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/agent/runs/run_123/approve" \
  -H 'X-Agent-Service-Token: <value>' -H 'Content-Type: application/json' \
  --data '{"workspace_id":"workspace_123","user_id":"user_123","plan_version":1,"operation_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: approve_agent_run_agent_runs__run_id__approve_post`)

</details>

<a id="summary-post-agent-runs-run-id-cancel"></a>
### `POST /agent/runs/{run_id}/cancel`

| 항목 | 내용 |
|---|---|
| 목적 | 아직 terminal 상태가 아닌 Agent run을 취소합니다. |
| 입력 | **Path** — `run_id`<br>**Body** — `workspace_id`, `user_id` |
| 출력 | `200` — `status=cancelled`인 `AgentRunResponse` |
| 조건 | completed·failed·partial_failed·conflicted·rejected·cancelled 상태는 취소할 수 없습니다. |
| 주요 오류 | `409` run 없음 또는 취소할 수 없는 상태 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-agent-runs-run-id-cancel"></a>
### `POST /agent/runs/{run_id}/cancel` 상세

#### 1. Method + Path

`POST /agent/runs/{run_id}/cancel`

#### 2. 목적

대기 job과 아직 시작하지 않은 operation을 함께 취소합니다.

#### 3. Auth 필요 여부

- 필요: `X-Agent-Service-Token`

#### 4. Request body

```json
{"workspace_id":"workspace_123","user_id":"user_123"}
```

#### 5. Response body

- HTTP `200`: 취소 성공
- `AgentRunResponse`; 성공 시 `status=cancelled`

```json
{
  "id": "run_123",
  "workspace_id": "workspace_123",
  "action": "workspace_workflow",
  "skill_version_id": null,
  "status": "cancelled",
  "request_summary": "현재 문서에 편집안을 반영해줘",
  "error_code": null,
  "plan": null
}
```

#### 6. Error response

- `401` 토큰 불일치, `409` 상태 충돌, `422` 입력 검증 실패, `503` 토큰 미설정

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

workspace·user와 run 소유권이 일치해야 합니다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/agent/runs/run_123/cancel" \
  -H 'X-Agent-Service-Token: <value>' -H 'Content-Type: application/json' \
  --data '{"workspace_id":"workspace_123","user_id":"user_123"}'
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: cancel_agent_run_agent_runs__run_id__cancel_post`)

</details>

<a id="summary-post-agent-runs-run-id-reject"></a>
### `POST /agent/runs/{run_id}/reject`

| 항목 | 내용 |
|---|---|
| 목적 | 승인 대기 중인 현재 계획을 거절합니다. |
| 입력 | **Path** — `run_id`<br>**Body** — `workspace_id`, `user_id` |
| 출력 | `200` — `status=rejected`인 `AgentRunResponse` |
| 조건 | run과 plan이 모두 `awaiting_approval`이어야 합니다. |
| 주요 오류 | `409` 승인 대기 상태가 아님 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-agent-runs-run-id-reject"></a>
### `POST /agent/runs/{run_id}/reject` 상세

#### 1. Method + Path

`POST /agent/runs/{run_id}/reject`

#### 2. 목적

현재 계획에 거절 결정을 기록하고 run을 terminal 상태로 종료합니다.

#### 3. Auth 필요 여부

- 필요: `X-Agent-Service-Token`

#### 4. Request body

```json
{"workspace_id":"workspace_123","user_id":"user_123"}
```

#### 5. Response body

- HTTP `200`: 거절 성공
- `AgentRunResponse`; 성공 시 `status=rejected`

```json
{
  "id": "run_123",
  "workspace_id": "workspace_123",
  "action": "workspace_workflow",
  "skill_version_id": null,
  "status": "rejected",
  "request_summary": "현재 문서에 편집안을 반영해줘",
  "error_code": null,
  "plan": null
}
```

#### 6. Error response

- `401` 토큰 불일치, `409` 상태 충돌, `422` 입력 검증 실패, `503` 토큰 미설정

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

workspace·user와 run 소유권이 일치해야 합니다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/agent/runs/run_123/reject" \
  -H 'X-Agent-Service-Token: <value>' -H 'Content-Type: application/json' \
  --data '{"workspace_id":"workspace_123","user_id":"user_123"}'
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: reject_agent_run_agent_runs__run_id__reject_post`)

</details>

<a id="summary-post-agent-runs-run-id-revise"></a>
### `POST /agent/runs/{run_id}/revise`

| 항목 | 내용 |
|---|---|
| 목적 | 기존 계획을 폐기하고 사용자 지시로 새 계획을 생성하도록 등록합니다. |
| 입력 | **Path** — `run_id`<br>**Body** — `workspace_id`, `user_id`, `instruction` |
| 출력 | `200` — `status=queued`인 `AgentRunResponse` |
| 조건 | `awaiting_approval` 또는 `clarification_required` 상태에서만 허용합니다. |
| 주요 오류 | `409` 수정할 수 없는 상태 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-agent-runs-run-id-revise"></a>
### `POST /agent/runs/{run_id}/revise` 상세

#### 1. Method + Path

`POST /agent/runs/{run_id}/revise`

#### 2. 목적

이전 계획을 superseded 처리하고 새 planning job을 등록합니다.

#### 3. Auth 필요 여부

- 필요: `X-Agent-Service-Token`

#### 4. Request body

```json
{
  "workspace_id": "workspace_123",
  "user_id": "user_123",
  "instruction": "문서를 저장하지 말고 미리보기만 다시 만들어줘"
}
```

#### 5. Response body

- HTTP `200`: 수정 요청 접수 성공
- `AgentRunResponse`; 성공 시 `status=queued`, `plan=null`

```json
{
  "id": "run_123",
  "workspace_id": "workspace_123",
  "action": "workspace_workflow",
  "skill_version_id": null,
  "status": "queued",
  "request_summary": "문서를 저장하지 말고 미리보기만 다시 만들어줘",
  "error_code": null,
  "plan": null
}
```

#### 6. Error response

- `401` 토큰 불일치, `409` 상태 충돌, `422` 입력 검증 실패, `503` 토큰 미설정

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

workspace·user와 run 소유권이 일치해야 합니다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/agent/runs/run_123/revise" \
  -H 'X-Agent-Service-Token: <value>' -H 'Content-Type: application/json' \
  --data '{"workspace_id":"workspace_123","user_id":"user_123","instruction":"문서를 저장하지 말고 미리보기만 다시 만들어줘"}'
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: revise_agent_run_agent_runs__run_id__revise_post`)

</details>

<a id="summary-post-internal-agent-runs-artifacts-list"></a>
### `POST /internal/agent/runs/artifacts/list`

| 항목 | 내용 |
|---|---|
| 목적 | Agent 실행에 등록된 artifact 목록을 조회합니다. |
| 입력 | **Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string` / `null`<br>**Body** — `AgentArtifactListRequest` |
| 출력 | `200` 성공 — 배열<`AgentArtifactResponse`> |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-internal-agent-runs-artifacts-list"></a>
### `POST /internal/agent/runs/artifacts/list` 상세

#### 1. Method + Path

`POST /internal/agent/runs/artifacts/list`

#### 2. 목적

Agent 실행에 등록된 artifact 목록을 조회합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`AgentArtifactListRequest`)

```json
{
  "run_id": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response List Agent Artifacts Internal Agent Runs Artifacts List Post`)

```json
[
  {
    "base_version": 1,
    "content_hash": "string",
    "document_id": "string",
    "id": "string",
    "purpose": "string",
    "target": {
    }
  }
]
```

#### 6. Error response

- HTTP `401`: 내부 인증 토큰 누락 또는 불일치
- HTTP `503`: 내부 인증 미설정

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/internal/agent/runs/artifacts/list" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"run_id":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
[
  {
    "base_version": 1,
    "content_hash": "string",
    "document_id": "string",
    "id": "string",
    "purpose": "string",
    "target": {
    }
  }
]
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: list_agent_artifacts_internal_agent_runs_artifacts_list_post`)

[↑ 요약으로 돌아가기](#summary-post-internal-agent-runs-artifacts-list)

</details>

<a id="summary-post-internal-agent-runs-artifacts-register"></a>
### `POST /internal/agent/runs/artifacts/register`

| 항목 | 내용 |
|---|---|
| 목적 | Agent 실행 결과 artifact를 등록합니다. |
| 입력 | **Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string` / `null`<br>**Body** — `AgentArtifactRegisterRequest` |
| 출력 | `200` 성공 — `AgentArtifactResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-internal-agent-runs-artifacts-register"></a>
### `POST /internal/agent/runs/artifacts/register` 상세

#### 1. Method + Path

`POST /internal/agent/runs/artifacts/register`

#### 2. 목적

Agent 실행 결과 artifact를 등록합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`AgentArtifactRegisterRequest`)

```json
{
  "artifact_id": "string",
  "base_version": 1.0,
  "content_hash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "document_id": "string",
  "markdown": "string",
  "purpose": "string",
  "run_id": "string",
  "target": {
  },
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`AgentArtifactResponse`)

```json
{
  "base_version": 1,
  "content_hash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "document_id": "string",
  "id": "string",
  "purpose": "string",
  "target": {
  }
}
```

#### 6. Error response

- HTTP `401`: 내부 인증 토큰 누락 또는 불일치
- HTTP `503`: 내부 인증 미설정

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/internal/agent/runs/artifacts/register" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"artifact_id":"<value>","base_version":1,"content_hash":"sha256:50d858e0985ecc7f60418aaf0cc5ab587f42c2570a884095a9e8ccacd0f6545c","document_id":"<value>","markdown":"example","purpose":"<value>","run_id":"<value>","target":{},"user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "base_version": 1,
  "content_hash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "document_id": "string",
  "id": "string",
  "purpose": "string",
  "target": {
  }
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: register_agent_artifact_internal_agent_runs_artifacts_register_post`)

[↑ 요약으로 돌아가기](#summary-post-internal-agent-runs-artifacts-register)

</details>

<a id="summary-post-internal-agent-runs-artifacts-resolve"></a>
### `POST /internal/agent/runs/artifacts/resolve`

| 항목 | 내용 |
|---|---|
| 목적 | Agent artifact의 저장 위치와 메타데이터를 확인합니다. |
| 입력 | **Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string` / `null`<br>**Body** — `AgentArtifactResolveRequest` |
| 출력 | `200` 성공 — `AgentArtifactResolveResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-internal-agent-runs-artifacts-resolve"></a>
### `POST /internal/agent/runs/artifacts/resolve` 상세

#### 1. Method + Path

`POST /internal/agent/runs/artifacts/resolve`

#### 2. 목적

Agent artifact의 저장 위치와 메타데이터를 확인합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`AgentArtifactResolveRequest`)

```json
{
  "artifact_id": "string",
  "base_version": 1.0,
  "content_hash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "document_id": "string",
  "purpose": "string",
  "run_id": "string",
  "target": {
  },
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`AgentArtifactResolveResponse`)

```json
{
  "base_version": 1,
  "content_hash": "string",
  "document_id": "string",
  "id": "string",
  "markdown": "string",
  "purpose": "string",
  "target": {
  }
}
```

#### 6. Error response

- HTTP `401`: 내부 인증 토큰 누락 또는 불일치
- HTTP `503`: 내부 인증 미설정

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/internal/agent/runs/artifacts/resolve" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"artifact_id":"<value>","base_version":1,"content_hash":"sha256:50d858e0985ecc7f60418aaf0cc5ab587f42c2570a884095a9e8ccacd0f6545c","document_id":"<value>","purpose":"<value>","run_id":"<value>","target":{},"user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "base_version": 1,
  "content_hash": "string",
  "document_id": "string",
  "id": "string",
  "markdown": "string",
  "purpose": "string",
  "target": {
  }
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: resolve_agent_artifact_internal_agent_runs_artifacts_resolve_post`)

[↑ 요약으로 돌아가기](#summary-post-internal-agent-runs-artifacts-resolve)

</details>

<a id="summary-post-internal-agent-runs-tool-authorizations-execute"></a>
### `POST /internal/agent/runs/tool-authorizations/execute`

| 항목 | 내용 |
|---|---|
| 목적 | Agent Tool 변경 작업의 실행 권한을 검증합니다. |
| 입력 | **Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string` / `null`<br>**Body** — `AgentToolExecuteAuthorizationRequest` |
| 출력 | `204` 성공 |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-internal-agent-runs-tool-authorizations-execute"></a>
### `POST /internal/agent/runs/tool-authorizations/execute` 상세

#### 1. Method + Path

`POST /internal/agent/runs/tool-authorizations/execute`

#### 2. 목적

Agent Tool 변경 작업의 실행 권한을 검증합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`AgentToolExecuteAuthorizationRequest`)

```json
{
  "arguments": {
  },
  "operation_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "operation_id": "string",
  "plan_id": "string",
  "plan_version": 1.0,
  "run_id": "string",
  "tool_name": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `204`: Successful Response
- Body: 없음

#### 6. Error response

- HTTP `401`: 내부 인증 토큰 누락 또는 불일치
- HTTP `503`: 내부 인증 미설정

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/internal/agent/runs/tool-authorizations/execute" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"arguments":{},"operation_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","operation_id":"<value>","plan_id":"<value>","plan_version":1,"run_id":"<value>","tool_name":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: authorize_agent_tool_execute_internal_agent_runs_tool_authorizations_execute_post`)

[↑ 요약으로 돌아가기](#summary-post-internal-agent-runs-tool-authorizations-execute)

</details>

<a id="summary-post-internal-agent-runs-tool-authorizations-read"></a>
### `POST /internal/agent/runs/tool-authorizations/read`

| 항목 | 내용 |
|---|---|
| 목적 | Agent Tool 읽기 작업의 실행 권한을 검증합니다. |
| 입력 | **Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string` / `null`<br>**Body** — `AgentToolReadAuthorizationRequest` |
| 출력 | `204` 성공 |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-internal-agent-runs-tool-authorizations-read"></a>
### `POST /internal/agent/runs/tool-authorizations/read` 상세

#### 1. Method + Path

`POST /internal/agent/runs/tool-authorizations/read`

#### 2. 목적

Agent Tool 읽기 작업의 실행 권한을 검증합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`AgentToolReadAuthorizationRequest`)

```json
{
  "run_id": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `204`: Successful Response
- Body: 없음

#### 6. Error response

- HTTP `401`: 내부 인증 토큰 누락 또는 불일치
- HTTP `503`: 내부 인증 미설정

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/internal/agent/runs/tool-authorizations/read" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"run_id":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: authorize_agent_tool_read_internal_agent_runs_tool_authorizations_read_post`)

[↑ 요약으로 돌아가기](#summary-post-internal-agent-runs-tool-authorizations-read)

</details>

<a id="summary-get-internal-agent-runs-run-id"></a>
### `GET /internal/agent/runs/{run_id}`

| 항목 | 내용 |
|---|---|
| 목적 | Markdown Agent 실행 상태와 결과를 조회합니다. |
| 입력 | **Path** — `run_id`: `string`<br>**Query** — `workspace_id`: `string`, `user_id`: `string`<br>**Header** — `X-Internal-Token`(필수, 인증 계층 검증): `string` / `null` |
| 출력 | `200` 성공 — `MarkdownAgentRunStatusResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>필터링: `workspace_id`, `user_id`<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-internal-agent-runs-run-id"></a>
### `GET /internal/agent/runs/{run_id}` 상세

#### 1. Method + Path

`GET /internal/agent/runs/{run_id}`

#### 2. 목적

Markdown Agent 실행 상태와 결과를 조회합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `run_id` | `string` | 예 | - |
| query | `workspace_id` | `string` | 예 | - |
| query | `user_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 예 (인증 계층 검증) | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`MarkdownAgentRunStatusResponse`)

```json
{
  "apply_operation_id": "string",
  "base_version": 1,
  "document_id": "string",
  "error_code": "string",
  "id": "string",
  "result": {
  },
  "status": "string"
}
```

#### 6. Error response

- HTTP `401`: 내부 인증 토큰 누락 또는 불일치
- HTTP `503`: 내부 인증 미설정

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `422` | Validation Error | `HTTPValidationError` |

```json
{
  "detail": [
    {
      "ctx": {
      },
      "input": {
      },
      "loc": [
        "string"
      ],
      "msg": "string",
      "type": "string"
    }
  ]
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: `workspace_id`, `user_id`

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/internal/agent/runs/<value>?workspace_id=<value>&user_id=<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
  "apply_operation_id": "string",
  "base_version": 1,
  "document_id": "string",
  "error_code": "string",
  "id": "string",
  "result": {
  },
  "status": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_markdown_agent_run_internal_agent_runs__run_id__get`)

[↑ 요약으로 돌아가기](#summary-get-internal-agent-runs-run-id)

</details>
