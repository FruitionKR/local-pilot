# 이전 자료

이 문서는 Spring Query API 구현 전 단계의 백로그 스펙입니다.
현재 구현과 최신 API 계약은 `docs/backend-llmpipeline-integration.md`와
`docs/Fruition_MVP_API_Contract.md`를 기준으로 확인합니다.

---

# Spring Boot 백엔드 Query API 구현 Spec

## 1. 목적

Spring Boot 백엔드의 `POST /api/query` 엔드포인트를 구현한다.

현재 이 엔드포인트는 `501 NOT_IMPLEMENTED`를 반환한다. Fruition MVP의 핵심 기능인 채팅 질의응답을 완성하려면 이 엔드포인트가 실제로 동작해야 한다.

구체적으로 이 기능이 해야 할 일:

- 사용자 질문을 llmPipeline(FastAPI `POST /query`)에 위임한다.
- llmPipeline 응답을 `Fruition_MVP_API_Contract.md` 형식으로 변환하여 반환한다.
- 처리된 질문/답변 메시지를 PostgreSQL에 영속화하여 `GET /api/chat/messages`에서 조회할 수 있도록 한다.

---

## 2. 사용자 시나리오

### 시나리오 A: 정상 질의

1. 사용자가 채팅 영역에 질문을 입력하고 전송한다.
2. 프론트엔드가 `POST /api/query { "question": "..." }`를 호출한다.
3. 백엔드가 llmPipeline `POST /query`에 질문을 전달한다.
4. llmPipeline이 답변, 관련 페이지, 근거 스니펫, 그래프 컨텍스트를 반환한다.
5. 백엔드가 응답을 API 계약 형식으로 변환하고, user_message / assistant_message를 DB에 저장한다.
6. 프론트엔드가 답변, 관련 Wiki 페이지, 원본 출처, 하이라이트 경로를 화면에 표시한다.

### 시나리오 B: 빈 질문

1. 사용자가 공백 문자열로 요청을 전송한다.
2. 백엔드가 `400 Bad Request`를 반환한다. llmPipeline은 호출하지 않는다.

### 시나리오 C: llmPipeline 연결 실패

1. 정상 질문이 들어왔으나 llmPipeline이 응답하지 않는다.
2. 30초 타임아웃 초과 시 `503 Service Unavailable`을 반환한다.

### 시나리오 D: 이전 채팅 기록 조회

1. 사용자가 채팅 영역을 다시 열었을 때 `GET /api/chat/messages`가 호출된다.
2. 백엔드가 이전에 영속화된 user/assistant 메시지를 생성 순서대로 반환한다.

---

## 3. API 흐름

### 3.1 POST /api/query

**Request:**

```json
{
  "question": "Self-Attention이 뭐야?"
}
```

**처리 흐름:**

```
1. @NotBlank 입력 검증
2. RestClient로 llmPipeline POST {app.query.endpoint} 호출 (타임아웃: 30초)
3. llmPipeline 응답 수신 (200 OK)
4. 응답 변환 (매핑 규칙 참고)
5. DB에 user_message, assistant_message 영속화
6. 변환된 QueryResponse 반환 (200 OK)
```

**매핑 규칙 (llmPipeline 응답 → API 계약 응답):**

| llmPipeline 필드 | API 계약 필드 | 변환 규칙 |
|---|---|---|
| `answer` | `assistant_message.content` | 그대로 |
| `related_pages[].id` | `related_pages[].id` | 그대로 |
| `related_pages[].page_type` | `related_pages[].page_type` | 그대로 |
| `related_pages[].title` | `related_pages[].title` | 그대로 |
| `related_pages[].slug` | `related_pages[].slug` | 그대로 |
| `related_pages[].relevance_score` | `related_pages[].relevance_score` | 그대로 |
| `related_pages[].role` | (제거) | API 계약에 없음 |
| `related_pages[].depth` | (제거) | API 계약에 없음 |
| `graph_context.edges[].from_page_id` | `highlighted_paths[].from_page_id` | 그대로 |
| `graph_context.edges[].to_page_id` | `highlighted_paths[].to_page_id` | 그대로 |
| `graph_context.edges[].link_type` | `highlighted_paths[].link_type` | 그대로 |
| `graph_context.edges[].role` | (제거) | API 계약에 없음 |
| `graph_context.edges[].score` | (제거) | API 계약에 없음 |
| `evidence_snippets[]` | `source_references[]` | 아래 별도 규칙 |
| `graph_context.nodes` | (제외) | API 계약에 없음 |
| `traversal_paths` | (제외) | API 계약에 없음 |
| `retrieval_summary` | (제외) | API 계약에 없음 |

**source_references 매핑 상세:**

`evidence_snippets` 중 `page_id`가 `"source:"` prefix로 시작하는 항목만 `source_references`에 포함한다.

| llmPipeline `evidence_snippets` 필드 | `source_references` 필드 | 변환 규칙 |
|---|---|---|
| `page_id` | `document_id` | `"source:"` prefix 제거 후 Document 조회하여 document ID 추출 |
| — | `filename` | DB에서 Document 조회하여 `filename` 추출 |
| `text` | `quote` | 그대로 |
| `paragraph_index` | `paragraph_index` | 그대로 (없으면 null) |
| — | `page_number` | null (llmPipeline이 현재 제공하지 않음, MVP) |

**Response (200 OK):**

```json
{
  "user_message": {
    "id": "chat_user_...",
    "role": "user",
    "content": "Self-Attention이 뭐야?",
    "status": "completed",
    "created_at": "2026-06-04T10:05:00Z"
  },
  "assistant_message": {
    "id": "chat_assistant_...",
    "role": "assistant",
    "content": "Self-Attention은 입력 토큰들이 서로 어떤 관계를 갖는지 계산하는 Transformer의 핵심 메커니즘이에요.",
    "status": "completed",
    "created_at": "2026-06-04T10:05:03Z"
  },
  "related_pages": [
    {
      "id": "page_concept_456",
      "page_type": "concept",
      "title": "Self-Attention",
      "slug": "self-attention",
      "relevance_score": 0.95
    }
  ],
  "source_references": [
    {
      "document_id": "doc_123",
      "filename": "lecture_01.pdf",
      "page_number": null,
      "paragraph_index": 2,
      "quote": "Self-attention computes relationships between tokens."
    }
  ],
  "highlighted_paths": [
    {
      "from_page_id": "page_source_123",
      "to_page_id": "page_concept_456",
      "link_type": "source_mentions_concept"
    }
  ]
}
```

### 3.2 GET /api/chat/messages

현재 `ChatController`는 빈 목록을 반환한다. Query 영속화 구현과 함께 실제 DB 조회로 변경한다.

**Response (200 OK):**

```json
{
  "messages": [
    {
      "id": "chat_user_...",
      "role": "user",
      "content": "Self-Attention이 뭐야?",
      "status": "completed",
      "created_at": "2026-06-04T10:05:00Z",
      "references": []
    },
    {
      "id": "chat_assistant_...",
      "role": "assistant",
      "content": "Self-Attention은 ...",
      "status": "completed",
      "created_at": "2026-06-04T10:05:03Z",
      "references": []
    }
  ]
}
```

MVP에서 `references` 필드는 빈 배열로 반환한다.

---

## 4. 성공 조건

- llmPipeline이 정상 응답(200 OK)을 반환했을 때, Spring Boot는 200 OK와 변환된 QueryResponse를 반환한다.
- `user_message`와 `assistant_message`가 `chat_messages` 테이블에 저장된다.
- `GET /api/chat/messages` 호출 시 방금 저장된 메시지가 시간 순으로 포함된다.

---

## 5. 실패 조건

| 조건 | HTTP 상태 | error code |
|---|---|---|
| `question`이 빈 문자열 또는 null | 400 | `INVALID_REQUEST` |
| llmPipeline 타임아웃 (30초 초과) | 503 | `PIPELINE_TIMEOUT` |
| llmPipeline 4xx 응답 | 502 | `PIPELINE_ERROR` |
| llmPipeline 5xx 응답 | 503 | `PIPELINE_UNAVAILABLE` |
| DB 영속화 실패 | 500 | `INTERNAL_SERVER_ERROR` |

---

## 6. Acceptance Criteria

**AC-1**: `question`이 빈 문자열이면 400 Bad Request와 `INVALID_REQUEST` error code를 반환한다.

**AC-2**: `question`이 정상이고 llmPipeline이 200을 반환하면, Spring Boot는 200 OK와 변환된 `QueryResponse`를 반환한다.

**AC-3**: 응답의 `user_message.role`은 `"user"`, `content`는 요청 `question`과 동일하다.

**AC-4**: 응답의 `assistant_message.role`은 `"assistant"`, `content`는 llmPipeline 응답의 `answer`와 동일하다.

**AC-5**: 응답의 `related_pages`는 llmPipeline `related_pages`에서 `role`, `depth`를 제거한 목록과 동일하다.

**AC-6**: 응답의 `highlighted_paths`는 llmPipeline `graph_context.edges`에서 `from_page_id`, `to_page_id`, `link_type`만 포함한 목록과 동일하다.

**AC-7**: 응답의 `source_references`는 llmPipeline `evidence_snippets` 중 `page_id`가 `"source:"`로 시작하는 항목만 포함하며, 각 항목의 `document_id`와 `filename`은 DB 조회 결과와 일치한다.

**AC-8**: 정상 질의 후 `GET /api/chat/messages`를 호출하면 해당 user/assistant 메시지 쌍이 포함된다.

**AC-9**: llmPipeline이 30초 내에 응답하지 않으면 503을 반환한다.

**AC-10**: llmPipeline이 5xx를 반환하면 503을 반환한다.

**AC-11**: llmPipeline이 4xx를 반환하면 502를 반환한다.

---

## 7. 설계 제약사항 및 구현 노트

### 7.1 신규 추가 설정 (application.properties)

```properties
# Query Pipeline
app.query.endpoint=${QUERY_ENDPOINT:http://localhost:8000/query}
app.query.timeout-seconds=${QUERY_TIMEOUT_SECONDS:30}
```

기존 `app.processing.endpoint`는 문서 파이프라인 실행 전용이다. query 엔드포인트는 별도 설정 키를 사용한다.

### 7.2 레이어 구조

기존 코드베이스 패턴(`DocumentProcessingRequester` 참고)을 따른다.

```
QueryController
  └─ QueryService                    # 변환 로직, 영속화 조율
       ├─ PipelineQueryClient        # llmPipeline HTTP 호출 (RestClient)
       ├─ ChatMessageRepository      # JPA, chat_messages 테이블
       └─ DocumentRepository         # source_references 매핑 시 filename 조회
```

### 7.3 DB 영속화 - chat_messages 엔티티

```
chat_messages
  id          VARCHAR PK    (e.g. "chat_user_<uuid>", "chat_assistant_<uuid>")
  role        VARCHAR       (user / assistant)
  content     TEXT
  status      VARCHAR       (completed / failed)
  created_at  TIMESTAMPTZ   (NOT NULL)
```

JPA `@Entity`로 정의하고 `spring.jpa.hibernate.ddl-auto=update`를 통해 자동 생성한다.

### 7.4 HTTP 클라이언트

`DocumentProcessingRequester`와 동일하게 `RestClient`와 `SimpleClientHttpRequestFactory`를 사용한다. 타임아웃은 `ReadTimeout`으로 설정한다.

### 7.5 source_references 조회 최적화

`evidence_snippets`의 `page_id`에서 document ID를 일괄 추출한 뒤 `DocumentRepository.findAllById()`로 한 번에 조회한다. N+1 방지.

---

## 8. 테스트 관점

### 8.1 단위 테스트 (Unit Test)

**QueryService (변환 로직)**
- llmPipeline 응답 → `QueryResponse` 변환 결과 검증
- `source_references` 필터링: `page_id`가 `"source:"`로 시작하지 않는 항목이 제외되는지 확인
- `highlighted_paths` 변환: `role`, `score` 제거, 나머지 필드 동일한지 확인
- `related_pages` 변환: `role`, `depth` 제거, 나머지 필드 동일한지 확인
- user_message / assistant_message ID, role, content, status 구조 검증

**PipelineQueryClient (HTTP 클라이언트)**
- 타임아웃 설정이 RestClient에 올바르게 적용되는지 확인
- llmPipeline이 4xx 반환 시 적절한 예외를 throw하는지 확인
- llmPipeline이 5xx 반환 시 적절한 예외를 throw하는지 확인

### 8.2 통합 테스트 (Integration Test)

**POST /api/query** (MockMvc + WireMock 또는 `@SpringBootTest`)
- 정상 흐름: llmPipeline stub → 200 OK, 변환된 QueryResponse 반환
- `question` 누락 → 400 Bad Request, `INVALID_REQUEST` 포함
- llmPipeline 타임아웃 stub → 503 반환
- llmPipeline 5xx stub → 503 반환
- llmPipeline 4xx stub → 502 반환

**GET /api/chat/messages**
- `POST /api/query` 정상 호출 후 → 메시지 목록에 해당 user/assistant 메시지가 순서대로 포함되는지 확인

---

## 9. Out of Scope

이번 구현에서 아래 항목은 포함하지 않는다.

- **멀티 워크스페이스 / 사용자 인증**: MVP는 단일 데모 워크스페이스, 로그인 없음
- **source_references의 page_number**: llmPipeline이 현재 page_number를 제공하지 않으므로 null 처리. 추후 llmPipeline 응답 확장 시 반영
- **비동기 스트리밍 응답**: MVP는 동기 호출. 30초 타임아웃 내에 응답하도록 전제
- **chat_message_references 영속화**: MVP에서 `GET /api/chat/messages`의 `references` 필드는 빈 배열 반환. 별도 스펙으로 분리
- **질의 실패 시 user_message DB 저장 정책**: 실패 케이스에서 user_message 단독 저장 여부는 별도 정책 결정 후 구현
- **llmPipeline의 traversal_paths, retrieval_summary, graph_context.nodes**: API 계약 응답에 포함하지 않음
- **재시도(Retry) 정책**: MVP는 재시도 없이 즉시 에러 반환
