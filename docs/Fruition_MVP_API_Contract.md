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
- chat_message_references.relevance_score
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
GET    /wiki/graph
GET    /wiki/pages/{wiki_page_id}
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
    },
    {
      "id": "page_source_123",
      "page_type": "source",
      "title": "lecture_01",
      "slug": "lecture-01",
      "relevance_score": 0.87
    }
  ],
  "source_references": [
    {
      "document_id": "doc_123",
      "filename": "lecture_01.pdf",
      "page_number": 3,
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

처리 규칙:

- QueryEngine은 먼저 `wiki_pages.title`과 `wiki_pages.summary`에서 후보 Wiki page를 검색한다.
- 후보 Wiki page의 Markdown만 Object Storage에서 읽는다.
- LLM은 Wiki page와 필요한 원본 근거를 바탕으로 답변한다.
- 응답에는 답변, 관련 Wiki page, 원본 출처, 그래프 highlight 대상을 함께 포함한다.

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
      "references": []
    },
    {
      "id": "chat_assistant_456",
      "role": "assistant",
      "content": "Self-Attention은 입력 토큰들이 서로 어떤 관계를 갖는지 계산하는 Transformer의 핵심 메커니즘이에요.",
      "status": "completed",
      "created_at": "2026-06-04T10:05:03Z",
      "references": [
        {
          "id": 1,
          "reference_type": "wiki_page",
          "wiki_page_id": "page_concept_456",
          "relevance_score": 0.95
        },
        {
          "id": 2,
          "reference_type": "source_quote",
          "document_id": "doc_123",
          "relevance_score": 0.87,
          "page_number": 3,
          "paragraph_index": 2,
          "quote": "Self-attention computes relationships between tokens."
        }
      ]
    }
  ]
}
```

사용처:

- 오른쪽 채팅 영역의 이전 질문/답변 표시
- 답변에 사용된 Wiki page와 원본 출처 표시

## 9. 프론트 화면 매핑

### 왼쪽 사이드바

사용 API:

```text
GET /api/documents
POST /api/documents
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
answer
related_pages
source_references
highlighted_paths
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
