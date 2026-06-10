# Fruition Backend

Spring Boot 기반 문서 업로드 및 처리 백엔드 서버입니다.

## 기술 스택

| 항목 | 버전 |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.x |
| PostgreSQL | 16 |
| MinIO | 최신 |
| Gradle | Wrapper 포함 |
| springdoc-openapi | 2.8.16 |

---

## 로컬 개발 환경 세팅

### 1. 사전 요구사항

- Java 21 이상 (`java -version` 으로 확인)
- Docker Desktop 실행 중

### 2. 인프라 실행 (PostgreSQL + MinIO)

`infra/` 디렉토리에서 실행합니다. `.env` 파일이 해당 위치에 있어야 합니다.

```bash
cd infra
docker compose -f docker-compose.dev.yml up -d
```

컨테이너가 정상 기동되면 다음 서비스가 활성화됩니다.

| 서비스 | 주소 |
|---|---|
| PostgreSQL | `localhost:5432` |
| MinIO API | `localhost:9000` |
| MinIO 콘솔 | `http://localhost:9001` |

MinIO 콘솔 로그인: `fruition` / `fruition_dev_secret`

### 3. 서버 실행

```bash
cd backend
./gradlew bootRun
```

서버가 기동되면 `http://localhost:8080` 에서 응답합니다.

### 4. 인프라 종료

```bash
# infra/ 디렉토리에서 실행
docker compose -f docker-compose.dev.yml down
```

볼륨까지 초기화하려면:

```bash
docker compose -f docker-compose.dev.yml down -v
```

---

## 환경 변수

환경 변수는 `infra/.env` 파일에서 관리합니다.
처음 세팅할 때 `infra/.env.example`을 복사해서 사용하세요.

```bash
cp infra/.env.example infra/.env
```

`./gradlew bootRun` 실행 시 `infra/.env`를 자동으로 읽어 환경변수를 주입합니다.
Docker Compose 인프라 기동 시에도 동일한 파일을 사용하므로 값을 한 곳에서만 관리하면 됩니다.

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | PostgreSQL 호스트 |
| `POSTGRES_PORT` | `5432` | PostgreSQL 포트 |
| `POSTGRES_DB` | `fruition_mvp` | 데이터베이스명 |
| `POSTGRES_USER` | `fruition` | DB 사용자명 |
| `POSTGRES_PASSWORD` | `fruition_dev_password` | DB 비밀번호 |
| `S3_ENDPOINT` | `http://localhost:9000` | MinIO 엔드포인트 |
| `S3_ACCESS_KEY` | `fruition` | MinIO 액세스 키 |
| `S3_SECRET_KEY` | `fruition_dev_secret` | MinIO 시크릿 키 |
| `S3_BUCKET` | `fruition-storage` | 오브젝트 스토리지 버킷명 |
| `PROCESSING_ENDPOINT` | `http://localhost:8001/process` | FastAPI 문서 처리 파이프라인 엔드포인트 |
| `OPENAI_API_KEY` | (없음) | OpenAI API 키 — LLM 기능 사용 시 필요 |
| `LLM_PROVIDER` | `openai` | LLM 프로바이더 |
| `LLM_MODEL` | `gpt-4.1-mini` | 사용할 LLM 모델명 |

---

## API

### Swagger UI

서버 실행 후 브라우저에서 전체 API 명세를 확인할 수 있습니다.

```
http://localhost:8080/swagger-ui.html
```

### 엔드포인트 목록

#### Documents

| 메서드 | 경로 | 설명 | 상태 |
|---|---|---|---|
| `POST` | `/api/documents` | 문서 업로드 (PDF/Markdown) | 구현 완료 |
| `GET` | `/api/documents` | 문서 목록 조회 | 구현 완료 |
| `GET` | `/api/documents/{document_id}` | 문서 상세 조회 | 구현 완료 |
| `PATCH` | `/api/documents/{document_id}/status` | FastAPI 콜백 — 처리 상태 업데이트 | 구현 완료 |

#### Wiki

| 메서드 | 경로 | 설명 | 상태 |
|---|---|---|---|
| `GET` | `/api/wiki/graph` | Wiki 그래프 전체 조회 | 스텁 (빈 목록 반환) |
| `GET` | `/api/wiki/pages/{wiki_page_id}` | Wiki 페이지 상세 조회 | 스텁 (501) |

#### Query

| 메서드 | 경로 | 설명 | 상태 |
|---|---|---|---|
| `POST` | `/api/query` | 자연어 질의응답 | 스텁 (501) |

#### Chat

| 메서드 | 경로 | 설명 | 상태 |
|---|---|---|---|
| `GET` | `/api/chat/messages` | 채팅 메시지 목록 조회 | 스텁 (빈 목록 반환) |

---

### 공통 에러 응답 형식

```json
{
  "error": {
    "code": "에러_코드",
    "message": "에러 메시지",
    "details": [
      { "field": "필드명", "reason": "오류 사유" }
    ]
  }
}
```

`details`는 입력 유효성 검사 오류(`400 INVALID_REQUEST`)일 때만 포함됩니다.

---

### POST /api/documents — 문서 업로드

**요청**

```
POST /api/documents
Content-Type: multipart/form-data
```

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `file` | MultipartFile | O | 업로드할 파일 (PDF 또는 Markdown) |

허용 형식: `application/pdf`, `text/markdown` / `text/x-markdown` (`.md`)

```bash
curl -X POST http://localhost:8080/api/documents \
  -F "file=@/path/to/document.pdf"
```

**응답 (201 Created)**

```json
{
  "id": "doc_a1b2c3d4",
  "filename": "document.pdf",
  "mime_type": "application/pdf",
  "byte_size": 204800,
  "status": "processing",
  "source_uri": "sources/documents/doc_a1b2c3d4/original",
  "uploaded_at": "2026-06-10T07:00:00Z"
}
```

| HTTP 상태 | 코드 | 설명 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 파일이 없거나 비어 있음 |
| 409 | `DOCUMENT_ALREADY_EXISTS` | 동일 파일이 이미 업로드됨 (SHA-256 기준) |
| 415 | `UNSUPPORTED_FILE_TYPE` | PDF, Markdown 외 파일 형식 |
| 500 | `INTERNAL_SERVER_ERROR` | 서버 처리 오류 |

---

### PATCH /api/documents/{document_id}/status — FastAPI 콜백

FastAPI 파이프라인이 문서 처리 단계마다 Spring 서버로 호출하는 콜백 엔드포인트입니다.

**요청**

```
PATCH /api/documents/{document_id}/status
Content-Type: application/json
```

```json
{
  "status": "completed",
  "extracted_text_uri": "sources/documents/doc_a1b2c3d4/extracted.txt",
  "processed_at": "2026-06-10T07:05:00Z",
  "error_message": null
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `status` | `processing` \| `completed` \| `failed` | O | 처리 상태 |
| `extracted_text_uri` | string | X | 추출된 텍스트 Object Storage 경로 |
| `processed_at` | ISO 8601 | X | 처리 완료 시각 |
| `error_message` | string | X | 실패 사유 (`status=failed` 시 사용) |

**응답 (204 No Content)**

| HTTP 상태 | 코드 | 설명 |
|---|---|---|
| 204 | — | 상태 업데이트 성공 |
| 400 | `INVALID_REQUEST` | 요청 형식 오류 |
| 404 | `DOCUMENT_NOT_FOUND` | 문서 ID 없음 |

---

### FastAPI 연동 흐름

```text
[Spring] POST /api/documents
  → MinIO에 원본 파일 저장 (sources/documents/{id}/original)
  → documents 레코드 생성 (status=processing)
  → 사용자에게 201 응답
  → [비동기] FastAPI에 POST {PROCESSING_ENDPOINT}
        body: { "document_id": "...", "source_uri": "..." }

[FastAPI] MinIO에서 파일 직접 fetch → 처리 단계마다:
  → [Spring] PATCH /api/documents/{id}/status
        body: { "status": "...", "extracted_text_uri": "...", ... }
  → documents 레코드 상태 업데이트 (JPA dirty checking)
```

---

## 패키지 구조

```
backend/src/main/java/fruition/poc/backend/
├── BackendApplication.java
├── common/
│   ├── ErrorResponse.java               # 공통 에러 응답 (field 오류 목록 포함)
│   └── GlobalExceptionHandler.java      # 전역 예외 처리 (validation, 404 등)
├── config/
│   ├── MinioConfig.java                 # MinIO 클라이언트 빈
│   ├── OpenApiConfig.java               # Swagger 설정
│   └── StorageProperties.java           # 스토리지 설정값
├── document/
│   ├── api/
│   │   └── DocumentController.java      # Documents REST 엔드포인트 (4개)
│   ├── application/
│   │   └── DocumentService.java         # 업로드 / 조회 / 상태 업데이트 로직
│   ├── domain/
│   │   ├── Document.java                # JPA 엔티티
│   │   ├── DocumentNotFoundException.java
│   │   ├── DocumentStatus.java          # enum: processing / completed / failed
│   │   ├── DocumentUploadException.java
│   │   └── DuplicateDocumentException.java
│   ├── dto/
│   │   ├── DocumentDetailResponse.java  # 문서 상세 응답 (wiki_pages 포함)
│   │   ├── DocumentListResponse.java    # 문서 목록 응답
│   │   ├── DocumentStatusUpdateRequest.java  # FastAPI 콜백 요청 DTO
│   │   ├── DocumentUploadResponse.java  # 업로드 응답
│   │   └── DocumentWikiPageRef.java     # 연결된 Wiki 페이지 참조
│   └── infra/
│       ├── DocumentProcessingRequester.java  # FastAPI 처리 요청 (RestClient)
│       └── DocumentRepository.java           # JPA Repository
├── wiki/
│   ├── api/
│   │   └── WikiController.java          # Wiki 엔드포인트 스텁
│   └── dto/
│       ├── WikiGraphEdge.java
│       ├── WikiGraphNode.java
│       ├── WikiGraphResponse.java
│       ├── WikiPageDetailResponse.java
│       ├── WikiPageSourceDoc.java
│       └── WikiRelatedPage.java
├── query/
│   ├── api/
│   │   └── QueryController.java         # Query 엔드포인트 스텁
│   └── dto/
│       ├── HighlightedPath.java
│       ├── QueryRelatedPage.java
│       ├── QueryRequest.java
│       ├── QueryResponse.java
│       └── SourceReference.java
└── chat/
    ├── api/
    │   └── ChatController.java          # Chat 엔드포인트 스텁
    └── dto/
        ├── ChatMessageReference.java
        ├── ChatMessageResponse.java
        └── ChatMessagesResponse.java
```

---

## 자주 겪는 문제

**PostgreSQL 연결 실패**

Docker 컨테이너가 완전히 기동되기 전에 서버를 실행하면 연결 오류가 발생합니다.
`docker compose ps` 로 `postgresql` 컨테이너 상태가 `healthy`인지 확인 후 서버를 다시 기동하세요.

**MinIO 버킷 없음 오류**

`minio-init` 컨테이너가 버킷을 자동 생성합니다. 컨테이너 로그를 확인하세요.

```bash
docker logs fruition-minio-init-dev
```

**포트 충돌**

로컬에서 PostgreSQL이나 MinIO가 이미 실행 중이라면 포트가 충돌합니다.
기존 프로세스를 종료하거나 `infra/docker-compose.dev.yml`의 포트 매핑을 변경하세요.

**FastAPI 처리 요청 실패**

`PROCESSING_ENDPOINT`에 FastAPI 서버가 실행 중이지 않아도 업로드 응답(201)은 정상 반환됩니다.
실패 시 서버 로그에 `[처리 요청 실패]` 경고가 기록됩니다.
