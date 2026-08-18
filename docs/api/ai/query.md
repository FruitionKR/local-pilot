# Query API

[API 문서](../README.md) / [ai-svc](README.md)

Wiki 기반 질의 실행 내부 API다.

- API 수: 1

## API 목차

| API | 목적 |
|---|---|
| [`POST /query`](#summary-post-query) | Answer Query |

## 한눈에 보기

<a id="summary-post-query"></a>
### `POST /query`

| 항목 | 내용 |
|---|---|
| 목적 | Answer Query |
| 입력 | **Header** — `X-Internal-Token`(선택): `string` / `null`<br>**Body** — `QueryRequest` |
| 출력 | `200` Successful Response — `QueryResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-query)

## 상세 계약

<a id="detail-post-query"></a>
### `POST /query` 상세

#### 1. Method + Path

`POST /query`

#### 2. 목적

Answer Query

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`QueryRequest`)

```json
{
  "allow_web_search": true,
  "model": "string",
  "output_language": "ko",
  "provider": "string",
  "question": "string",
  "recent_conversation_summary": "string",
  "recent_messages": [
    {
      "content": "string",
      "role": "user"
    }
  ],
  "reference_context": {
  },
  "response_length": "concise",
  "user_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`QueryResponse`)

```json
{
  "answer": "string",
  "error_code": "string",
  "evidence_snippets": [
    {
      "rank": 1,
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
      "depth": 1,
      "id": "string",
      "page_type": "string",
      "relevance_score": 1,
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
      "score": 1,
      "stop_reason": "string",
      "used_for_answer": true
    }
  ],
  "updated_conversation_summary": "string",
  "web_search_executed": true,
  "web_search_requested": true
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
curl -X POST "$PIPELINE/query" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"allow_web_search":true,"model":"<value>","output_language":"ko","provider":"<value>","question":"<value>","recent_conversation_summary":"<value>","recent_messages":[{"content":"<value>","role":"user"}],"reference_context":{},"response_length":"concise","user_id":"<value>"}'
```

```json
{
  "answer": "string",
  "error_code": "string",
  "evidence_snippets": [
    {
      "rank": 1,
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
      "depth": 1,
      "id": "string",
      "page_type": "string",
      "relevance_score": 1,
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
      "score": 1,
      "stop_reason": "string",
      "used_for_answer": true
    }
  ],
  "updated_conversation_summary": "string",
  "web_search_executed": true,
  "web_search_requested": true
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/query/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: answer_query_query_post`)
