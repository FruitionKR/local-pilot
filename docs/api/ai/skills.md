# Skills API

[API 문서](../README.md) / [ai-svc](README.md)

Skill 조회·작성·게시·설정 내부 API다.

- API 수: 8

## API 목차

| API | 목적 |
|---|---|
| [`GET /skills`](#summary-get-skills) | 사용 가능한 Skill 목록을 조회합니다. |
| [`POST /skills/author`](#summary-post-skills-author) | 사용자 요청과 참조 문서로 Skill 초안을 작성합니다. |
| [`POST /skills/author/publish`](#summary-post-skills-author-publish) | 검토한 Skill 초안을 게시합니다. |
| [`POST /skills/preview`](#summary-post-skills-preview) | Skill 지침과 권한을 게시 전에 미리 검증합니다. |
| [`GET /skills/{skill_id}`](#summary-get-skills-skill-id) | Skill 상세 정보를 조회합니다. |
| [`PATCH /skills/{skill_id}`](#summary-patch-skills-skill-id) | Skill 지침과 실행 설정을 변경합니다. |
| [`POST /skills/{skill_id}/disable`](#summary-post-skills-skill-id-disable) | Skill을 비활성화합니다. |
| [`POST /skills/{skill_id}/enable`](#summary-post-skills-skill-id-enable) | Skill을 활성화합니다. |

## 한눈에 보기

<a id="summary-get-skills"></a>
### `GET /skills`

| 항목 | 내용 |
|---|---|
| 목적 | 사용 가능한 Skill 목록을 조회합니다. |
| 입력 | **Query** — `workspace_id`: `string`, `user_id`: `string`<br>**Header** — `X-Agent-Service-Token`(필수, 인증 계층 검증): `string` / `null` |
| 출력 | `200` 성공 — 배열<`SkillResponse`> |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>필터링: `workspace_id`, `user_id`<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-skills"></a>
### `GET /skills` 상세

#### 1. Method + Path

`GET /skills`

#### 2. 목적

사용 가능한 Skill 목록을 조회합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| query | `workspace_id` | `string` | 예 | - |
| query | `user_id` | `string` | 예 | - |
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 예 (인증 계층 검증) | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`Response List Skills Skills Get`)

```json
[
  {
    "enabled_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "string",
      "status": "string",
      "version": 0
    },
    "id": "string",
    "latest_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "string",
      "status": "string",
      "version": 0
    },
    "owner_user_id": "string",
    "scope_type": "string",
    "slug": "string",
    "status": "string",
    "workspace_id": "string"
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
- 필터링: `workspace_id`, `user_id`

#### 8. 권한 규칙

- 올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.
- 요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$PIPELINE/skills?workspace_id=<value>&user_id=<value>" \
  -H 'X-Agent-Service-Token: <value>'
```

```json
[
  {
    "enabled_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "string",
      "status": "string",
      "version": 0
    },
    "id": "string",
    "latest_version": {
      "allowed_tools": [
        "string"
      ],
      "capabilities": [
        "string"
      ],
      "description": "string",
      "id": "string",
      "instructions_markdown": "string",
      "lint_result": {
      },
      "name": "string",
      "status": "string",
      "version": 0
    },
    "owner_user_id": "string",
    "scope_type": "string",
    "slug": "string",
    "status": "string",
    "workspace_id": "string"
  }
]
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: list_skills_skills_get`)

</details>

<a id="summary-post-skills-author"></a>
### `POST /skills/author`

| 항목 | 내용 |
|---|---|
| 목적 | 사용자 요청과 참조 문서로 Skill 초안을 작성합니다. |
| 입력 | **Header** — `X-Agent-Service-Token`(필수, 인증 계층 검증): `string` / `null`<br>**Body** — `SkillAuthoringRequest` |
| 출력 | `200` 성공 — `SkillAuthoringResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-skills-author"></a>
### `POST /skills/author` 상세

#### 1. Method + Path

`POST /skills/author`

#### 2. 목적

사용자 요청과 참조 문서로 Skill 초안을 작성합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`SkillAuthoringRequest`)

```json
{
  "authoring_mode": "preserve",
  "description": "string",
  "instruction": "string",
  "model": "string",
  "name": "example",
  "provider": "string",
  "reference_document_ids": [
    "string"
  ],
  "scope_type": "personal",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillAuthoringResponse`)

```json
{
  "allowed_tools": [
    "list_root_items"
  ],
  "capabilities": [
    "document-create"
  ],
  "description": "string",
  "instructions_markdown": "string",
  "issues": [
    {
    }
  ],
  "name": "string",
  "question": "string",
  "scope_type": "personal",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "clarification_required",
  "version_id": "string"
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
curl -X POST "$PIPELINE/skills/author" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"authoring_mode":"preserve","description":"<value>","instruction":"<value>","model":"<value>","name":"<value>","provider":"<value>","reference_document_ids":["<value>"],"scope_type":"personal","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "allowed_tools": [
    "list_root_items"
  ],
  "capabilities": [
    "document-create"
  ],
  "description": "string",
  "instructions_markdown": "string",
  "issues": [
    {
    }
  ],
  "name": "string",
  "question": "string",
  "scope_type": "personal",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "clarification_required",
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: author_skill_skills_author_post`)

</details>

<a id="summary-post-skills-author-publish"></a>
### `POST /skills/author/publish`

| 항목 | 내용 |
|---|---|
| 목적 | 검토한 Skill 초안을 게시합니다. |
| 입력 | **Header** — `X-Agent-Service-Token`(필수, 인증 계층 검증): `string` / `null`<br>**Body** — `PublishAuthoredSkillRequest` |
| 출력 | `200` 성공 — `SkillAuthoringResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-skills-author-publish"></a>
### `POST /skills/author/publish` 상세

#### 1. Method + Path

`POST /skills/author/publish`

#### 2. 목적

검토한 Skill 초안을 게시합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`PublishAuthoredSkillRequest`)

```json
{
  "allowed_tools": [
    "list_root_items"
  ],
  "capabilities": [
    "document-create"
  ],
  "description": "string",
  "instructions_markdown": "string",
  "model": "string",
  "name": "example",
  "provider": "string",
  "scope_type": "personal",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillAuthoringResponse`)

```json
{
  "allowed_tools": [
    "list_root_items"
  ],
  "capabilities": [
    "document-create"
  ],
  "description": "string",
  "instructions_markdown": "string",
  "issues": [
    {
    }
  ],
  "name": "string",
  "question": "string",
  "scope_type": "personal",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "clarification_required",
  "version_id": "string"
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
curl -X POST "$PIPELINE/skills/author/publish" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"allowed_tools":["list_root_items"],"capabilities":["document-create"],"description":"<value>","instructions_markdown":"<value>","model":"<value>","name":"<value>","provider":"<value>","scope_type":"personal","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "allowed_tools": [
    "list_root_items"
  ],
  "capabilities": [
    "document-create"
  ],
  "description": "string",
  "instructions_markdown": "string",
  "issues": [
    {
    }
  ],
  "name": "string",
  "question": "string",
  "scope_type": "personal",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "clarification_required",
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: publish_authored_skill_skills_author_publish_post`)

</details>

<a id="summary-post-skills-preview"></a>
### `POST /skills/preview`

| 항목 | 내용 |
|---|---|
| 목적 | Skill 지침과 권한을 게시 전에 미리 검증합니다. |
| 입력 | **Header** — `X-Agent-Service-Token`(필수, 인증 계층 검증): `string` / `null`<br>**Body** — `SkillDefinitionRequest` |
| 출력 | `200` 성공 — `SkillPreviewResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-skills-preview"></a>
### `POST /skills/preview` 상세

#### 1. Method + Path

`POST /skills/preview`

#### 2. 목적

Skill 지침과 권한을 게시 전에 미리 검증합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`SkillDefinitionRequest`)

```json
{
  "allowed_tools": [
    "list_root_items"
  ],
  "capabilities": [
    "document-create"
  ],
  "description": "string",
  "instructions_markdown": "string",
  "name": "example",
  "user_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillPreviewResponse`)

```json
{
  "has_blocked_issues": true,
  "lint_result": {
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
curl -X POST "$PIPELINE/skills/preview" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"allowed_tools":["list_root_items"],"capabilities":["document-create"],"description":"<value>","instructions_markdown":"<value>","name":"<value>","user_id":"<value>"}'
```

```json
{
  "has_blocked_issues": true,
  "lint_result": {
  }
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: preview_skill_skills_preview_post`)

</details>

<a id="summary-get-skills-skill-id"></a>
### `GET /skills/{skill_id}`

| 항목 | 내용 |
|---|---|
| 목적 | Skill 상세 정보를 조회합니다. |
| 입력 | **Path** — `skill_id`: `string`<br>**Query** — `workspace_id`: `string`, `user_id`: `string`<br>**Header** — `X-Agent-Service-Token`(필수, 인증 계층 검증): `string` / `null` |
| 출력 | `200` 성공 — `SkillResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>필터링: `workspace_id`, `user_id`<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-skills-skill-id"></a>
### `GET /skills/{skill_id}` 상세

#### 1. Method + Path

`GET /skills/{skill_id}`

#### 2. 목적

Skill 상세 정보를 조회합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `skill_id` | `string` | 예 | - |
| query | `workspace_id` | `string` | 예 | - |
| query | `user_id` | `string` | 예 | - |
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 예 (인증 계층 검증) | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillResponse`)

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "owner_user_id": "string",
  "scope_type": "string",
  "slug": "string",
  "status": "string",
  "workspace_id": "string"
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
curl -X GET "$PIPELINE/skills/<value>?workspace_id=<value>&user_id=<value>" \
  -H 'X-Agent-Service-Token: <value>'
```

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "owner_user_id": "string",
  "scope_type": "string",
  "slug": "string",
  "status": "string",
  "workspace_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: get_skill_skills__skill_id__get`)

</details>

<a id="summary-patch-skills-skill-id"></a>
### `PATCH /skills/{skill_id}`

| 항목 | 내용 |
|---|---|
| 목적 | Skill 지침과 실행 설정을 변경합니다. |
| 입력 | **Path** — `skill_id`: `string`<br>**Header** — `X-Agent-Service-Token`(필수, 인증 계층 검증): `string` / `null`<br>**Body** — `UpdateSkillRequest` |
| 출력 | `200` 성공 — `SkillAuthoringResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-patch-skills-skill-id"></a>
### `PATCH /skills/{skill_id}` 상세

#### 1. Method + Path

`PATCH /skills/{skill_id}`

#### 2. 목적

Skill 지침과 실행 설정을 변경합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `skill_id` | `string` | 예 | - |
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`UpdateSkillRequest`)

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "model": "string",
  "name": "example",
  "provider": "string",
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillAuthoringResponse`)

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": [
    {
    }
  ],
  "name": "string",
  "question": "string",
  "scope_type": "personal",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "clarification_required",
  "version_id": "string"
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
curl -X PATCH "$PIPELINE/skills/<value>" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"description":"<value>","instructions_markdown":"<value>","model":"<value>","name":"<value>","provider":"<value>","user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "description": "string",
  "instructions_markdown": "string",
  "issues": [
    {
    }
  ],
  "name": "string",
  "question": "string",
  "scope_type": "personal",
  "skill_id": "string",
  "skill_markdown": "string",
  "status": "clarification_required",
  "version_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: update_skill_skills__skill_id__patch`)

</details>

<a id="summary-post-skills-skill-id-disable"></a>
### `POST /skills/{skill_id}/disable`

| 항목 | 내용 |
|---|---|
| 목적 | Skill을 비활성화합니다. |
| 입력 | **Path** — `skill_id`: `string`<br>**Header** — `X-Agent-Service-Token`(필수, 인증 계층 검증): `string` / `null`<br>**Body** — `SkillActorRequest` |
| 출력 | `200` 성공 — `SkillResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-skills-skill-id-disable"></a>
### `POST /skills/{skill_id}/disable` 상세

#### 1. Method + Path

`POST /skills/{skill_id}/disable`

#### 2. 목적

Skill을 비활성화합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `skill_id` | `string` | 예 | - |
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`SkillActorRequest`)

```json
{
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillResponse`)

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "owner_user_id": "string",
  "scope_type": "string",
  "slug": "string",
  "status": "string",
  "workspace_id": "string"
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
curl -X POST "$PIPELINE/skills/<value>/disable" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "owner_user_id": "string",
  "scope_type": "string",
  "slug": "string",
  "status": "string",
  "workspace_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: disable_skill_skills__skill_id__disable_post`)

</details>

<a id="summary-post-skills-skill-id-enable"></a>
### `POST /skills/{skill_id}/enable`

| 항목 | 내용 |
|---|---|
| 목적 | Skill을 활성화합니다. |
| 입력 | **Path** — `skill_id`: `string`<br>**Header** — `X-Agent-Service-Token`(필수, 인증 계층 검증): `string` / `null`<br>**Body** — `SkillActorRequest` |
| 출력 | `200` 성공 — `SkillResponse` |
| 조건 | 인증 필요<br>서비스 간 내부 인증 토큰을 검증한다.<br>올바른 내부 서비스 토큰을 가진 서비스만 호출할 수 있다.<br>요청에 포함된 workspace/user scope는 해당 route의 서비스 계층에서 추가 검증한다. |
| 주요 오류 | `422` 요청 검증 실패 — `HTTPValidationError`<br>`401` 내부 인증 토큰 누락 또는 불일치<br>`503` 내부 인증 미설정 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-skills-skill-id-enable"></a>
### `POST /skills/{skill_id}/enable` 상세

#### 1. Method + Path

`POST /skills/{skill_id}/enable`

#### 2. 목적

Skill을 활성화합니다.

#### 3. Auth 필요 여부

- 필요
- 서비스 간 내부 인증 토큰을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `skill_id` | `string` | 예 | - |
| header | `X-Agent-Service-Token` | `X-Agent-Service-Token` | 예 (인증 계층 검증) | - |

- Content-Type: `application/json` (`SkillActorRequest`)

```json
{
  "user_id": "string",
  "workspace_id": "string"
}
```

#### 5. Response body

- HTTP `200`: Successful Response
- Content-Type: `application/json` (`SkillResponse`)

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "owner_user_id": "string",
  "scope_type": "string",
  "slug": "string",
  "status": "string",
  "workspace_id": "string"
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
curl -X POST "$PIPELINE/skills/<value>/enable" \
  -H 'X-Agent-Service-Token: <value>' \
  -H 'Content-Type: application/json' \
  --data '{"user_id":"<value>","workspace_id":"<value>"}'
```

```json
{
  "enabled_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "id": "string",
  "latest_version": {
    "allowed_tools": [
      "string"
    ],
    "capabilities": [
      "string"
    ],
    "description": "string",
    "id": "string",
    "instructions_markdown": "string",
    "lint_result": {
    },
    "name": "string",
    "status": "string",
    "version": 1
  },
  "owner_user_id": "string",
  "scope_type": "string",
  "slug": "string",
  "status": "string",
  "workspace_id": "string"
}
```

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: enable_skill_skills__skill_id__enable_post`)

</details>
