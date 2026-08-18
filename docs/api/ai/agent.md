# Agent API

[API 문서](../README.md) / [ai-svc](README.md)

Agent turn과 run·artifact·Tool 인가 내부 API다.

- API 수: 7

## API 목차

| API | 목적 |
|---|---|
| [`POST /agent/turn`](#summary-post-agent-turn) | Handle Agent Turn |
| [`POST /internal/agent/runs/artifacts/list`](#summary-post-internal-agent-runs-artifacts-list) | List Agent Artifacts |
| [`POST /internal/agent/runs/artifacts/register`](#summary-post-internal-agent-runs-artifacts-register) | Register Agent Artifact |
| [`POST /internal/agent/runs/artifacts/resolve`](#summary-post-internal-agent-runs-artifacts-resolve) | Resolve Agent Artifact |
| [`POST /internal/agent/runs/tool-authorizations/execute`](#summary-post-internal-agent-runs-tool-authorizations-execute) | Authorize Agent Tool Execute |
| [`POST /internal/agent/runs/tool-authorizations/read`](#summary-post-internal-agent-runs-tool-authorizations-read) | Authorize Agent Tool Read |
| [`GET /internal/agent/runs/{run_id}`](#summary-get-internal-agent-runs-run-id) | Get Markdown Agent Run |

## 한눈에 보기

<a id="summary-post-agent-turn"></a>
### `POST /agent/turn`

| 항목 | 내용 |
|---|---|
| 목적 | Handle Agent Turn |
| 입력 | **Header** — `X-Internal-Token`(선택): `string` / `null`<br>**Body** — `AgentTurnRequestBody` |
| 출력 | `200` Successful Response — `AgentTurnResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-agent-turn)

<a id="summary-post-internal-agent-runs-artifacts-list"></a>
### `POST /internal/agent/runs/artifacts/list`

| 항목 | 내용 |
|---|---|
| 목적 | List Agent Artifacts |
| 입력 | **Header** — `X-Internal-Token`(선택): `string` / `null`<br>**Body** — `AgentArtifactListRequest` |
| 출력 | `200` Successful Response — 배열<`AgentArtifactResponse`> |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-internal-agent-runs-artifacts-list)

<a id="summary-post-internal-agent-runs-artifacts-register"></a>
### `POST /internal/agent/runs/artifacts/register`

| 항목 | 내용 |
|---|---|
| 목적 | Register Agent Artifact |
| 입력 | **Header** — `X-Internal-Token`(선택): `string` / `null`<br>**Body** — `AgentArtifactRegisterRequest` |
| 출력 | `200` Successful Response — `AgentArtifactResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-internal-agent-runs-artifacts-register)

<a id="summary-post-internal-agent-runs-artifacts-resolve"></a>
### `POST /internal/agent/runs/artifacts/resolve`

| 항목 | 내용 |
|---|---|
| 목적 | Resolve Agent Artifact |
| 입력 | **Header** — `X-Internal-Token`(선택): `string` / `null`<br>**Body** — `AgentArtifactResolveRequest` |
| 출력 | `200` Successful Response — `AgentArtifactResolveResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-internal-agent-runs-artifacts-resolve)

<a id="summary-post-internal-agent-runs-tool-authorizations-execute"></a>
### `POST /internal/agent/runs/tool-authorizations/execute`

| 항목 | 내용 |
|---|---|
| 목적 | Authorize Agent Tool Execute |
| 입력 | **Header** — `X-Internal-Token`(선택): `string` / `null`<br>**Body** — `AgentToolExecuteAuthorizationRequest` |
| 출력 | `204` Successful Response |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-internal-agent-runs-tool-authorizations-execute)

<a id="summary-post-internal-agent-runs-tool-authorizations-read"></a>
### `POST /internal/agent/runs/tool-authorizations/read`

| 항목 | 내용 |
|---|---|
| 목적 | Authorize Agent Tool Read |
| 입력 | **Header** — `X-Internal-Token`(선택): `string` / `null`<br>**Body** — `AgentToolReadAuthorizationRequest` |
| 출력 | `204` Successful Response |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-internal-agent-runs-tool-authorizations-read)

<a id="summary-get-internal-agent-runs-run-id"></a>
### `GET /internal/agent/runs/{run_id}`

| 항목 | 내용 |
|---|---|
| 목적 | Get Markdown Agent Run |
| 입력 | **Path** — `run_id`: `string`<br>**Query** — `workspace_id`: `string`, `user_id`: `string`<br>**Header** — `X-Internal-Token`(선택): `string` / `null` |
| 출력 | `200` Successful Response — `MarkdownAgentRunStatusResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>필터링: `workspace_id`, `user_id`<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-get-internal-agent-runs-run-id)

## 상세 계약

<a id="detail-post-agent-turn"></a>
### `POST /agent/turn` 상세

#### 1. Method + Path

`POST /agent/turn`

#### 2. 목적

Handle Agent Turn

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`AgentTurnRequestBody`)

```json
{
  "active_markdown_context": {
    "markdown": "string",
    "target": null
  },
  "allow_web_search": true,
  "base_version": 3,
  "conversation_context": {
    "pending_skill_proposal": null,
    "recent_conversation_summary": null,
    "recent_messages": [
      {
        "action": "conversation_reply",
        "content": "string",
        "role": "string"
      }
    ],
    "reference_context": null
  },
  "document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "message": "string",
  "model": "string",
  "output_language": "ko",
  "provider": "string",
  "response_length": "concise",
  "skill_authoring_mode": "preserve",
  "skill_draft_excluded_literals": [
    "string"
  ]
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
복합 요청은 `retrieval_source`(`none|workspace|web`),
`document_operation`(`none|create|edit`), `persist`로 분해한 뒤 전체 조합을 대표하는 action을
선택한다. 서버는 이 의미를 문장 패턴으로 덮어쓰지 않고, action과 필드 조합이 모순될 때만
LLM에 한 번 재요청한다. 두 번째 응답도 계약을 만족하지 못하면 HTTP 422로 종료한다.
응답 action은 내부 문서 근거 조회인 `chat_answer`, 대화 맥락만으로 작성·형식을 이어가는
`conversation_reply`, 열린 Markdown을 변경하는 `markdown_edit`를 구분한다.
내부 문서 근거 조회와 새 문서 저장 또는 열린 문서 편집을 함께 요청하면 Query 파이프라인이 먼저
평가한 답변과 evidence snippet을 Markdown 생성·편집 입력에 포함한다. 허용된 웹 검색은 새 문서
생성 요청에서 같은 흐름을 사용하며 `allow_web_search=true`일 때만 실행한다. 생성 결과는
`create_document`, 편집 결과는 `apply_document_edit` 아티팩트로 `workspace_workflow` 승인 계획에
전달한다.

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`AgentTurnResponse`)

```json
{
  "action": "chat_answer",
  "chat": {
    "answer": "string",
    "error_code": null,
    "evidence_snippets": [
      {
        "rank": 0,
        "source_block_ids": [
          "string"
        ],
        "source_document_id": "string",
        "source_refs": [
          {
            "source_block_id": "string",
            "source_document_id": "string"
          }
        ],
        "text": "string"
      }
    ],
    "graph_context": {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "string",
          "role": "string",
          "score": 0.0,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        {
          "depth": 0,
          "id": "string",
          "page_type": "string",
          "relevance_score": 0.0,
          "role": "string",
          "slug": "string",
          "title": "string"
        }
      ]
    },
    "related_pages": [
      {
        "depth": 0,
        "id": "string",
        "page_type": "string",
        "relevance_score": 0.0,
        "role": "string",
        "slug": "string",
        "title": "string"
      }
    ],
    "result_count": 1,
    "traversal_paths": [
      {
        "edges": [
          {
            "from_page_id": "string",
            "link_type": "string",
            "role": "string",
            "score": 0.0,
            "to_page_id": "string"
          }
        ],
        "nodes": [
          "string"
        ],
        "path_id": "string",
        "role": "string",
        "score": 0.0,
        "stop_reason": "string",
        "used_for_answer": false
      }
    ],
    "updated_conversation_summary": null,
    "web_search_executed": true,
    "web_search_requested": true
  },
  "edit": {
    "actual_target": {
      "end_line": 0,
      "start_line": 0,
      "type": "string"
    },
    "changed": true,
    "operation": "replace",
    "replacement_markdown": "string",
    "requested_target": {
      "end_line": 0,
      "start_line": 0,
      "type": "string"
    },
    "scope_expanded": true,
    "summary": "string"
  },
  "generated_markdown": {
    "markdown": "string",
    "summary": "string",
    "title": "string"
  },
  "message": "string",
  "route": {
    "action": "chat_answer",
    "confidence": 1,
    "document_operation": "none",
    "edit_goal": "string",
    "persist": false,
    "reason": "string",
    "retrieval_source": "workspace",
    "selected_skill_id": "string",
    "skill_candidates": [
      "string"
    ]
  },
  "run_id": "string",
  "run_status": "string",
  "skill_authoring": {
    "allowed_tools": [],
    "capabilities": [],
    "description": null,
    "instructions_markdown": null,
    "issues": [
      {
      }
    ],
    "name": null,
    "question": null,
    "scope_type": null,
    "skill_id": null,
    "skill_markdown": null,
    "status": "clarification_required",
    "version_id": null
  },
  "skill_candidates": [
    {
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "name": "string",
      "version_id": "string"
    }
  ]
}
```

#### 6. Error response

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
curl -X POST "$PIPELINE/agent/turn" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"active_markdown_context":{"markdown":"<value>","target":null},"allow_web_search":true,"base_version":3,"conversation_context":{"pending_skill_proposal":null,"recent_conversation_summary":null,"recent_messages":[null],"reference_context":null},"document_id":"doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83","message":"<value>","model":"<value>","output_language":"ko","provider":"<value>","response_length":"concise","skill_authoring_mode":"preserve","skill_draft_excluded_literals":["<value>"]}'
```

```json
{
  "action": "chat_answer",
  "chat": {
    "answer": "string",
    "error_code": null,
    "evidence_snippets": [
      {
        "rank": 0,
        "source_block_ids": [
          "string"
        ],
        "source_document_id": "string",
        "source_refs": [
          {
            "source_block_id": "string",
            "source_document_id": "string"
          }
        ],
        "text": "string"
      }
    ],
    "graph_context": {
      "edges": [
        {
          "from_page_id": "string",
          "link_type": "string",
          "role": "string",
          "score": 0.0,
          "to_page_id": "string"
        }
      ],
      "nodes": [
        {
          "depth": 0,
          "id": "string",
          "page_type": "string",
          "relevance_score": 0.0,
          "role": "string",
          "slug": "string",
          "title": "string"
        }
      ]
    },
    "related_pages": [
      {
        "depth": 0,
        "id": "string",
        "page_type": "string",
        "relevance_score": 0.0,
        "role": "string",
        "slug": "string",
        "title": "string"
      }
    ],
    "result_count": 1,
    "traversal_paths": [
      {
        "edges": [
          {
            "from_page_id": "string",
            "link_type": "string",
            "role": "string",
            "score": 0.0,
            "to_page_id": "string"
          }
        ],
        "nodes": [
          "string"
        ],
        "path_id": "string",
        "role": "string",
        "score": 0.0,
        "stop_reason": "string",
        "used_for_answer": false
      }
    ],
    "updated_conversation_summary": null,
    "web_search_executed": true,
    "web_search_requested": true
  },
  "edit": {
    "actual_target": {
      "end_line": 0,
      "start_line": 0,
      "type": "string"
    },
    "changed": true,
    "operation": "replace",
    "replacement_markdown": "string",
    "requested_target": {
      "end_line": 0,
      "start_line": 0,
      "type": "string"
    },
    "scope_expanded": true,
    "summary": "string"
  },
  "generated_markdown": {
    "markdown": "string",
    "summary": "string",
    "title": "string"
  },
  "message": "string",
  "route": {
    "action": "chat_answer",
    "confidence": 1,
    "document_operation": "none",
    "edit_goal": "string",
    "persist": false,
    "reason": "string",
    "retrieval_source": "workspace",
    "selected_skill_id": "string",
    "skill_candidates": [
      "string"
    ]
  },
  "run_id": "string",
  "run_status": "string",
  "skill_authoring": {
    "allowed_tools": [],
    "capabilities": [],
    "description": null,
    "instructions_markdown": null,
    "issues": [
      {
      }
    ],
    "name": null,
    "question": null,
    "scope_type": null,
    "skill_id": null,
    "skill_markdown": null,
    "status": "clarification_required",
    "version_id": null
  },
  "skill_candidates": [
    {
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "name": "string",
      "version_id": "string"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: handle_agent_turn_agent_turn_post`)

<a id="detail-post-internal-agent-runs-artifacts-list"></a>
### `POST /internal/agent/runs/artifacts/list` 상세

#### 1. Method + Path

`POST /internal/agent/runs/artifacts/list`

#### 2. 목적

List Agent Artifacts

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

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

<a id="detail-post-internal-agent-runs-artifacts-register"></a>
### `POST /internal/agent/runs/artifacts/register` 상세

#### 1. Method + Path

`POST /internal/agent/runs/artifacts/register`

#### 2. 목적

Register Agent Artifact

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`AgentArtifactRegisterRequest`)

```json
{
  "artifact_id": "string",
  "base_version": 1.0,
  "content_hash": "string",
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
  "content_hash": "string",
  "document_id": "string",
  "id": "string",
  "purpose": "string",
  "target": {
  }
}
```

#### 6. Error response

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
  --data '{"artifact_id":"<value>","base_version":1.0,"content_hash":"<value>","document_id":"<value>","markdown":"<value>","purpose":"<value>","run_id":"<value>","target":{},"user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "base_version": 1,
  "content_hash": "string",
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

<a id="detail-post-internal-agent-runs-artifacts-resolve"></a>
### `POST /internal/agent/runs/artifacts/resolve` 상세

#### 1. Method + Path

`POST /internal/agent/runs/artifacts/resolve`

#### 2. 목적

Resolve Agent Artifact

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`AgentArtifactResolveRequest`)

```json
{
  "artifact_id": "string",
  "base_version": 1.0,
  "content_hash": "string",
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
  --data '{"artifact_id":"<value>","base_version":1.0,"content_hash":"<value>","document_id":"<value>","purpose":"<value>","run_id":"<value>","target":{},"user_id":"<value>","workspace_id":"<value>"}'
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

<a id="detail-post-internal-agent-runs-tool-authorizations-execute"></a>
### `POST /internal/agent/runs/tool-authorizations/execute` 상세

#### 1. Method + Path

`POST /internal/agent/runs/tool-authorizations/execute`

#### 2. 목적

Authorize Agent Tool Execute

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`AgentToolExecuteAuthorizationRequest`)

```json
{
  "arguments": {
  },
  "operation_hash": "string",
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
  --data '{"arguments":{},"operation_hash":"<value>","operation_id":"<value>","plan_id":"<value>","plan_version":1.0,"run_id":"<value>","tool_name":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/agent_run/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: authorize_agent_tool_execute_internal_agent_runs_tool_authorizations_execute_post`)

<a id="detail-post-internal-agent-runs-tool-authorizations-read"></a>
### `POST /internal/agent/runs/tool-authorizations/read` 상세

#### 1. Method + Path

`POST /internal/agent/runs/tool-authorizations/read`

#### 2. 목적

Authorize Agent Tool Read

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

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

<a id="detail-get-internal-agent-runs-run-id"></a>
### `GET /internal/agent/runs/{run_id}` 상세

#### 1. Method + Path

`GET /internal/agent/runs/{run_id}`

#### 2. 목적

Get Markdown Agent Run

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `run_id` | `string` | 예 | - |
| query | `workspace_id` | `string` | 예 | - |
| query | `user_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

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
