# Changelog — Backend

Spring Boot 백엔드 변경 이력입니다. 날짜 역순으로 기록합니다.

---

## [Unreleased] — feat/backend-api

현재 작업 중인 브랜치입니다.

---

## 2026-06-10

### feat: FastAPI 콜백 패턴 기반 문서 처리 상태 업데이트 (`0595937`)

**배경**

문서 업로드 후 FastAPI 파이프라인이 비동기로 파일을 처리하며, 단계마다 Spring 서버에 진행 상태를 알려야 합니다. 업로드 응답은 즉시 반환하되, 처리 진행 상황은 콜백으로 수신하는 패턴을 적용했습니다.

**추가된 것**

- `PATCH /api/documents/{document_id}/status` — FastAPI 파이프라인 콜백 수신 엔드포인트
- `DocumentStatusUpdateRequest` — 콜백 요청 DTO (`status` 필수, `extracted_text_uri` / `processed_at` / `error_message` 선택)
- `Document.updateStatus()` — JPA dirty checking으로 상태 필드 갱신 (별도 save 호출 불필요)
- `DocumentService.updateStatus()` — `@Transactional` 트랜잭션 내 상태 업데이트
- `DocumentProcessingRequester` — 업로드 직후 FastAPI에 `document_id` + `source_uri` POST, 성공/실패 응답 로깅
- `application.properties` — `app.processing.endpoint` 환경변수 추가 (기본값: `http://localhost:8001/process`)

**FastAPI 측 연동 스펙**

```
POST {PROCESSING_ENDPOINT}
Content-Type: application/json

{ "document_id": "doc_abc12345", "source_uri": "sources/documents/doc_abc12345/original" }
```

응답은 `{ "document_id": "...", "status": "..." }` 형태를 기대하며, 실패 시 서버 로그에 경고만 기록하고 업로드 응답에는 영향을 주지 않습니다.

---

### feat: MVP API 컨트롤러 및 Swagger 구성 추가 (`5fceb59`)

**배경**

`docs/Fruition_MVP_API_Contract.md` 명세 기준으로 7개 엔드포인트의 컨트롤러와 Swagger 어노테이션을 구성했습니다. Wiki / Query / Chat 도메인은 프론트엔드 연동 준비를 위해 스텁으로 먼저 노출합니다.

**추가된 것**

- `GET /api/documents` — 문서 목록 조회 (상태 polling용)
- `GET /api/documents/{document_id}` — 문서 상세 조회 (연결된 Wiki 페이지 목록 포함, 현재 빈 목록)
- `GET /api/wiki/graph` — Wiki 그래프 전체 조회 스텁 (빈 nodes/edges 반환)
- `GET /api/wiki/pages/{wiki_page_id}` — Wiki 페이지 상세 스텁 (501)
- `POST /api/query` — 자연어 질의응답 스텁 (501)
- `GET /api/chat/messages` — 채팅 메시지 목록 스텁 (빈 목록 반환)
- 전체 엔드포인트에 `@Tag`, `@Operation`, `@ApiResponse` Swagger 어노테이션 적용
- `GlobalExceptionHandler` — `@Valid` 입력 검증 오류(필드별 오류 목록) 및 `DocumentNotFoundException` 처리 추가
- `ErrorResponse` — `details` 필드(필드 오류 목록) 추가, `@JsonInclude(NON_NULL)` 적용
- `DocumentNotFoundException` — 도메인 예외 신규 추가

---

## 2026-06-09

### feat: 문서 업로드 Service / Repository 구현 (`16ce32b` ~ `c19a081`)

**추가된 것**

- `DocumentService.upload()` — SHA-256 중복 확인 → MinIO 저장 → DB 레코드 생성 → 처리 요청 순서로 구현
- `DocumentRepository` — Spring Data JPA, `findByContentHash` 포함
- `Document` JPA 엔티티 — `id`, `filename`, `mime_type`, `byte_size`, `status`, `source_uri`, `extracted_text_uri`, `content_hash`, `uploaded_at`, `processed_at`, `error_message` 필드
- `DocumentStatus` enum — `processing`, `completed`, `failed`
- `GlobalExceptionHandler` — `DuplicateDocumentException` (409), `DocumentUploadException` (500) 처리

---

## 2026-06-07

### feat: Spring Boot 백엔드 초기 세팅 (`7156846`)

**추가된 것**

- Spring Boot 3.5.x 프로젝트 초기화 (Java 21, Gradle)
- 의존성: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `postgresql`, `minio`, `springdoc-openapi-starter-webmvc-ui`
- `DocumentController` 업로드 스텁 (`POST /api/documents`)
- `MinioConfig`, `OpenApiConfig`, `StorageProperties` 기본 설정

---

*커밋 단위 이력은 `git log` 로 확인하세요.*
