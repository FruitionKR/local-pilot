# Wiki Schema API

[API 문서](../README.md) / [ai-svc](README.md)

Wiki 스키마 관리 내부 API다.

- API 수: 4

## API 목차

| API | 목적 |
|---|---|
| [`GET /wiki-schema/active`](#summary-get-wiki-schema-active) | Get Active Wiki Schema |
| [`POST /wiki-schema/drafts`](#summary-post-wiki-schema-drafts) | Create Wiki Schema Draft |
| [`POST /wiki-schema/preview`](#summary-post-wiki-schema-preview) | Preview Wiki Schema |
| [`POST /wiki-schema/{schema_id}/activate`](#summary-post-wiki-schema-schema-id-activate) | Activate Wiki Schema |

## 한눈에 보기

<a id="summary-get-wiki-schema-active"></a>
### `GET /wiki-schema/active`

| 항목 | 내용 |
|---|---|
| 목적 | Get Active Wiki Schema |
| 입력 | **Query** — `workspace_id`: `string`, `user_id`: `string`<br>**Header** — `X-Internal-Token`(선택): `string` / `null` |
| 출력 | `200` Successful Response — `WikiSchemaResponse` / `null` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>필터링: `workspace_id`, `user_id`<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-get-wiki-schema-active)

<a id="summary-post-wiki-schema-drafts"></a>
### `POST /wiki-schema/drafts`

| 항목 | 내용 |
|---|---|
| 목적 | Create Wiki Schema Draft |
| 입력 | **Header** — `X-Internal-Token`(선택): `string` / `null`<br>**Body** — `CreateWikiSchemaDraftRequest` |
| 출력 | `200` Successful Response — `CreateWikiSchemaDraftResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-wiki-schema-drafts)

<a id="summary-post-wiki-schema-preview"></a>
### `POST /wiki-schema/preview`

| 항목 | 내용 |
|---|---|
| 목적 | Preview Wiki Schema |
| 입력 | **Header** — `X-Internal-Token`(선택): `string` / `null`<br>**Body** — `WikiSchemaPreviewRequest` |
| 출력 | `200` Successful Response — `WikiSchemaPreviewResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-wiki-schema-preview)

<a id="summary-post-wiki-schema-schema-id-activate"></a>
### `POST /wiki-schema/{schema_id}/activate`

| 항목 | 내용 |
|---|---|
| 목적 | Activate Wiki Schema |
| 입력 | **Path** — `schema_id`: `string`<br>**Header** — `X-Internal-Token`(선택): `string` / `null` |
| 출력 | `200` Successful Response — `WikiSchemaResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-wiki-schema-schema-id-activate)

## 상세 계약

<a id="detail-get-wiki-schema-active"></a>
### `GET /wiki-schema/active` 상세

#### 1. Method + Path

`GET /wiki-schema/active`

#### 2. 목적

Get Active Wiki Schema

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| query | `workspace_id` | `string` | 예 | - |
| query | `user_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Get Active Wiki Schema Wiki Schema Active Get`)

```json
{
  "activated_at": "string",
  "created_at": "string",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": true,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "string",
      "text": "string"
    }
  ],
  "name": "string",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "string"
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
curl -X GET "$PIPELINE/wiki-schema/active?workspace_id=<value>&user_id=<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
  "activated_at": "string",
  "created_at": "string",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": true,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": "string",
      "severity": "string",
      "text": "string"
    }
  ],
  "name": "string",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_schema/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_active_wiki_schema_wiki_schema_active_get`)

<a id="detail-post-wiki-schema-drafts"></a>
### `POST /wiki-schema/drafts` 상세

#### 1. Method + Path

`POST /wiki-schema/drafts`

#### 2. 목적

Create Wiki Schema Draft

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`CreateWikiSchemaDraftRequest`)

```json
{
  "name": "default",
  "raw_markdown": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`CreateWikiSchemaDraftResponse`)

```json
{
  "wiki_schema": {
    "activated_at": "string",
    "created_at": "string",
    "fragments": {
      "concept_markdown": "string",
      "edit_markdown": "string",
      "global_markdown": "string",
      "ingest_markdown": "string",
      "query_markdown": "string",
      "template_markdown": "string"
    },
    "has_blocked_issues": true,
    "id": "string",
    "issues": [
      {
        "category": "string",
        "reason": "string",
        "section": "string",
        "severity": "string",
        "text": "string"
      }
    ],
    "name": "string",
    "preview_markdown": "string",
    "raw_markdown": "string",
    "schema_version": "string"
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
curl -X POST "$PIPELINE/wiki-schema/drafts" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"name":"default","raw_markdown":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "wiki_schema": {
    "activated_at": "string",
    "created_at": "string",
    "fragments": {
      "concept_markdown": "string",
      "edit_markdown": "string",
      "global_markdown": "string",
      "ingest_markdown": "string",
      "query_markdown": "string",
      "template_markdown": "string"
    },
    "has_blocked_issues": true,
    "id": "string",
    "issues": [
      {
        "category": "string",
        "reason": "string",
        "section": "string",
        "severity": "string",
        "text": "string"
      }
    ],
    "name": "string",
    "preview_markdown": "string",
    "raw_markdown": "string",
    "schema_version": "string"
  }
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_schema/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: create_wiki_schema_draft_wiki_schema_drafts_post`)

<a id="detail-post-wiki-schema-preview"></a>
### `POST /wiki-schema/preview` 상세

#### 1. Method + Path

`POST /wiki-schema/preview`

#### 2. 목적

Preview Wiki Schema

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`WikiSchemaPreviewRequest`)

```json
{
  "raw_markdown": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`WikiSchemaPreviewResponse`)

```json
{
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": true,
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": null,
      "severity": "blocked",
      "text": "string"
    }
  ],
  "preview_markdown": "string"
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
curl -X POST "$PIPELINE/wiki-schema/preview" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"raw_markdown":"<value>"}'
```

```json
{
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": true,
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": null,
      "severity": "blocked",
      "text": "string"
    }
  ],
  "preview_markdown": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_schema/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: preview_wiki_schema_wiki_schema_preview_post`)

<a id="detail-post-wiki-schema-schema-id-activate"></a>
### `POST /wiki-schema/{schema_id}/activate` 상세

#### 1. Method + Path

`POST /wiki-schema/{schema_id}/activate`

#### 2. 목적

Activate Wiki Schema

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `schema_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`WikiSchemaResponse`)

```json
{
  "activated_at": "string",
  "created_at": "string",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": true,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": null,
      "severity": "blocked",
      "text": "string"
    }
  ],
  "name": "string",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "string"
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
curl -X POST "$PIPELINE/wiki-schema/<value>/activate" \
  -H 'X-Internal-Token: <value>'
```

```json
{
  "activated_at": "string",
  "created_at": "string",
  "fragments": {
    "concept_markdown": "string",
    "edit_markdown": "string",
    "global_markdown": "string",
    "ingest_markdown": "string",
    "query_markdown": "string",
    "template_markdown": "string"
  },
  "has_blocked_issues": true,
  "id": "string",
  "issues": [
    {
      "category": "string",
      "reason": "string",
      "section": null,
      "severity": "blocked",
      "text": "string"
    }
  ],
  "name": "string",
  "preview_markdown": "string",
  "raw_markdown": "string",
  "schema_version": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_schema/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: activate_wiki_schema_wiki_schema__schema_id__activate_post`)
