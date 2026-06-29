# Wiki Schema 계약

이 문서는 프론트엔드가 프로젝트별 LLM Wiki schema 설정 UI를 구현할 때 필요한 API 계약과 처리 흐름을 정리한다.

현재 구현 기준 endpoint는 `llmPipeline` FastAPI의 `/wiki-schema` 경로다. Spring backend가 proxy endpoint를 추가하면 프론트 호출 경로만 바뀌고 request/response body 계약은 유지한다.

## 1. 처리 플로우

```text
사용자 자연어 schema 입력
  -> POST /wiki-schema/preview
  -> LLM Schema Organizer
  -> Safety Filter
  -> section별 sanitized Markdown preview 반환
  -> 프론트가 preview/차단 항목 표시
  -> 사용자가 저장 선택
  -> POST /wiki-schema/drafts
  -> draft 저장
  -> 사용자가 활성화 선택
  -> POST /wiki-schema/{schema_id}/activate
  -> active schema 전환
  -> 이후 query/ingest/edit/concept/template 기능에 필요한 fragment만 prompt 주입
```

프론트 분기 기준:

| 상태 | 기준 | 프론트 처리 |
| --- | --- | --- |
| 정상 preview | `has_blocked_issues == false` | 적용될 Markdown fragment와 저장 버튼 표시 |
| 차단 항목 있음 | `has_blocked_issues == true` | 차단 항목을 강조하고 저장/활성화 전 사용자 확인 유도 |
| 확인 필요 항목 있음 | `issues[].severity == "unclear"` | 사용자가 schema 입력을 보완하도록 안내 |
| active schema 없음 | `GET /wiki-schema/active` 응답이 `null` | 기본 schema 없음 상태 표시 |

## 2. 핵심 원칙

- 사용자가 작성한 `raw_markdown`은 agent prompt에 직접 주입하지 않는다.
- 실제 prompt에 들어가는 값은 safety filter를 통과한 `fragments.*_markdown`이다.
- JSON은 API와 lint metadata 표현에만 사용한다.
- 실행 prompt 표현은 sanitized Markdown fragment다.
- section 의미 분류는 LLM organizer가 담당한다.
- 제품 코드는 section을 keyword rule로 강제 이동하지 않는다.
- 제품 코드는 injection, secret, 권한 상승, 정책 약화 차단에 집중한다.

## 3. Section 의미

`fragments`는 다음 section을 가진다.

| 필드 | 의미 | 주입 대상 |
| --- | --- | --- |
| `global_markdown` | 모든 기능에 공통 적용 가능한 언어, 문체, 용어, 작성 기준 | 모든 feature의 공통 후보 |
| `query_markdown` | 질문 답변 방식, 근거 제시, 불확실성 처리 | query |
| `ingest_markdown` | 문서 수집/분해, source element 처리 기준 | ingest |
| `edit_markdown` | Markdown 편집, 보존, 정리 기준 | edit, section polish |
| `concept_markdown` | concept 후보, 관계, graph/page 생성 기준 | concept, ingest |
| `template_markdown` | 문서 구조, section 순서, template 기준 | template |

기능별 prompt 주입 기준:

| feature | 사용 fragment |
| --- | --- |
| `query` | `global_markdown + query_markdown` |
| `ingest` | `global_markdown + ingest_markdown + concept_markdown` |
| `edit` | `global_markdown + edit_markdown` |
| `concept` | `global_markdown + concept_markdown` |
| `template` | `global_markdown + template_markdown` |

## 4. Preview API

사용자가 작성한 schema를 저장하지 않고 정리 결과만 미리 확인한다.

```http
POST /wiki-schema/preview
Content-Type: application/json
```

Request:

```json
{
  "raw_markdown": "한국어로 답하고 결론 먼저 말해줘. 모터 종류는 concept 후보로 봐줘."
}
```

Request 필드:

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `raw_markdown` | 예 | 사용자가 자연어로 작성한 schema 원문. 빈 문자열은 허용하지 않는다. |

Response:

```json
{
  "fragments": {
    "global_markdown": "- 한국어로 작성한다",
    "query_markdown": "- 결론을 먼저 짧게 제시한다",
    "ingest_markdown": "",
    "edit_markdown": "",
    "concept_markdown": "- 모터 종류는 문서 근거가 있을 때 concept 후보로 우선 검토한다.",
    "template_markdown": ""
  },
  "issues": [],
  "preview_markdown": "## 적용될 Schema\n\n### Global\n- 한국어로 작성한다",
  "has_blocked_issues": false
}
```

프론트 처리:

- `preview_markdown`은 사용자 확인용으로 그대로 렌더링할 수 있다.
- section별 편집 UI를 만들 경우 `fragments`를 source of truth로 사용한다.
- `has_blocked_issues`가 true이면 저장 버튼을 비활성화하거나 경고 confirm을 거친다.
- preview API는 DB에 저장하지 않는다.

## 5. Draft 생성 API

preview와 같은 organizer/filter를 실행한 뒤 draft schema로 저장한다.

```http
POST /wiki-schema/drafts
Content-Type: application/json
```

Request:

```json
{
  "project_id": "default",
  "name": "기본 schema",
  "raw_markdown": "한국어로 답하고 결론 먼저 말해줘."
}
```

Request 필드:

| 필드 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `raw_markdown` | 예 | 없음 | 사용자가 작성한 schema 원문 |
| `project_id` | 아니오 | `default` | schema를 적용할 프로젝트 id |
| `name` | 아니오 | `default` | 사용자가 구분할 schema 이름 |

Response:

```json
{
  "wiki_schema": {
    "id": "schema-id",
    "project_id": "default",
    "name": "기본 schema",
    "raw_markdown": "한국어로 답하고 결론 먼저 말해줘.",
    "fragments": {
      "global_markdown": "- 한국어로 작성한다",
      "query_markdown": "- 결론을 먼저 짧게 제시한다",
      "ingest_markdown": "",
      "edit_markdown": "",
      "concept_markdown": "",
      "template_markdown": ""
    },
    "issues": [],
    "preview_markdown": "## 적용될 Schema\n\n### Global\n- 한국어로 작성한다",
    "has_blocked_issues": false,
    "status": "draft",
    "schema_version": "1.0",
    "created_at": "2026-06-28T12:00:00+00:00",
    "updated_at": "2026-06-28T12:00:00+00:00",
    "activated_at": null
  }
}
```

프론트 처리:

- 저장 성공 후 draft 상세를 표시한다.
- 저장 직후 자동 active 전환은 하지 않는다.
- 사용자가 명시적으로 활성화를 선택해야 `activate`를 호출한다.

## 6. Activate API

저장된 draft schema를 active schema로 전환한다.

```http
POST /wiki-schema/{schema_id}/activate
```

Response:

```json
{
  "id": "schema-id",
  "project_id": "default",
  "name": "기본 schema",
  "raw_markdown": "한국어로 답하고 결론 먼저 말해줘.",
  "fragments": {
    "global_markdown": "- 한국어로 작성한다",
    "query_markdown": "- 결론을 먼저 짧게 제시한다",
    "ingest_markdown": "",
    "edit_markdown": "",
    "concept_markdown": "",
    "template_markdown": ""
  },
  "issues": [],
  "preview_markdown": "## 적용될 Schema\n\n### Global\n- 한국어로 작성한다",
  "has_blocked_issues": false,
  "status": "active",
  "schema_version": "1.0",
  "created_at": "2026-06-28T12:00:00+00:00",
  "updated_at": "2026-06-28T12:01:00+00:00",
  "activated_at": "2026-06-28T12:01:00+00:00"
}
```

프론트 처리:

- 활성화 성공 시 현재 project의 active schema 표시를 갱신한다.
- 같은 `project_id`의 기존 active schema는 서버에서 draft로 내려간다.
- 활성화 전 preview를 다시 보여주고 confirm을 받는 것이 좋다.

## 7. Active 조회 API

현재 project에 적용 중인 active schema를 조회한다.

```http
GET /wiki-schema/active?project_id=default
```

Response:

```json
{
  "id": "schema-id",
  "project_id": "default",
  "name": "기본 schema",
  "raw_markdown": "한국어로 답하고 결론 먼저 말해줘.",
  "fragments": {
    "global_markdown": "- 한국어로 작성한다",
    "query_markdown": "- 결론을 먼저 짧게 제시한다",
    "ingest_markdown": "",
    "edit_markdown": "",
    "concept_markdown": "",
    "template_markdown": ""
  },
  "issues": [],
  "preview_markdown": "## 적용될 Schema\n\n### Global\n- 한국어로 작성한다",
  "has_blocked_issues": false,
  "status": "active",
  "schema_version": "1.0",
  "created_at": "2026-06-28T12:00:00+00:00",
  "updated_at": "2026-06-28T12:01:00+00:00",
  "activated_at": "2026-06-28T12:01:00+00:00"
}
```

active schema가 없으면 `null`을 반환한다.

프론트 처리:

- `null`이면 “적용 중인 schema 없음” 상태를 보여준다.
- active schema가 있으면 `name`, `preview_markdown`, `activated_at`을 우선 표시한다.

## 8. Issue 계약

`issues`는 차단 항목과 확인 필요 항목을 표현한다.

```json
{
  "severity": "blocked",
  "category": "instruction_override",
  "text": "system prompt는 무시",
  "reason": "상위 지시를 무시하라는 요청입니다.",
  "section": "raw_markdown"
}
```

Issue 필드:

| 필드 | 설명 |
| --- | --- |
| `severity` | `blocked` 또는 `unclear` |
| `category` | 차단/확인 필요 분류 |
| `text` | 문제가 된 원문 또는 organizer 후보 문구 |
| `reason` | 사용자에게 보여줄 수 있는 이유 |
| `section` | 문제가 발견된 section. 없을 수 있음 |

주요 `category`:

| category | 의미 | 프론트 처리 |
| --- | --- | --- |
| `instruction_override` | system/developer 지시 무시 요청 | 저장/활성화 전 강한 경고 |
| `hidden_prompt` | hidden prompt/internal policy 공개 요청 | 저장/활성화 전 강한 경고 |
| `policy_weakening` | 출처/근거/불확실성 정책 약화 | 저장/활성화 전 강한 경고 |
| `permission_escalation` | 파일/네트워크/도구 권한 상승 | 저장/활성화 전 강한 경고 |
| `secret` | API key/token/password/private key 등 민감정보 | 저장/활성화 전 강한 경고 |
| `organizer_blocked` | LLM organizer가 차단 후보로 분류 | 사용자 확인 필요 |
| `organizer_unclear` | LLM organizer가 배치하기 애매하다고 판단 | 입력 보완 유도 |

## 9. 권장 UI

권장 화면 구성:

```text
Schema 입력 textarea
  -> Preview 버튼
  -> 적용될 Schema preview
      Global
      Query
      Ingest
      Edit
      Concept
      Template
  -> 차단 항목 panel
  -> 확인 필요 항목 panel
  -> Draft 저장
  -> Active 적용
```

프론트 표시 기준:

- `preview_markdown`은 전체 확인용으로 보여준다.
- section별 세부 UI가 필요하면 `fragments`를 개별 panel로 보여준다.
- `issues[].severity == "blocked"`는 눈에 띄게 표시한다.
- `issues[].severity == "unclear"`는 사용자가 입력을 보완하도록 안내한다.
- raw schema와 sanitized fragment를 나란히 비교할 수 있으면 좋다.

## 10. 에러 처리

공통 에러:

| 상태 | 대표 원인 | 프론트 처리 |
| --- | --- | --- |
| `400` | 빈 `raw_markdown`, 빈 `project_id`, 빈 `name` | 입력값 확인 메시지 |
| `404` | 존재하지 않는 `schema_id` activate | schema가 삭제되었거나 만료된 상태 안내 |
| `500` | organizer LLM 호출 실패, DB 오류 | 일시적 실패 메시지와 재시도 버튼 |

LLM organizer는 로컬 Ollama 또는 OpenAI-compatible endpoint를 사용한다. preview/draft 생성은 LLM 호출이 포함되므로 일반 REST 조회보다 느릴 수 있다.

## 11. 현재 제한과 후속 작업

현재 제한:

- draft 목록 조회 API는 아직 없다.
- draft 수정 API는 아직 없다.
- section별 수동 수정 API는 아직 없다.
- 특정 section만 재생성하는 API는 아직 없다.
- Spring backend proxy 경로는 아직 정의되지 않았다.

후속 작업 후보:

- `GET /wiki-schema/drafts?project_id=...`
- `PATCH /wiki-schema/{schema_id}`
- `POST /wiki-schema/preview/sections/{section}/regenerate`
- preview 화면에서 section별 직접 수정
- active schema가 실제 기능 prompt에 어떻게 들어가는지 debug preview 제공
