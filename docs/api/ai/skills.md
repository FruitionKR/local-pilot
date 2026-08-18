# Skills API

[API 문서](../README.md) / [ai-svc](README.md)

Skill 조회·작성·게시·설정 내부 API다. 공개 Gateway 계약은
[`document-svc Skills API`](../document/skills.md)다. Backend가 사용자·워크스페이스·모델 정보를
검증해 추가한 뒤 7개 관리 API를 내부 HTTP로 호출한다. draft-from-runs·preview는 ai-svc 내부 기능이다.

- API 수: 9

## API 목차

| API | 목적 |
|---|---|
| [`GET /skills`](#summary-get-skills) | 사용 가능한 Skill 목록을 조회합니다. |
| [`POST /skills/author`](#summary-post-skills-author) | 사용자 요청과 참조 문서로 Skill 초안을 작성합니다. |
| [`POST /skills/author/publish`](#summary-post-skills-author-publish) | 검토한 Skill 초안을 게시합니다. |
| [`POST /skills/draft-from-runs/preview`](#summary-post-skills-draft-from-runs-preview) | 완료된 Agent 실행 결과로 게시 전 Skill 초안을 만듭니다. |
| [`POST /skills/preview`](#summary-post-skills-preview) | Skill 지침과 권한을 게시 전에 미리 검증합니다. |
| [`GET /skills/{skill_id}`](#summary-get-skills-skill-id) | Skill 상세 정보를 조회합니다. |
| [`PATCH /skills/{skill_id}`](#summary-patch-skills-skill-id) | Skill 지침과 실행 설정을 변경합니다. |
| [`POST /skills/{skill_id}/disable`](#summary-post-skills-skill-id-disable) | Skill을 비활성화합니다. |
| [`POST /skills/{skill_id}/enable`](#summary-post-skills-skill-id-enable) | Skill을 활성화합니다. |

실행 결과 기반 초안 API는 `AGENT_SKILLS_ENABLED=true`, 나머지 8개 API는
`SKILL_API_ENABLED=true`일 때 노출된다. 모두 `X-Agent-Service-Token`으로 보호한다.

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

[↑ 요약으로 돌아가기](#summary-get-skills)

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

[↑ 요약으로 돌아가기](#summary-post-skills-author)

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

[↑ 요약으로 돌아가기](#summary-post-skills-author-publish)

</details>

<a id="summary-post-skills-draft-from-runs-preview"></a>
### `POST /skills/draft-from-runs/preview`

| 항목 | 내용 |
|---|---|
| 목적 | 완료된 Agent 실행의 성공 작업을 일반화해 게시 전 Skill 초안을 만듭니다. |
| 입력 | **Header** — `X-Agent-Service-Token`: 필수<br>**Body** — `SkillDraftProposalRequest` |
| 출력 | `200` — `SkillAuthoringResponse` |
| 조건 | `AGENT_SKILLS_ENABLED=true`일 때만 노출됩니다. source run은 completed 상태이며 성공 operation이 하나 이상이어야 합니다. |
| 주요 오류 | `400` source·지침·보안 검증 실패<br>`422` 요청 검증 실패 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-skills-draft-from-runs-preview"></a>
### `POST /skills/draft-from-runs/preview` 상세

#### 1. Method + Path

`POST /skills/draft-from-runs/preview`

#### 2. 목적

Backend가 소유권과 완료 상태를 검증해 전달한 Agent run 요약에서 재사용 가능한 절차만 추출합니다.
결과는 바로 게시하지 않고 기존 Skill 보안 검사를 거친 `proposal_ready`, `blocked` 또는
`clarification_required` 응답으로 반환합니다.

#### 3. Auth 필요 여부

- 필요: `X-Agent-Service-Token`

#### 4. Request body

```json
{
  "provider": "openai",
  "model": "gpt-5-nano",
  "workspace_id": "workspace_123",
  "user_id": "user_123",
  "scope_type": "personal",
  "source_runs": [
    {
      "run_id": "run_123",
      "status": "completed",
      "request_summary": "주간 회의록 문서를 만들고 업무 폴더로 이동",
      "plan_summary": "회의록 생성 후 지정 폴더로 이동",
      "successful_operations": [
        {
          "tool_name": "create_document",
          "reason": "회의록 문서를 생성했습니다."
        },
        {
          "tool_name": "move_document",
          "reason": "업무 폴더로 이동했습니다."
        }
      ]
    }
  ],
  "user_directives": ["회의록 형식을 유지해줘"],
  "excluded_literals": ["2026년 8월 18일"]
}
```

#### 5. Response body

```json
{
  "status": "proposal_ready",
  "question": null,
  "skill_id": null,
  "version_id": null,
  "scope_type": "personal",
  "name": "weekly-meeting-notes",
  "description": "주간 회의록을 만들고 지정 폴더에 정리합니다.",
  "skill_markdown": "---\nname: weekly-meeting-notes\n---\n",
  "instructions_markdown": "회의 내용을 회의록 형식으로 작성하고 지정 폴더에 정리합니다.",
  "capabilities": ["document-create", "folder-organize"],
  "allowed_tools": ["create_document", "move_document"],
  "issues": []
}
```

#### 6. Error response

- `400`: source run 의미, 사용자 지침 또는 초안 검증 실패
- `401`: Agent 서비스 토큰 누락·불일치
- `422`: 요청 schema 검증 실패
- `503`: Agent 서비스 토큰 미설정

#### 7. Pagination / filtering

- 페이지네이션과 필터링을 지원하지 않음

#### 8. 권한 규칙

클라이언트가 임의로 만든 run 결과를 직접 신뢰하지 않습니다. Backend가 workspace·user 소유권과
완료 상태를 확인한 canonical run 결과만 이 내부 API에 전달해야 합니다.

#### 9. 예시 요청/응답

위 request·response 예시와 같습니다. 실제 게시에는 별도로 `POST /skills/author/publish` 승인이 필요합니다.

#### 10. 구현 파일

- 진입점: `services/ai/pipeline/app/modules/skill/interfaces/http/routes.py`
- 기계 판독 계약: `api-specs/pipeline/openapi.yaml` (`operationId: propose_skill_draft_skills_draft_from_runs_preview_post`)

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

[↑ 요약으로 돌아가기](#summary-post-skills-preview)

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

[↑ 요약으로 돌아가기](#summary-get-skills-skill-id)

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

[↑ 요약으로 돌아가기](#summary-patch-skills-skill-id)

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

[↑ 요약으로 돌아가기](#summary-post-skills-skill-id-disable)

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

[↑ 요약으로 돌아가기](#summary-post-skills-skill-id-enable)

</details>
