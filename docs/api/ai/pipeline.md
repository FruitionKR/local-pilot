# Pipeline API

[API 문서](../README.md) / [ai-svc](README.md)

pipeline 실행·상태·로그와 상태 점검 내부 API다.

- API 수: 7

## API 목차

| API | 목적 |
|---|---|
| [`POST /chat-wiki/runs`](#summary-post-chat-wiki-runs) | Run Chat Wiki Endpoint |
| [`POST /pipeline/reingest-runs`](#summary-post-pipeline-reingest-runs) | Run Reingest Pipeline Endpoint |
| [`POST /pipeline/runs`](#summary-post-pipeline-runs) | Run Pipeline Endpoint |
| [`GET /pipeline/runs/{run_id}`](#summary-get-pipeline-runs-run-id) | Get Pipeline Run |
| [`GET /pipeline/runs/{run_id}/logs`](#summary-get-pipeline-runs-run-id-logs) | Get Pipeline Logs |
| [`GET /documents/{document_id}`](#summary-get-documents-document-id) | Get Document |
| [`GET /health`](#summary-get-health) | Health |

## 한눈에 보기

<a id="summary-post-chat-wiki-runs"></a>
### `POST /chat-wiki/runs`

| 항목 | 내용 |
|---|---|
| 목적 | Run Chat Wiki Endpoint |
| 입력 | **Header** — `X-Internal-Token`(선택): `string` / `null`<br>**Body** — `ChatWikiRunIn` |
| 출력 | `200` Successful Response — `PipelineRunOut` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-chat-wiki-runs)

<a id="summary-post-pipeline-reingest-runs"></a>
### `POST /pipeline/reingest-runs`

| 항목 | 내용 |
|---|---|
| 목적 | Run Reingest Pipeline Endpoint |
| 입력 | **Header** — `X-Internal-Token`(선택): `string` / `null`<br>**Body** — `ReingestRunIn` |
| 출력 | `200` Successful Response — `PipelineRunOut` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-pipeline-reingest-runs)

<a id="summary-post-pipeline-runs"></a>
### `POST /pipeline/runs`

| 항목 | 내용 |
|---|---|
| 목적 | Run Pipeline Endpoint |
| 입력 | **Header** — `X-Internal-Token`(선택): `string` / `null`<br>**Body** — `PipelineRunIn` |
| 출력 | `200` Successful Response — `PipelineRunOut` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-post-pipeline-runs)

<a id="summary-get-pipeline-runs-run-id"></a>
### `GET /pipeline/runs/{run_id}`

| 항목 | 내용 |
|---|---|
| 목적 | Get Pipeline Run |
| 입력 | **Path** — `run_id`: `string`<br>**Header** — `X-Internal-Token`(선택): `string` / `null` |
| 출력 | `200` Successful Response — `object` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-get-pipeline-runs-run-id)

<a id="summary-get-pipeline-runs-run-id-logs"></a>
### `GET /pipeline/runs/{run_id}/logs`

| 항목 | 내용 |
|---|---|
| 목적 | Get Pipeline Logs |
| 입력 | **Path** — `run_id`: `string`<br>**Header** — `X-Internal-Token`(선택): `string` / `null` |
| 출력 | `200` Successful Response — `string` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-get-pipeline-runs-run-id-logs)

<a id="summary-get-documents-document-id"></a>
### `GET /documents/{document_id}`

| 항목 | 내용 |
|---|---|
| 목적 | Get Document |
| 입력 | **Path** — `document_id`: `string`<br>**Header** — `X-Internal-Token`(선택): `string` / `null` |
| 출력 | `200` Successful Response — `object` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` Validation Error — `HTTPValidationError` |

[상세 계약](#detail-get-documents-document-id)

<a id="summary-get-health"></a>
### `GET /health`

| 항목 | 내용 |
|---|---|
| 목적 | Health |
| 입력 | 없음 |
| 출력 | `200` Successful Response — `object` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | 공통 오류 계약 적용 |

[상세 계약](#detail-get-health)

## 상세 계약

<a id="detail-post-chat-wiki-runs"></a>
### `POST /chat-wiki/runs` 상세

#### 1. Method + Path

`POST /chat-wiki/runs`

#### 2. 목적

Run Chat Wiki Endpoint

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`ChatWikiRunIn`)

```json
{
  "chat_append_system_prompt": "prompts/chat_semantic_append.system.md",
  "chat_system_prompt": "prompts/chat_semantic_extraction.system.md",
  "concept_page_mode": "auto",
  "concept_resolution_system_prompt": "prompts/concept_resolution.system.md",
  "concept_system_prompt": "prompts/concept_page_generation.system.md",
  "document_id": "string",
  "existing_wiki_dir": "string",
  "input_markdown": "string",
  "input_name": "string",
  "log_callback_url": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`PipelineRunOut`)

```json
{
  "log_path": "string",
  "manifest": {
  },
  "output_dir": "string",
  "run_id": "string",
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
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/chat-wiki/runs" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"chat_append_system_prompt":"prompts/chat_semantic_append.system.md","chat_system_prompt":"prompts/chat_semantic_extraction.system.md","concept_page_mode":"auto","concept_resolution_system_prompt":"prompts/concept_resolution.system.md","concept_system_prompt":"prompts/concept_page_generation.system.md","document_id":"<value>","existing_wiki_dir":"<value>","input_markdown":"<value>","input_name":"<value>","log_callback_url":"<value>"}'
```

```json
{
  "log_path": "string",
  "manifest": {
  },
  "output_dir": "string",
  "run_id": "string",
  "status": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: run_chat_wiki_endpoint_chat_wiki_runs_post`)

<a id="detail-post-pipeline-reingest-runs"></a>
### `POST /pipeline/reingest-runs` 상세

#### 1. Method + Path

`POST /pipeline/reingest-runs`

#### 2. 목적

Run Reingest Pipeline Endpoint

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`ReingestRunIn`)

```json
{
  "concept_page_mode": "auto",
  "concept_resolution_system_prompt": "prompts/concept_resolution.system.md",
  "concept_system_prompt": "prompts/concept_page_generation.system.md",
  "document_id": "string",
  "existing_wiki_dir": "string",
  "input_markdown": "string",
  "input_name": "string",
  "log_callback_url": "string",
  "max_eval_attempts": 1,
  "max_packet_chars": 1
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`PipelineRunOut`)

```json
{
  "log_path": "string",
  "manifest": {
  },
  "output_dir": "string",
  "run_id": "string",
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
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/pipeline/reingest-runs" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"concept_page_mode":"auto","concept_resolution_system_prompt":"prompts/concept_resolution.system.md","concept_system_prompt":"prompts/concept_page_generation.system.md","document_id":"<value>","existing_wiki_dir":"<value>","input_markdown":"<value>","input_name":"<value>","log_callback_url":"<value>","max_eval_attempts":1,"max_packet_chars":1}'
```

```json
{
  "log_path": "string",
  "manifest": {
  },
  "output_dir": "string",
  "run_id": "string",
  "status": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: run_reingest_pipeline_endpoint_pipeline_reingest_runs_post`)

<a id="detail-post-pipeline-runs"></a>
### `POST /pipeline/runs` 상세

#### 1. Method + Path

`POST /pipeline/runs`

#### 2. 목적

Run Pipeline Endpoint

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Content-Type: `application/json` (`PipelineRunIn`)

```json
{
  "concept_page_mode": "auto",
  "concept_resolution_system_prompt": "prompts/concept_resolution.system.md",
  "concept_system_prompt": "prompts/concept_page_generation.system.md",
  "document_id": "string",
  "existing_wiki_dir": "string",
  "input_name": "string",
  "log_callback_url": "string",
  "max_eval_attempts": 1,
  "max_packet_chars": 1,
  "mode": "api"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`PipelineRunOut`)

```json
{
  "log_path": "string",
  "manifest": {
  },
  "output_dir": "string",
  "run_id": "string",
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
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$PIPELINE/pipeline/runs" \
  -H 'X-Internal-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"concept_page_mode":"auto","concept_resolution_system_prompt":"prompts/concept_resolution.system.md","concept_system_prompt":"prompts/concept_page_generation.system.md","document_id":"<value>","existing_wiki_dir":"<value>","input_name":"<value>","log_callback_url":"<value>","max_eval_attempts":1,"max_packet_chars":1,"mode":"api"}'
```

```json
{
  "log_path": "string",
  "manifest": {
  },
  "output_dir": "string",
  "run_id": "string",
  "status": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: run_pipeline_endpoint_pipeline_runs_post`)

<a id="detail-get-pipeline-runs-run-id"></a>
### `GET /pipeline/runs/{run_id}` 상세

#### 1. Method + Path

`GET /pipeline/runs/{run_id}`

#### 2. 목적

Get Pipeline Run

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `run_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Get Pipeline Run Pipeline Runs  Run Id  Get`)

```json
{
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
curl -X GET "$PIPELINE/pipeline/runs/<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_pipeline_run_pipeline_runs__run_id__get`)

<a id="detail-get-pipeline-runs-run-id-logs"></a>
### `GET /pipeline/runs/{run_id}/logs` 상세

#### 1. Method + Path

`GET /pipeline/runs/{run_id}/logs`

#### 2. 목적

Get Pipeline Logs

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `run_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `text/plain`

```json
string
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
curl -X GET "$PIPELINE/pipeline/runs/<value>/logs" \
  -H 'X-Internal-Token: <value>'
```

```json
string
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_pipeline_logs_pipeline_runs__run_id__logs_get`)

<a id="detail-get-documents-document-id"></a>
### `GET /documents/{document_id}` 상세

#### 1. Method + Path

`GET /documents/{document_id}`

#### 2. 목적

Get Document

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `document_id` | `string` | 예 | - |
| header | `X-Internal-Token` | `X-Internal-Token` | 아니요 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Get Document Documents  Document Id  Get`)

```json
{
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
curl -X GET "$PIPELINE/documents/<value>" \
  -H 'X-Internal-Token: <value>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_document_documents__document_id__get`)

<a id="detail-get-health"></a>
### `GET /health` 상세

#### 1. Method + Path

`GET /health`

#### 2. 목적

Health

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- 없음

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response Health Health Get`)

```json
{
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/health"
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: health_health_get`)
