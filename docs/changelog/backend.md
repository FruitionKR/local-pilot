# Changelog — Backend

Spring Boot 백엔드 변경 이력입니다. 날짜 역순으로 기록합니다.

> AI/LLM/pipeline(llmPipeline) 변경 이력은 `docs/changelog/ai.md`를 참고하세요.

---

## 2026-06-29 (3)

### feat: 여러 문서 동시 업로드 시 pipeline 처리 순서 보장 — DB 기반 처리 큐 도입

**배경**

프론트에서 파일을 여러 개 동시에 업로드하면 각 업로드 요청이 병렬로 처리되어 pipeline run이 동시에 여러 개 실행됐습니다.
llmPipeline 내부에 전역 큐나 단일 worker 제한이 없어 처리 순서와 완료 순서가 보장되지 않았습니다.

**추가/변경된 것**

- `document_processing_queue` 테이블 추가. 컬럼: `id`, `document_id`(UNIQUE), `created_at`, `status`(`pending`|`processing`).
- `DocumentProcessingQueue` 엔티티 + `DocumentProcessingQueueRepository` 추가.
- `DocumentProcessingWorker` 추가. `@Scheduled(fixedDelay=2000)`로 `pending` 항목을 `created_at` 오름차순으로 하나씩 꺼내 pipeline 요청을 순차 실행합니다.
- `@PostConstruct`에서 서버 재시작 시 `processing` 상태로 stuck된 항목을 `pending`으로 리셋합니다.
- `DocumentService.requestProcessingAfterCommit()`이 pipeline을 즉시 호출하는 대신 queue에 INSERT하도록 변경했습니다.
- `DocumentService.delete()`가 문서 삭제 시 queue 항목도 함께 제거합니다.

**주의사항**

- 서버 인스턴스가 여러 개인 환경에서는 `pending → processing` 전환의 원자성을 보장하지 않습니다. 현재 로컬 단일 인스턴스 기준 구현입니다.

---

## 2026-06-29 (2)

### feat: 문서 삭제 API 추가 — DELETE /api/documents/{id}

**배경**

프론트의 삭제 메뉴가 로컬 tree 상태만 제거하고 backend API를 호출하지 않아 새로고침하면 문서가 다시 나타났습니다.

**추가/변경된 것**

- `DELETE /api/documents/{document_id}` endpoint 추가. 성공 시 `204 No Content`.
- 삭제 범위: source wiki page, MinIO 원본/추출 텍스트 오브젝트.
- DB CASCADE로 자동 처리: `source_blocks`, `document_wiki_links`, `wiki_page_links`, `wiki_page_embeddings`, `wiki_embedding_units`.
- concept wiki page는 여러 문서 공유 가능하므로 삭제 제외.
- MinIO 오브젝트 삭제는 DB commit 이후 실행. 실패 시 경고 로그만 남기고 204 반환.
- `processing` 상태 문서도 삭제 허용. 이후 pipeline callback은 404 무시.

---

## 2026-06-29

### feat: 문서 처리 상태 신뢰성 개선 — pipeline_run_id 추적 및 processing_state 추가

**배경**

`documents.status=processing`만으로는 pipeline worker가 실제로 살아서 처리 중인지 알 수 없었습니다.
pipeline 요청 실패, 장시간 무응답 상태를 구분할 방법이 없어 프론트엔드에서 신뢰도 있는 상태 표시가 불가능했습니다.

**추가/변경된 것**

- `Document` 엔티티에 `pipeline_run_id`, `processing_started_at`, `processing_updated_at` 필드를 추가했습니다.
- `markPipelineStarted()`, `markProcessingHeartbeat()`, `markProcessingFailed()` 메서드를 추가해 상태 변경을 엔티티에서 관리합니다.
- `DocumentProcessingRequester.request()`가 pipeline 요청 성공 시 `PipelineRunResponse`를 반환하고, 실패 시 예외를 throw하도록 변경했습니다. callback URL도 요청 body에 포함합니다.
- `DocumentService.doRequestProcessing()`을 분리해 pipeline 요청 성공 시 `pipeline_run_id`를 저장하고, 실패 시 `status=failed`로 즉시 기록합니다.
- `POST /api/documents/{document_id}/pipeline-events` endpoint를 추가했습니다. llmPipeline의 `PipelineLog.emit()`이 단계마다 이 URL로 POST하면 `processing_updated_at`이 갱신됩니다.
- `DocumentListResponse`, `DocumentDetailResponse`에 `pipeline_run_id`, `processing_state` 필드를 추가했습니다.
- `processing_state` 계산 규칙: `pipeline_run_id` 없으면 `starting`, heartbeat가 60초 이상 없으면 `stalled`, 그 외 `running`.
- `DocumentProcessingState` enum(`starting/running/stalled/completed/failed`)을 추가했습니다.

**주의사항**

- `spring.jpa.hibernate.ddl-auto=update`로 개발 환경에서는 컬럼이 자동 추가됩니다. 공유 DB 환경은 별도 migration 필요.
- llmPipeline의 `log_callback_url` 기능은 이미 구현되어 있어 callback URL을 전달하면 자동으로 heartbeat가 발송됩니다.

---

## 2026-06-20

### feat: Query run 비동기 처리 및 SSE 진행상황 중계 (POST /api/query/runs)

**배경**

기존 `POST /api/query`는 pipeline 처리가 끝날 때까지 응답을 기다리는 동기 방식이라, 사용자가 질의 처리 중 실제 진행 상황(`query_started`, `retrieval_scored` 등)을 확인할 수 없었습니다. `dev`에 먼저 병합된 pipeline 커밋이 `request_id`/`log_callback_url`을 받아 요청별로 동적 callback publisher를 생성하도록 지원하면서, 백엔드도 이 callback을 수신해 SSE로 중계할 구조가 필요했습니다.

**추가/변경된 것**

- `POST /api/query/runs` — query run 생성, 비동기로 pipeline 호출을 시작하고 즉시 `{request_id, status}` 반환 (202)
- `GET /api/query/runs/{requestId}/events` — SSE 구독. callback으로 들어온 event를 `sequence`/`received_at`과 함께 중계, heartbeat 전송
- `POST /api/query/runs/{requestId}/events/callback` — pipeline → backend event 수신
- `GET /api/query/runs/{requestId}` — 상태/최종 결과 조회 (SSE 연결 실패 시 polling fallback)
- `query/domain/QueryRun`, `QueryRunStatus` — 진행 상태 추적용 immutable 도메인 모델 (상태 전이마다 새 인스턴스 반환)
- `query/service/QueryRunStore` — in-memory `ConcurrentHashMap` 기반 run 저장, 완료 후 10분 TTL 자동 정리
- `query/service/QueryEventBroker` — requestId별 `SseEmitter` 구독/버퍼/heartbeat/complete 관리
- `query/service/QueryRunService` — run 생성, 전용 executor로 비동기 실행 orchestration, 만료 run 정리 스케줄러
- `query/controller/QueryRunController`, `query/dto/QueryRunCreateResponse`, `QueryRunStatusResponse`, `PipelineEventCallbackRequest` 추가
- `query/exception/QueryRunNotFoundException` + `GlobalExceptionHandler` 404 핸들러 추가
- `QueryService.query()`, `PipelineQueryRequester.query()` — `requestId`/`logCallbackUrl` overload 추가. 기존 메서드는 새 overload에 위임만 하므로 **기존 `POST /api/query` 동기 경로는 요청/응답/저장 로직 100% 동일하게 유지**
- `application.properties`/`infra/.env.example` — `app.callback.base-url`/`CALLBACK_BASE_URL` 추가. backend가 docker-compose 서비스가 아니라 호스트에서 직접 실행되는 구조라 기본값을 `http://host.docker.internal:8080`으로 설정
- `infra/docker-compose.pipeline.yml` — `pipeline-api`에 `extra_hosts: host.docker.internal:host-gateway` 추가 (Docker Desktop 외 환경 호환)
- `query/config/QueryAsyncConfig` — `Clock`, 전용 `queryRunExecutor`(`ThreadPoolTaskExecutor`) bean 추가, `BackendApplication`에 `@EnableScheduling` 추가
- `docs/spec/backend-query-events-api.md`, `docs/issue/2026-06-20.md` 추가

**검증**

- `./gradlew test` 32개 전체 통과
- 로컬 전체 스택(Postgres/MinIO/pipeline-api/backend/frontend)을 직접 기동해 `POST /api/query/runs` → SSE 구독 → `query_started`~`answer_generated` 전체 단계 event를 순서대로(`sequence` 1~10) 실시간 수신 → `query.completed` 종료까지 end-to-end 확인
- 기존 `POST /api/query` 동기 경로, `GET /api/chat/messages` 저장 결과 회귀 없음 확인

---

## 2026-06-19

### feat: related_pages 별도 테이블 저장 및 채팅 조회 API 반영

**배경**

`chat_message_references`에 `related_pages`를 `reference_type`으로 섞어 저장하면 "근거 스니펫(quote)"과 "탐색된 Wiki 페이지 목록"이 하나의 테이블에 혼재됩니다. 두 개념은 쓰임새가 다르므로(`evidence_snippets` = 인용 근거, `related_pages` = "찾은 자료" 카드) 별도 테이블로 분리했습니다.

**추가/변경된 것**

- `ChatMessageRelatedPage` 엔티티 추가 (`chat_message_related_pages` 테이블)
- `ChatMessageRelatedPageRepository` 추가 (`findAllByChatMessageIdIn()` 배치 조회)
- `ChatMessageRelatedPageResponse` DTO 추가 (`wiki_page_id`, `page_type`, `title`, `slug`, `relevance_score`, `role`, `depth`, `rank`)
- `QueryService` — `relatedPageRepository` 주입, `buildRelatedPages()` 추가, `query()` 내 `relatedPageRepository.saveAll()` 호출
- `ChatMessageResponse` — `related_pages` 필드 추가 (`references` 앞)
- `ChatController` — `relatedPageRepository` 주입, `GET /api/chat/messages` 응답에 `related_pages` 포함 (배치 조회로 N+1 방지)
- `backend-mvp-erd.md` — `chat_message_related_pages` 테이블 및 관계 추가
- `QueryServiceTest` — `relatedPageRepository` mock 추가, `buildRelatedPages()` 저장 검증 추가

**검증**

- `./gradlew test --tests "fruition.query.service.QueryServiceTest"` 통과

---

### fix: buildReferences() 저장 전 reference 유효성 검증 추가

**배경**

`QueryService.buildReferences()`가 `pageId != null`인 evidence snippet을 조건 없이 저장했습니다.
이 때문에 DB에 없는 wiki_page를 참조하거나 원문 viewer에서 열 수 없는 항목이 "근거 자료"로 표시됐습니다.

**추가/변경된 것**

- `buildReferences()`에 3단계 필터 추가
  - `quote` 비공백: `snippet.text()`가 null이거나 blank면 제외
  - `wiki_pages` 존재: `WikiPageRepository.findAllById()` batch 조회 후 DB에 없는 pageId 제외
  - 원문 viewer 접근 가능: `markdownUri != null` 또는 `document_wiki_links` 연결이 있는 page만 저장
- `WikiPageRepository`, `DocumentWikiLinkRepository` 의존성 추가
- `QueryServiceTest` — 신규 의존성 Mock 추가 및 wiki page 스텁 설정

**검증**

- `./gradlew test --tests "fruition.query.service.QueryServiceTest"` 통과

---

### feat: 원본 문서 조회 API 구현 (GET /api/documents/{document_id}/original)

**배경**

프론트엔드에서 원본 문서를 클릭하면 MinIO에 저장된 raw 원본 파일이 아니라 source Wiki page를 열고 있었습니다. DB의 `documents.source_uri`를 기준으로 MinIO 객체를 스트리밍하는 엔드포인트가 없어 원본 파일을 직접 조회할 수 없었습니다.

**추가/변경된 것**

- `document/exception/DocumentOriginalNotFoundException` — document는 DB에 있지만 MinIO 객체가 없는 경우 전용 예외 추가. `DOCUMENT_NOT_FOUND`와 구분되는 `DOCUMENT_ORIGINAL_NOT_FOUND` 에러코드 반환
- `document/dto/DocumentOriginalResult` — service → controller 사이 전달용 record (`mimeType`, `filename`, `inputStream`)
- `DocumentService.getOriginal()` — `documents.source_uri`로 MinIO `getObject()` 호출 후 스트림 반환. `s3://` 형식과 plain object key 형식 모두 처리하는 `normalizeObjectKey()` 추가
- `DocumentController` — `GET /{document_id}/original` 엔드포인트 추가. `Content-Type`은 `document.mimeType` 기준, PDF/text는 `inline`, 그 외 `attachment`로 `Content-Disposition` 설정
- `GlobalExceptionHandler` — `DocumentOriginalNotFoundException` 핸들러 추가 (404, `DOCUMENT_ORIGINAL_NOT_FOUND`)

**검증**

- `./gradlew compileJava` 통과
- `QueryServiceTest` 통과

---

## 2026-06-16

### feat: Wiki page 상세 응답에 markdown 본문 포함

**배경**

프론트 원문 viewer에서 source/concept Wiki page의 실제 markdown 본문을 바로 렌더링해야 했습니다. 기존 상세 API는 `markdown_uri`만 제공해 프론트가 원문 내용을 표시할 수 없었습니다.

**추가/변경된 것**

- `WikiService.findById()` — `markdown_uri`가 가리키는 MinIO object를 읽어 `markdown` 필드로 함께 반환
- `s3://{bucket}/...` 형식과 object key 형식을 모두 처리하도록 object path 정규화 추가
- markdown 읽기 실패 시 상세 조회 자체는 유지하고 `markdown`만 비워두도록 처리

**검증**

- `./gradlew test` 통과.
- 로컬 API에서 `GET /api/wiki/pages/{wiki_page_id}` 응답에 source/concept markdown이 포함되는 것을 확인.

---

### feat: chat_messages 테이블에 error_message 컬럼 추가

**배경**
채팅 메시지 생성 시 발생하는 오류를 DB에서 직접 확인할 수 없었습니다.

**추가/변경된 것**
- `ChatMessage` 엔티티 — `error_message VARCHAR(255)` 필드 추가, 생성자에 `errorMessage` 파라미터 추가
- `ChatMessageResponse` DTO — `error_message` 응답 필드 추가 (null 시 응답에서 생략)
- `ChatController` — `ChatMessageResponse` 생성 시 `errorMessage` 매핑 추가
- `QueryService` — `ChatMessage` 생성 시 정상 흐름에서 `errorMessage=null` 전달
- `docs/spec/backend-mvp-erd.md` — `chat_messages` 테이블에 `error_message` 컬럼 반영

**검증 결과**
- 정상 흐름에서 `error_message`는 `null`로 저장되며 응답에서 생략됨

### feat: Spring 백엔드 Query API 구현 (POST /api/query)

**배경**
FastAPI 파이프라인이 제공하는 그래프 기반 자연어 질의응답을 Spring 백엔드에서 중계해야 했습니다. 기존 `QueryController`는 스텁이었으며, `QueryResponse` DTO가 pipeline 출력 형식과 불일치했습니다.

**추가/변경된 것**
- `query/service/QueryService` — FastAPI pipeline에 질의를 전달하고 응답을 변환하는 서비스 추가
- `query/repository/PipelineQueryRequester` — FastAPI `/query` 엔드포인트 HTTP 클라이언트
- `query/repository/PipelineQueryResponse` — pipeline 응답 역직렬화 DTO
- `query/exception/PipelineQueryException` — pipeline 오류 전파용 도메인 예외
- `QueryController` — `QueryService` 주입 및 스텁 제거
- `QueryResponse` — pipeline 출력 형식으로 재구성 (`HighlightedPath`, `QueryRelatedPage`, `SourceReference` DTO 제거)
- `application.properties` — `app.query.endpoint`, `app.query.timeout-seconds` 환경변수 추가
- `docs/backlog/backend-query-api.md` — Query API 구현 전 스펙 문서 추가

**주의사항**
FastAPI pipeline 주소는 `QUERY_ENDPOINT` 환경변수로 주입하며 기본값은 `http://localhost:8000/query`입니다.

---

### feat: Chat 기록 조회 API 구현 (GET /api/chat/messages)

**배경**
채팅 메시지 목록 API가 스텁으로 빈 배열을 반환하고 있었습니다. ChatMessage와 ChatMessageReference 도메인 모델을 구현해 실제 대화 이력을 반환하도록 교체했습니다.

**추가/변경된 것**
- `chat/domain/ChatMessage` — 채팅 메시지 JPA 엔티티
- `chat/domain/ChatMessageReference` — 메시지별 근거 참조 JPA 엔티티
- `chat/repository/ChatMessageRepository` — Spring Data JPA, 세션별 메시지 조회
- `chat/repository/ChatMessageReferenceRepository` — 메시지 ID 목록 기준 일괄 조회 (N+1 방지)
- `ChatController` — `ChatMessageRepository` / `ChatMessageReferenceRepository` 주입, 실제 데이터 반환
- `ChatMessageReference` DTO — `pageRole`, `rank`, `sentenceIndex` 필드 추가

---

### feat: 문서 이름 변경 API 구현 (PATCH /api/documents/{document_id}/rename)

**배경**
`docs/Fruition_MVP_API_Contract.md` 명세에 정의된 문서 이름 변경 API가 구현되지 않았습니다. `sync_source_title=true`이면 연결된 source WikiPage 제목도 함께 동기화합니다.

**추가/변경된 것**
- `document/dto/DocumentRenameRequest` — `filename`, `sync_source_title` 요청 DTO
- `document/dto/DocumentRenameResponse` — 이전 파일명, source page ref(`id`, `title`, `renamed`) 포함 응답 DTO
- `document/exception/InvalidDocumentFilenameException` — 파일명 검증 실패 예외 (400)
- `Document.rename()` — 파일명 변경 도메인 메서드 추가
- `DocumentService.rename()` — 파일명 검증(1~255자, 경로 구분자 금지), source_of 링크 탐색 후 WikiPage 제목 동기화, 응답 생성
- `DocumentController` — `PATCH /{document_id}/rename` 엔드포인트 추가

---

### feat: Wiki graph source doc 참조 및 Wiki page 이름 변경 API 구현

**배경**
Wiki graph 조회 시 source 타입 노드에 원본 문서 참조가 표시되지 않는 이슈가 있었습니다. 또한 `docs/Fruition_MVP_API_Contract.md` 명세의 Wiki page 이름 변경 API가 구현되지 않았습니다.

**추가/변경된 것**
- `DocumentWikiLinkRepository.findAllByIdWikiPageIdIn()` — graph 조회 시 source 페이지 일괄 조회 (N+1 방지)
- `WikiService.buildSourceDocRefs()` — source 타입 WikiPage에 연결된 원본 문서 참조를 WikiGraphNode에 포함
- `wiki/dto/WikiPageRenameRequest` — `title`, `update_slug` 요청 DTO
- `wiki/dto/WikiPageRenameResponse` — 이전 제목/slug, slug 업데이트 여부 포함 응답 DTO
- `wiki/exception/InvalidWikiPageTitleException` — 제목 검증 실패 예외 (400)
- `wiki/exception/WikiPageSlugConflictException` — slug 중복 예외 (409)
- `WikiPage.renameTitle()`, `WikiPage.updateSlug()` — 제목/slug 변경 도메인 메서드 추가
- `WikiService.rename()` — 제목 검증, slug 재생성(`update_slug=true` 시), `page_type+slug` 중복 검사
- `WikiController` — `PATCH /pages/{wiki_page_id}/rename` 엔드포인트 추가
- `GlobalExceptionHandler` — `PipelineQueryException`, `InvalidDocumentFilenameException`, `InvalidWikiPageTitleException`, `WikiPageSlugConflictException` 핸들러 추가

**주의사항**
slug 재생성 시 소문자 변환, 공백→하이픈, 한글 유지, 연속 하이픈 정리를 적용합니다. 같은 `page_type+slug` 조합이 이미 존재하면 409로 응답합니다.

---

## 2026-06-11

### feat: Wiki 도메인 서비스 구현

**배경**

Wiki 그래프 조회 및 페이지 상세 조회 엔드포인트가 스텁으로 노출되어 있었습니다. Wiki 도메인 모델·Repository·Service를 구현해 실제 데이터를 반환하도록 교체했습니다.

**추가된 것**

- `wiki/domain/WikiPage` — Wiki 페이지 JPA 엔티티 (`id`, `title`, `slug`, `summary`, `markdownUri`, `pageType`, `status`, `createdAt`, `updatedAt`)
- `wiki/domain/WikiPageType` — enum: `CONCEPT`, `PROCESS`, `ENTITY`, `OVERVIEW`
- `wiki/domain/WikiPageStatus` — enum: `active`, `draft`, `archived`
- `wiki/domain/WikiPageNotFoundException` — 도메인 예외 (404)
- `wiki/domain/WikiPageLink` — Wiki 페이지 간 링크 JPA 엔티티 (복합키: `fromPageId` + `toPageId`)
- `wiki/domain/WikiPageLinkId` — 복합키 클래스
- `wiki/domain/DocumentWikiLink` — 문서 ↔ Wiki 페이지 연결 JPA 엔티티 (복합키: `documentId` + `wikiPageId`)
- `wiki/domain/DocumentWikiLinkId` — 복합키 클래스
- `wiki/domain/DocumentWikiRelationType` — enum: `primary_source`, `supporting`, `referenced`
- `wiki/infra/WikiPageRepository` — Spring Data JPA Repository
- `wiki/infra/WikiPageLinkRepository` — `findAllByIdFromPageId` 포함
- `wiki/infra/DocumentWikiLinkRepository` — `findAllByIdWikiPageId` 포함
- `wiki/application/WikiService` — `findGraph()` / `findById()` 구현
  - `findGraph()`: 전체 WikiPage + WikiPageLink를 nodes/edges로 변환
  - `findById()`: 페이지 조회 → 연결 문서(`source_documents`) + 연결 페이지(`related_pages`) 조합

**변경된 것**

- `WikiController` — 스텁 제거, `WikiService` 주입 및 실제 서비스 호출로 교체
- `GlobalExceptionHandler` — `WikiPageNotFoundException` 핸들러 추가 (404 `WIKI_PAGE_NOT_FOUND`)

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
