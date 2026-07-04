# 이전 MVP API 계약 (로그인/워크스페이스 도입 전)

이 문서는 로그인 없이 단일 기본 workspace만 사용하던 시절의 API 계약입니다.
현재 API 계약은 `docs/Fruition_MVP_API_Contract.md`를 우선 확인합니다.

---

# Fruition MVP API Contract

## 1. 기준

- MVP 목표: 파일명을 몰라도 개념이나 질문만으로 관련 Wiki page와 원본 근거를 찾을 수 있는지 검증
- 로그인 없음
- 하나의 기본 demo workspace 기준
- 원본 파일은 Object Storage에 저장
- 서비스 관리 정보는 PostgreSQL에 저장
- 그래프 node는 `wiki_pages`
- 그래프 edge는 `wiki_page_links`
- 원본 파일은 그래프 node가 아니며, `source page`가 원본 문서를 대표

## 2. 공통 규칙

### Base URL

```text
/api
```

### 공통 응답 형식

성공 응답은 API별 data 형식을 그대로 반환한다.

실패 응답:

```json
{
  "error": {
    "code": "DOCUMENT_NOT_FOUND",
    "message": "문서를 찾을 수 없습니다."
  }
}
```

### 공통 에러 응답

#### 400 Bad Request

요청 값이 잘못된 경우.

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다.",
    "details": [
      {
        "field": "question",
        "reason": "질문은 비어 있을 수 없습니다."
      }
    ]
  }
}
```

#### 404 Not Found

요청한 리소스가 없는 경우.

```json
{
  "error": {
    "code": "DOCUMENT_NOT_FOUND",
    "message": "문서를 찾을 수 없습니다."
  }
}
```

#### 409 Conflict

이미 같은 파일이 업로드되었거나 현재 상태에서 처리할 수 없는 경우.

```json
{
  "error": {
    "code": "DOCUMENT_ALREADY_EXISTS",
    "message": "이미 업로드된 문서입니다."
  }
}
```

#### 415 Unsupported Media Type

지원하지 않는 파일 형식인 경우.

```json
{
  "error": {
    "code": "UNSUPPORTED_FILE_TYPE",
    "message": "PDF 또는 Markdown 파일만 업로드할 수 있습니다."
  }
}
```

#### 500 Internal Server Error

서버 내부 오류가 발생한 경우.

```json
{
  "error": {
    "code": "INTERNAL_SERVER_ERROR",
    "message": "서버 처리 중 오류가 발생했습니다."
  }
}
```

### optional 필드 규칙

아직 값이 생성되지 않았거나 해당 상태에서 필요 없는 값은 응답에서 생략할 수 있다.

예:

```text
extracted_text_uri
- 문서 업로드 직후에는 아직 텍스트 추출 전이므로 생략 가능
- 처리 완료 후에는 sources/documents/{document_id}/extracted.txt

processed_at
- 처리 중에는 생략 가능
- completed 또는 failed 상태가 되면 처리 종료 시각

error_message
- 정상 처리 중이거나 성공이면 생략
- failed 상태이면 실패 사유
```

프론트는 `status`를 기준으로 화면을 분기하고, optional 필드는 없을 수 있다고 처리한다.

### DB schema와 API 응답 관계

API 응답은 DB row 전체를 그대로 노출하지 않는다.

```text
DB에 있지만 기본 API 응답에서 생략 가능한 필드
- workspace_id: MVP는 하나의 기본 demo workspace를 사용하므로 클라이언트에 노출하지 않음
- content_hash: 중복 업로드 감지용 내부 필드
- created_at / updated_at: 화면에 필요 없는 목록 API에서는 생략 가능

연결 테이블에서 가져와 API에 포함하는 필드
- document_wiki_links.relation_type
- document_wiki_links.confidence
- wiki_page_links.link_type
- wiki_page_links.label
- wiki_page_links.confidence
- chat_message_references.reference_type
- chat_message_references.source_block_ids
```

즉, API 응답은 화면과 프론트 상태 관리에 필요한 필드를 중심으로 구성하고, 내부 처리용 필드는 서버 내부에 둔다.

### 공통 Enum

#### document status

```text
uploaded
processing
completed
failed
```

#### wiki page type

```text
source
concept
```

#### wiki page status

```text
draft
active
failed
```

#### chat role

```text
user
assistant
```

#### chat status

```text
completed
failed
```

#### link type

```text
source_mentions_concept
concept_related_to
concept_contrasts_with
source_related_to
```

#### document wiki relation type

```text
source_of
extracted_concept
```

## 3. Object Storage 경로 규칙

```text
sources/documents/{document_id}/original
sources/documents/{document_id}/extracted.txt
wiki/sources/{document_slug}.md
wiki/concepts/{concept_slug}.md
```

## 4. API 목록

```text
POST   /documents
GET    /documents
GET    /documents/{document_id}
GET    /documents/{document_id}/original
GET    /documents/{document_id}/blocks
PATCH  /documents/{document_id}/rename
GET    /wiki/graph
GET    /wiki/pages/{wiki_page_id}
PATCH  /wiki/pages/{wiki_page_id}/rename
POST   /query
GET    /chat/messages
```

## 5. Documents API

### 5.1 문서 업로드

```http
POST /api/documents
Content-Type: multipart/form-data
```

Request:

```text
file: PDF 또는 Markdown 파일
```

Response:

```json
{
  "id": "doc_123",
  "filename": "lecture_01.pdf",
  "mime_type": "application/pdf",
  "byte_size": 1024000,
  "status": "processing",
  "source_uri": "sources/documents/doc_123/original",
  "uploaded_at": "2026-06-04T10:00:00Z"
}
```

처리 규칙:

- API는 원본 파일 저장과 `documents` 레코드 생성 후 즉시 응답한다.
- 문서 텍스트 추출과 Wiki 생성은 백그라운드에서 처리한다.
- 처리 중 상태는 `processing`이다.
- 성공 시 `completed`, 실패 시 `failed`로 갱신한다.

### 5.2 문서 목록 조회

```http
GET /api/documents
```

Response:

```json
{
  "documents": [
    {
      "id": "doc_123",
      "filename": "lecture_01.pdf",
      "mime_type": "application/pdf",
      "byte_size": 1024000,
      "status": "completed",
      "source_uri": "sources/documents/doc_123/original",
      "extracted_text_uri": "sources/documents/doc_123/extracted.txt",
      "uploaded_at": "2026-06-04T10:00:00Z",
      "processed_at": "2026-06-04T10:01:20Z"
    }
  ]
}
```

사용처:

- 왼쪽 사이드바의 원본 파일 flat list
- 문서 처리 상태 polling

실패한 문서 예시:

```json
{
  "id": "doc_456",
  "filename": "broken.pdf",
  "mime_type": "application/pdf",
  "byte_size": 204800,
  "status": "failed",
  "source_uri": "sources/documents/doc_456/original",
  "uploaded_at": "2026-06-04T10:10:00Z",
  "processed_at": "2026-06-04T10:10:15Z",
  "error_message": "PDF 텍스트 추출에 실패했습니다."
}
```

### 5.3 문서 상세 조회

```http
GET /api/documents/{document_id}
```

Response:

```json
{
  "id": "doc_123",
  "filename": "lecture_01.pdf",
  "mime_type": "application/pdf",
  "byte_size": 1024000,
  "status": "completed",
  "source_uri": "sources/documents/doc_123/original",
  "extracted_text_uri": "sources/documents/doc_123/extracted.txt",
  "uploaded_at": "2026-06-04T10:00:00Z",
  "processed_at": "2026-06-04T10:01:20Z",
  "wiki_pages": [
    {
      "id": "page_source_123",
      "page_type": "source",
      "title": "lecture_01",
      "slug": "lecture-01",
      "relation_type": "source_of",
      "confidence": 1.0
    },
    {
      "id": "page_concept_456",
      "page_type": "concept",
      "title": "Self-Attention",
      "slug": "self-attention",
      "relation_type": "extracted_concept",
      "confidence": 0.92
    }
  ]
}
```

### 5.4 원본 파일 스트리밍

```http
GET /api/documents/{document_id}/original
```

Response: 원본 파일 바이너리 스트림

```text
Content-Type: application/pdf
Content-Disposition: inline; filename="lecture_01.pdf"
```

처리 규칙:

- `documents.source_uri`를 기준으로 MinIO에서 원본 파일을 스트리밍한다.
- `Content-Type`은 `documents.mime_type` 기준이다.
- PDF·text/* 계열은 `Content-Disposition: inline`, 그 외는 `attachment`로 반환한다.

주요 error code:

| code | HTTP status | description |
| --- | --- | --- |
| `DOCUMENT_NOT_FOUND` | 404 | 문서 ID가 존재하지 않는다. |
| `DOCUMENT_ORIGINAL_NOT_FOUND` | 404 | DB에 문서 레코드는 있으나 MinIO 원본 객체가 없다. |

### 5.5 문서 이름 변경

```http
PATCH /api/documents/{document_id}/rename
Content-Type: application/json
```

Request:

```json
{
  "filename": "lecture_01_renamed.pdf",
  "sync_source_title": false
}
```

Request fields:

| field | type | required | description |
| --- | --- | --- | --- |
| `filename` | string | O | 사용자가 지정한 문서 표시명. 원본 파일 확장자를 유지하는 것을 권장한다. |
| `sync_source_title` | boolean | X | 대응되는 source page가 있을 때 source page `title`도 함께 변경할지 여부. 기본값은 `false`다. |

Response:

```json
{
  "id": "doc_123",
  "filename": "lecture_01_renamed.pdf",
  "previous_filename": "lecture_01.pdf",
  "source_uri": "sources/documents/doc_123/original",
  "status": "completed",
  "renamed_at": "2026-06-04T10:20:00Z",
  "source_page": {
    "id": "source:doc_123",
    "title": "lecture_01",
    "renamed": false
  }
}
```

처리 규칙:

- 이 API는 `documents.filename`만 변경한다.
- MinIO 원본 객체 경로인 `source_uri`와 content hash는 변경하지 않는다.
- 문서 처리 상태가 `processing`, `completed`, `failed`여도 이름 변경은 허용한다.
- `sync_source_title=true`이고 `source:{document_id}` page가 존재하면 source page `title`도 같은 표시명 기반으로 변경한다.
- `sync_source_title=true`인데 source page가 아직 없으면 문서 이름만 변경하고 `source_page`는 `null`로 반환한다.
- 처리 중인 문서의 source page가 이후 생성될 때 source title을 변경된 `documents.filename` 기준으로 만들지는 백엔드 정책으로 명시해야 한다. MVP 기본 정책은 생성 시점의 최신 `documents.filename`을 사용한다.

검증 규칙:

- `filename`은 trim 후 1자 이상이어야 한다.
- `filename`은 255자를 넘지 않는다.
- `/`, `\`, NULL 문자 등 object key 또는 path로 오인될 수 있는 문자는 거부한다.
- 같은 workspace 또는 같은 사용자 범위에서 중복 이름을 허용할지 여부는 백엔드 정책으로 정한다. MVP 기본 정책은 중복 허용이다.

Error response:

```json
{
  "error": {
    "code": "INVALID_DOCUMENT_FILENAME",
    "message": "문서 이름은 1자 이상 255자 이하여야 합니다."
  }
}
```

주요 error code:

| code | HTTP status | description |
| --- | --- | --- |
| `DOCUMENT_NOT_FOUND` | 404 | 문서 ID가 존재하지 않는다. |
| `INVALID_DOCUMENT_FILENAME` | 400 | 이름이 비어 있거나 허용되지 않는 문자를 포함한다. |
| `DOCUMENT_RENAME_CONFLICT` | 409 | 백엔드가 중복 이름을 금지하는 정책일 때 같은 이름이 이미 존재한다. |

### 5.6 원본 문서 block 목록 조회

```http
GET /api/documents/{document_id}/blocks
```

Response:

```json
{
  "document_id": "doc_123",
  "blocks": [
    {
      "block_id": "B0005",
      "text": "원본 문서의 다섯 번째 block 본문"
    },
    {
      "block_id": "B0006",
      "text": "원본 문서의 여섯 번째 block 본문"
    }
  ]
}
```

처리 규칙:

- `source_blocks` 테이블에서 해당 `document_id`의 block을 `block_id` 오름차순으로 조회한다.
- block이 없어도 200과 빈 배열을 반환한다 (404 아님).
- 답변 citation `[n]` 클릭 시 `evidence_snippets[].source_document_id` + `source_block_ids`로 이 API를 호출해 원본 block을 가져와 하이라이트한다.

주요 error code:

| code | HTTP status | description |
| --- | --- | --- |
| `DOCUMENT_NOT_FOUND` | 404 | 문서 ID가 존재하지 않는다. |

## 6. Wiki API

### 6.1 Wiki graph 조회

```http
GET /api/wiki/graph
```

Response:

```json
{
  "nodes": [
    {
      "id": "page_source_123",
      "page_type": "source",
      "title": "lecture_01",
      "slug": "lecture-01",
      "summary": "Transformer 강의자료 요약입니다.",
      "status": "active",
      "source_document": {
        "id": "doc_123",
        "filename": "lecture_01.pdf"
      }
    },
    {
      "id": "page_concept_456",
      "page_type": "concept",
      "title": "Self-Attention",
      "slug": "self-attention",
      "summary": "토큰 간 관계를 계산하는 Transformer의 핵심 메커니즘입니다.",
      "status": "active"
    }
  ],
  "edges": [
    {
      "from_page_id": "page_source_123",
      "to_page_id": "page_concept_456",
      "link_type": "source_mentions_concept",
      "label": "mentions",
      "confidence": 0.92
    }
  ]
}
```

사용처:

- 중앙 Wiki graph 렌더링
- 답변 후 관련 node/path highlight

### 6.2 Wiki page 상세 조회

```http
GET /api/wiki/pages/{wiki_page_id}
```

Response:

```json
{
  "id": "page_concept_456",
  "page_type": "concept",
  "title": "Self-Attention",
  "slug": "self-attention",
  "summary": "토큰 간 관계를 계산하는 Transformer의 핵심 메커니즘입니다.",
  "markdown_uri": "wiki/concepts/self-attention.md",
  "markdown": "# Self-Attention\n\n## Definition\n...",
  "status": "active",
  "created_at": "2026-06-04T10:01:10Z",
  "updated_at": "2026-06-04T10:01:20Z",
  "source_documents": [
    {
      "id": "doc_123",
      "filename": "lecture_01.pdf",
      "source_uri": "sources/documents/doc_123/original",
      "relation_type": "extracted_concept",
      "confidence": 0.92
    }
  ],
  "related_pages": [
    {
      "id": "page_concept_789",
      "page_type": "concept",
      "title": "Transformer",
      "slug": "transformer",
      "link_type": "concept_related_to",
      "label": "related",
      "confidence": 0.81
    }
  ]
}
```

source page 상세의 경우 `source_documents`에는 대응되는 원본 문서 1개가 들어간다.

### 6.3 Wiki page 이름 변경

```http
PATCH /api/wiki/pages/{wiki_page_id}/rename
Content-Type: application/json
```

Request:

```json
{
  "title": "Self-Attention 개념 정리",
  "update_slug": false
}
```

Request fields:

| field | type | required | description |
| --- | --- | --- | --- |
| `title` | string | O | 사용자가 지정한 Wiki page 표시명. |
| `update_slug` | boolean | X | title 변경에 맞춰 slug도 재생성할지 여부. 기본값은 `false`다. |

Response:

```json
{
  "id": "page_concept_456",
  "page_type": "concept",
  "title": "Self-Attention 개념 정리",
  "previous_title": "Self-Attention",
  "slug": "self-attention",
  "previous_slug": "self-attention",
  "slug_updated": false,
  "updated_at": "2026-06-04T10:25:00Z"
}
```

처리 규칙:

- 이 API는 `wiki_pages.title`을 변경한다.
- `update_slug=false`이면 기존 slug와 markdown URI를 유지한다.
- `update_slug=true`이면 title 기반으로 slug를 재생성한다.
- slug를 변경하더라도 page id는 유지한다.
- slug 변경 시 `wiki_pages.slug` 중복을 검증한다.
- markdown 파일 경로(`markdown_uri`)까지 변경할지는 별도 migration이 필요하므로 MVP 기본 정책은 변경하지 않는다.
- source page 이름 변경은 원본 문서 이름 변경과 독립적으로 허용한다. 원본 문서 이름까지 함께 변경하려면 `PATCH /api/documents/{document_id}/rename`의 `sync_source_title` 정책을 사용한다.

검증 규칙:

- `title`은 trim 후 1자 이상이어야 한다.
- `title`은 255자를 넘지 않는다.
- `page_type`이 `source`, `concept` 모두 rename 가능하다.

Error response:

```json
{
  "error": {
    "code": "INVALID_WIKI_PAGE_TITLE",
    "message": "Wiki page 제목은 1자 이상 255자 이하여야 합니다."
  }
}
```

주요 error code:

| code | HTTP status | description |
| --- | --- | --- |
| `WIKI_PAGE_NOT_FOUND` | 404 | Wiki page ID가 존재하지 않는다. |
| `INVALID_WIKI_PAGE_TITLE` | 400 | 제목이 비어 있거나 너무 길다. |
| `WIKI_PAGE_SLUG_CONFLICT` | 409 | `update_slug=true`이고 재생성된 slug가 이미 존재한다. |

## 7. Query API

### 7.1 Wiki 기반 자연어 질의

```http
POST /api/query
Content-Type: application/json
```

Request:

```json
{
  "question": "Self-Attention이 뭐야?"
}
```

Response:

```json
{
  "user_message": {
    "id": "chat_user_123",
    "role": "user",
    "content": "Self-Attention이 뭐야?",
    "status": "completed",
    "created_at": "2026-06-04T10:05:00Z"
  },
  "assistant_message": {
    "id": "chat_assistant_456",
    "role": "assistant",
    "content": "Self-Attention은 입력 토큰들이 서로 어떤 관계를 갖는지 계산하는 Transformer의 핵심 메커니즘이에요. [1]",
    "status": "completed",
    "created_at": "2026-06-04T10:05:03Z"
  },
  "related_pages": [
    {
      "id": "page_concept_456",
      "page_type": "concept",
      "title": "Self-Attention",
      "slug": "self-attention",
      "relevance_score": 0.95,
      "role": "concept",
      "depth": 1
    },
    {
      "id": "page_source_123",
      "page_type": "source",
      "title": "lecture_01",
      "slug": "lecture-01",
      "relevance_score": 0.87,
      "role": "source",
      "depth": 0
    }
  ],
  "evidence_snippets": [
    {
      "rank": 1,
      "source_document_id": "doc_123",
      "source_block_ids": ["B0005", "B0006"],
      "text": "Self-attention computes relationships between tokens."
    }
  ],
  "graph_context": {
    "nodes": [
      {
        "id": "page_source_123",
        "page_type": "source",
        "title": "lecture_01",
        "slug": "lecture-01",
        "relevance_score": 0.87,
        "role": "source",
        "depth": 0
      }
    ],
    "edges": [
      {
        "from_page_id": "page_source_123",
        "to_page_id": "page_concept_456",
        "link_type": "source_mentions_concept",
        "role": "forward",
        "score": 0.88
      }
    ]
  },
  "traversal_paths": [
    {
      "path_id": "path_01",
      "role": "primary",
      "used_for_answer": true,
      "score": 0.91,
      "stop_reason": "relative_score_cutoff",
      "nodes": ["page_source_123", "page_concept_456"],
      "edges": [
        {
          "from_page_id": "page_source_123",
          "to_page_id": "page_concept_456",
          "link_type": "source_mentions_concept",
          "role": "forward",
          "score": 0.88
        }
      ]
    }
  ]
}
```

Response fields:

| field | description |
| --- | --- |
| `user_message` | 저장된 사용자 메시지 요약 |
| `assistant_message` | 저장된 어시스턴트 메시지 요약. 답변 본문에는 `[1]`, `[2]` 형태의 evidence rank 표식이 포함될 수 있다. |
| `related_pages` | 탐색에 사용된 Wiki page 목록. `role`은 탐색 중 page의 역할(`source`/`concept`), `depth`는 그래프 탐색 깊이 |
| `evidence_snippets` | 답변 근거로 사용된 원본 문서 block 단위 snippet. `rank`는 답변 본문의 `[N]` 표식과 대응하며, `source_document_id` + `source_block_ids`로 `GET /api/documents/{document_id}/blocks`를 호출해 원본 block을 가져올 수 있다. |
| `graph_context` | 탐색 중 방문한 nodes와 edges. 그래프 하이라이트 렌더링에 사용한다. |
| `traversal_paths` | 탐색 경로 목록. `used_for_answer=true`인 path가 실제 답변 생성에 사용된 경로다. |

처리 규칙:

- QueryEngine은 먼저 `wiki_pages`에서 질문과 유사도가 높은 source page를 탐색 시작점으로 선택한다.
- 탐색 중 관측된 최고 유사도 기준 95% 미만 후보는 제외한다.
- LLM은 Wiki page와 evidence snippet을 바탕으로 답변을 생성한다. 근거가 없으면 unsupported 고정 응답을 반환한다.
- 답변 본문의 `[N]` 표식은 `evidence_snippets[].rank`와 대응한다.
- 응답에는 답변 메시지, 관련 Wiki page, evidence snippet, 그래프 탐색 경로를 함께 포함한다.

## 8. Chat API

### 8.1 채팅 기록 조회

```http
GET /api/chat/messages
```

Response:

```json
{
  "messages": [
    {
      "id": "chat_user_123",
      "role": "user",
      "content": "Self-Attention이 뭐야?",
      "status": "completed",
      "created_at": "2026-06-04T10:05:00Z",
      "related_pages": [],
      "references": []
    },
    {
      "id": "chat_assistant_456",
      "role": "assistant",
      "content": "Self-Attention은 입력 토큰들이 서로 어떤 관계를 갖는지 계산하는 Transformer의 핵심 메커니즘이에요. [1]",
      "status": "completed",
      "created_at": "2026-06-04T10:05:03Z",
      "related_pages": [
        {
          "wiki_page_id": "source:lecture-01",
          "page_type": "source",
          "title": "lecture_01",
          "slug": "lecture-01",
          "relevance_score": 0.95,
          "role": "seed_source",
          "depth": 0,
          "rank": 1
        },
        {
          "wiki_page_id": "concept:self-attention",
          "page_type": "concept",
          "title": "Self-Attention",
          "slug": "self-attention",
          "relevance_score": 0.88,
          "role": "focus_concept",
          "depth": 1,
          "rank": 2
        }
      ],
      "references": [
        {
          "id": 1,
          "reference_type": "source_block",
          "rank": 1,
          "source_document_id": "doc_123",
          "source_block_ids": ["B0005", "B0006"],
          "text": "Self-attention computes relationships between tokens."
        }
      ]
    }
  ]
}
```

Response fields:

| field | description |
| --- | --- |
| `related_pages` | pipeline `related_pages` 기준 탐색된 Wiki page 목록. 프론트 "찾은 자료" 카드 기준. |
| `references` | pipeline `evidence_snippets` 기준 인용 근거. 답변 본문 `[N]` citation과 대응하며, 원본 문서 block 기준(`source_document_id` + `source_block_ids`)으로 저장된다. |

사용처:

- 오른쪽 채팅 영역의 이전 질문/답변 표시
- "찾은 자료" 카드 목록 (`related_pages` 기준)
- 답변 citation 근거 표시 (`references` 기준)

## 9. 프론트 화면 매핑

### 왼쪽 사이드바

사용 API:

```text
GET /api/documents
POST /api/documents
GET /api/documents/{document_id}/original
PATCH /api/documents/{document_id}/rename
```

표시 데이터:

```text
filename
status
error_message
```

### 중앙 Wiki graph

사용 API:

```text
GET /api/wiki/graph
GET /api/wiki/pages/{wiki_page_id}
PATCH /api/wiki/pages/{wiki_page_id}/rename
```

표시 데이터:

```text
nodes = wiki_pages
edges = wiki_page_links
selected page detail
```

### 오른쪽 채팅

사용 API:

```text
POST /api/query
GET /api/chat/messages
```

표시 데이터:

```text
question
answer (with [N] evidence markers)
related_pages
evidence_snippets
graph_context (그래프 하이라이트용)
traversal_paths
```

## 10. MVP 제외

아래 기능은 MVP API에 포함하지 않는다.

- 로그인/회원가입
- workspace 선택 UI
- 폴더형 파일 트리
- 작업 큐/재시도 API
- 사용자 정의 Wiki guideline API
- 승인/롤백 API
- 벡터 검색 API
- query answer 또는 synthesis page 승격 API
- graph node 좌표 저장 API
- 팀 협업/공유 API
