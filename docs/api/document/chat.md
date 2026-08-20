# Chat API

[API 문서](../README.md) / [document-svc](README.md)

채팅 세션·메시지와 Wiki 내보내기 Gateway API다. Wiki 내보내기는 Backend가 채팅을 검증·직렬화해
문서로 저장한 뒤 Kafka `ai.ingest.command`로 전달하며, 클라이언트는 ai-svc DTO를 보내지 않는다.

- API 수: 6

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/workspaces/{workspace_id}/chat/sessions`](#summary-get-api-workspaces-workspace-id-chat-sessions) | 가장 최근 메시지 순으로 정렬해 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/chat/sessions`](#summary-post-api-workspaces-workspace-id-chat-sessions) | 워크스페이스당 최대 10개까지 생성할 수 있습니다. |
| [`DELETE /api/workspaces/{workspace_id}/chat/sessions/{session_id}`](#summary-delete-api-workspaces-workspace-id-chat-sessions-session-id) | 워크스페이스에서 지정한 채팅 세션과 해당 세션의 메시지 기록을 삭제합니다. |
| [`GET /api/workspaces/{workspace_id}/chat/sessions/{session_id}/messages`](#summary-get-api-workspaces-workspace-id-chat-sessions-session-id-messages) | 세션 내 채팅 메시지를 생성 순서대로 반환합니다. |
| [`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki`](#summary-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki) | 세션(full) 또는 선택 문답(partial)을 Markdown 원문 문서로 먼저 저장한 뒤 일반 문서 Ingest를 요청합니다. Wiki 생성은 파이프라인이 비동기로 수행합니다. |
| [`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki/preview`](#summary-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki-preview) | 세션을 llmPipeline 입력용 Markdown으로 직렬화해 결과만 반환합니다. 저장/파이프라인 호출은 하지 않습니다. |

## 한눈에 보기

<a id="summary-get-api-workspaces-workspace-id-chat-sessions"></a>
### `GET /api/workspaces/{workspace_id}/chat/sessions`

| 항목 | 내용 |
|---|---|
| 목적 | 가장 최근 메시지 순으로 정렬해 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string` |
| 출력 | `200` 조회 성공 — `ChatSessionListResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-chat-sessions"></a>
### `GET /api/workspaces/{workspace_id}/chat/sessions` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/chat/sessions`

#### 2. 목적

가장 최근 메시지 순으로 정렬해 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`ChatSessionListResponse`)

```json
{
  "sessions": [
    {
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "last_message_at": "2026-08-13T04:25:24.371948Z",
      "title": "검색 인덱싱 질문"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "sessions": [
    {
      "created_at": "2026-08-13T04:25:24.371948Z",
      "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
      "last_message_at": "2026-08-13T04:25:24.371948Z",
      "title": "검색 인덱싱 질문"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: list_1`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-chat-sessions)

</details>

<a id="summary-post-api-workspaces-workspace-id-chat-sessions"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스당 최대 10개까지 생성할 수 있습니다. |
| 입력 | **Path** — `workspace_id`: `string`<br>**Body** — `ChatSessionCreateRequest` |
| 출력 | `201` 생성 성공 — `ChatSessionResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 워크스페이스를 찾을 수 없음 — `ErrorResponse`<br>`409` 세션 개수 제한 초과 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-chat-sessions"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions`

#### 2. 목적

워크스페이스당 최대 10개까지 생성할 수 있습니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |

- Content-Type: `application/json` (`ChatSessionCreateRequest`)

```json
{
  "title": "검색 인덱싱 질문"
}
```

`title`은 선택이다. 비우거나 생략하면 서버가 `새 채팅`으로 채운다. 세션 제목은 이 세션을 위키화한 문서의
이름이 되므로 비워 두지 않는다 — 비면 문서 이름에 세션 ID가 새어 나간다.

#### 5. Response body

- HTTP `201`: 생성 성공
- Content-Type: `*/*` (`ChatSessionResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "last_message_at": "2026-08-13T04:25:24.371948Z",
  "title": "검색 인덱싱 질문"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 워크스페이스를 찾을 수 없음 | `ErrorResponse` |
| `409` | 세션 개수 제한 초과 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"title":"검색 인덱싱 질문"}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "id": "session_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "last_message_at": "2026-08-13T04:25:24.371948Z",
  "title": "검색 인덱싱 질문"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: create_1`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-chat-sessions)

</details>

<a id="summary-delete-api-workspaces-workspace-id-chat-sessions-session-id"></a>
### `DELETE /api/workspaces/{workspace_id}/chat/sessions/{session_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 워크스페이스에서 지정한 채팅 세션과 해당 세션의 메시지 기록을 삭제합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `session_id`: `string` |
| 출력 | `204` 삭제 성공 |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 세션 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-delete-api-workspaces-workspace-id-chat-sessions-session-id"></a>
### `DELETE /api/workspaces/{workspace_id}/chat/sessions/{session_id}` 상세

#### 1. Method + Path

`DELETE /api/workspaces/{workspace_id}/chat/sessions/{session_id}`

#### 2. 목적

워크스페이스에서 지정한 채팅 세션과 해당 세션의 메시지 기록을 삭제합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Body: 없음

#### 5. Response body

- HTTP `204`: 삭제 성공
- Body: 없음

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 세션 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X DELETE "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: delete_2`)

[↑ 요약으로 돌아가기](#summary-delete-api-workspaces-workspace-id-chat-sessions-session-id)

</details>

<a id="summary-get-api-workspaces-workspace-id-chat-sessions-session-id-messages"></a>
### `GET /api/workspaces/{workspace_id}/chat/sessions/{session_id}/messages`

| 항목 | 내용 |
|---|---|
| 목적 | 세션 내 채팅 메시지를 생성 순서대로 반환합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `session_id`: `string` |
| 출력 | `200` 조회 성공 — `ChatMessagesResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | `404` 세션 또는 워크스페이스를 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-workspaces-workspace-id-chat-sessions-session-id-messages"></a>
### `GET /api/workspaces/{workspace_id}/chat/sessions/{session_id}/messages` 상세

#### 1. Method + Path

`GET /api/workspaces/{workspace_id}/chat/sessions/{session_id}/messages`

#### 2. 목적

세션 내 채팅 메시지를 생성 순서대로 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`ChatMessagesResponse`)

Agent turn이 만든 메시지는 `run_id`와 `action`이 함께 온다. 질의 메시지는 두 키가 빠진다.
화면은 `action`으로 편집 미리보기와 일반 답변을 나누고, 승인 상태와 미리보기 본문은 `run_id`가
가리키는 run에서 읽는다.

```json
{
  "messages": [
    {
      "action": "markdown_edit",
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "error_message": "string",
      "id": "string",
      "model": "gpt-5-nano",
      "pair_id": "string",
      "partial_wiki_page_ids": [
        "string"
      ],
      "provider": "openai",
      "references": [
        {
          "id": 1,
          "rank": 0,
          "reference_type": "string",
          "source_block_ids": [
            "string"
          ],
          "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
          "source_refs": [
            {
              "source_block_id": "string",
              "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
            }
          ],
          "text": "string"
        }
      ],
      "related_pages": [
        {
          "depth": 1,
          "page_type": "Concept",
          "rank": 0,
          "relevance_score": 0.87,
          "role": "string",
          "slug": "search-indexing",
          "title": "검색 인덱싱",
          "wiki_page_id": "string"
        }
      ],
      "run_id": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
    }
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `404` | 세션 또는 워크스페이스를 찾을 수 없음 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/messages" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "messages": [
    {
      "action": "markdown_edit",
      "content": "string",
      "created_at": "2026-08-13T04:25:24.371948Z",
      "error_message": "string",
      "id": "string",
      "model": "gpt-5-nano",
      "pair_id": "string",
      "partial_wiki_page_ids": [
        "string"
      ],
      "provider": "openai",
      "references": [
        {
          "id": 1,
          "rank": 0,
          "reference_type": "string",
          "source_block_ids": [
            "string"
          ],
          "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
          "source_refs": [
            {
              "source_block_id": "string",
              "source_document_id": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
            }
          ],
          "text": "string"
        }
      ],
      "related_pages": [
        {
          "depth": 1,
          "page_type": "Concept",
          "rank": 0,
          "relevance_score": 0.87,
          "role": "string",
          "slug": "search-indexing",
          "title": "검색 인덱싱",
          "wiki_page_id": "string"
        }
      ],
      "run_id": "agent_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83"
    }
  ]
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/chat/controller/ChatSessionController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: getMessages`)

[↑ 요약으로 돌아가기](#summary-get-api-workspaces-workspace-id-chat-sessions-session-id-messages)

</details>

<a id="summary-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki`

| 항목 | 내용 |
|---|---|
| 목적 | 세션(full) 또는 선택 문답(partial)을 Markdown 원문 문서로 먼저 저장한 뒤 일반 문서 Ingest를 요청합니다. Wiki 생성은 파이프라인이 비동기로 수행합니다. |
| 입력 | **Path** — `workspace_id`: `string`, `session_id`: `string`<br>**Body** — `ChatWikiExportRequest` |
| 출력 | `202` Wiki 생성 작업 등록 — `ChatWikiExportResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki`

#### 2. 목적

세션(full) 또는 선택 문답(partial)을 Markdown 원문 문서로 먼저 저장한 뒤 일반 문서 Ingest를 요청합니다. Wiki 생성은 파이프라인이 비동기로 수행합니다.

저장되는 Markdown 본문은 문답마다 `[session_id:pair_id]Q :` prefix를 포함한다. 일반 문서 Ingest가 block ID를
새로 부여해도 후처리가 이 prefix에서 원본 문답 provenance를 복원한다.

문서 이름은 채팅에서 왔음을 알리는 `[채팅] ` 접두사로 시작하고, 뒤쪽이 두 단계로 정해진다. 처리 중에는
발췌한 첫 질문을 20자로 줄인 임시 이름을 쓰고, 파이프라인이 끝나면 만들어진 Wiki 페이지 제목으로 확정한다. 페이지 제목이 비었거나 폴백값(`Chat Export`)이면 임시 이름을
그대로 둔다. 같은 이름이 이미 있으면 `(2)`처럼 번호를 붙인다. 세션 ID는 본문에도 이름에도 넣지 않는다.

만들어진 `chat_export` 문서는 문서 목록에 보이지만 **읽기 전용**이다(`editable: false`). 본문을 사람이 고치면
문답 경계를 다시 알아낼 수 없어 provenance가 끊기므로, 편집 잠금·본문 저장·버전 복원·재처리를 모두 거절한다.
재처리는 이 API로 다시 export하는 경로만 쓴다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Content-Type: `application/json` (`ChatWikiExportRequest`)

```json
{
  "pair_ids": [
    "string"
  ]
}
```

#### 5. Response body

- HTTP `202`: Wiki 생성 작업이 대기열에 등록됨
- Content-Type: `*/*` (`ChatWikiExportResponse`)

```json
{
  "exportDocumentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "status": "processing"
}
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/wiki" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"pair_ids":["<value>"]}'
```

```json
{
  "exportDocumentId": "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83",
  "status": "processing"
}
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/chat/controller/ChatWikiExportController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: exportToWiki`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki)

</details>

<a id="summary-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki-preview"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki/preview`

| 항목 | 내용 |
|---|---|
| 목적 | 세션을 llmPipeline 입력용 Markdown으로 직렬화해 결과만 반환합니다. 저장/파이프라인 호출은 하지 않습니다. |
| 입력 | **Path** — `workspace_id`: `string`, `session_id`: `string` |
| 출력 | `200` 성공 — `string` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다.<br>path의 `workspace_id`에 대한 활성 멤버십을 검증한다. |
| 주요 오류 | 공통 오류 계약 적용 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki-preview"></a>
### `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki/preview` 상세

#### 1. Method + Path

`POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/wiki/preview`

#### 2. 목적

세션을 llmPipeline 입력용 Markdown으로 직렬화해 결과만 반환합니다. 저장/파이프라인 호출은 하지 않습니다.
export와 같은 본문이라 `session_id`·`pair_id`는 포함되지 않는다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `workspace_id` | `string` | 예 | - |
| path | `session_id` | `string` | 예 | 채팅 세션 ID |

- Body: 없음

#### 5. Response body

- HTTP `200`: OK
- Content-Type: `text/plain;charset=UTF-8`

```text
string
```

#### 6. Error response

- 명세에 별도 오류 응답이 정의되어 있지 않다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.
- path의 `workspace_id`에 대한 활성 멤버십을 검증한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$DOCUMENT/api/workspaces/ws_9d47a0e9a6324341b47562553b75f92a/chat/sessions/<value>/wiki/preview" \
  -H 'Authorization: Bearer <access_token>'
```

```text
string
```

#### 10. 구현 파일

- 진입점: `services/backend/document-svc/src/main/java/fruition/core/chat/controller/ChatWikiExportController.java`
- 기계 판독 계약: `api-specs/document-svc/openapi.yaml` (`operationId: previewWikiMarkdown`)

[↑ 요약으로 돌아가기](#summary-post-api-workspaces-workspace-id-chat-sessions-session-id-wiki-preview)

</details>
