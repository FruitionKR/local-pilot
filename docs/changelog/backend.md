# Changelog — Backend

Spring Boot 백엔드 변경 이력입니다. 날짜 역순으로 기록합니다.

---

## 2026-06-16

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
- `docs/spec/backend-query-api.md` — Query API 스펙 문서 추가

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

## [Unreleased] — feat/backend-api

현재 작업 중인 브랜치입니다.

### refactor: Query graph 탐색과 답변 근거 표시 조정

**배경**

Query graph 탐색이 고정 점수 컷과 감쇠 기준을 함께 사용해, top source page에서 이어지는 상대적으로 유효한 후보가 절대 점수 때문에 제외될 수 있었습니다. 또한 답변 본문과 graph highlight 출력의 책임이 섞여 있어, 사용자가 어떤 문장이 어떤 근거에 기대는지 확인하기 어려웠습니다.

**변경된 것**

- 가장 유사도가 높은 source page 1개를 탐색 시작점으로 사용하도록 조정했습니다.
- 탐색 중 관측된 최고 유사도 기준 95% 미만 후보를 제외하고, 기존 고정 `min_node_score`/감쇠 종료 조건은 사용하지 않도록 변경했습니다.
- 답변 본문은 문장별 `[1]`, `[2]` 형태의 evidence rank 표식을 사용할 수 있도록 query prompt와 static fallback을 갱신했습니다.
- 답변 생성 context에서는 page URL/path id 같은 내부 경로 정보를 제거하고, URL은 `evidence_snippets` 메타데이터로만 유지하도록 정리했습니다.
- API 최종 출력의 `traversal_paths`는 전체 탐색 중간 경로가 아니라 답변 context에 사용하는 상위 path만 반환하도록 제한했습니다.
- 최고 유사도 점수가 0 이하이면 graph를 확장하지 않고 `no_relevant_seed`로 멈추도록 조정했습니다.
- 근거가 직접 답하지 못하는 질문에서는 일반 지식으로 답을 설명하지 않도록 query prompt 정책을 강화했습니다.
- `no_relevant_seed`일 때는 LLM이 외부 지식을 덧붙이지 못하도록 서버에서 고정 unsupported 답변으로 교체하도록 했습니다.
- graph traversal의 depth 제한을 제거하고, 상대 유사도 컷과 방문 node 점수 가드로 종료하도록 변경했습니다.
- `evidence_snippets`를 문단 단위가 아니라 문장 단위로 생성하고, `paragraph_index`/`sentence_index`를 응답에 포함하도록 확장했습니다.
- evidence 문장에서 Markdown heading, frontmatter, block ref, bullet prefix를 제거해 답변 citation이 실제 근거 문장 자체를 가리키도록 정리했습니다.
- 답변 문장별 citation marker가 빠지면 서버에서 fallback marker를 보정하되, 별도 `answer_citations` 응답 필드는 두지 않고 `answer`의 marker와 `evidence_snippets.rank`로 매칭하도록 정리했습니다.

**검증**

- `.\.venv-query\Scripts\python.exe -m unittest tests.modules.query.test_answer_query tests.modules.query.test_query_chat_answer_generator` 통과.
- `.\.venv-query\Scripts\python.exe -m unittest discover -s tests` 통과.
- WSL Docker `pipeline-api`에서 `QUERY_EMBEDDING_MODE=text-only` 상태로 `POST /query` 호출 성공.
- 미지원 질문은 `no_relevant_seed`, `traversal_paths=[]`, 고정 unsupported 답변으로 응답하는 것을 확인.
- 지원 질문 `LLM Wiki가 뭐야?`는 evidence marker `[1]`가 포함된 답변과 `evidence_snippets` rank를 반환하는 것을 확인.
- `.\.venv-query\Scripts\python.exe -m compileall app tests` 통과.
- WSL Docker `pipeline-api`에서 `retrieval_summary.max_depth=0`과 문장 단위 `evidence_snippets[].paragraph_index/sentence_index/text` 반환을 확인.
- WSL Docker `pipeline-api`에서 모든 답변 문장에 citation marker가 보정되고, 별도 `answer_citations` 없이 `answer`와 `evidence_snippets.rank`로 근거를 매칭할 수 있음을 확인.

---

### fix: Query embedding 검색의 text-only 실행 모드 추가

**배경**

로컬 Docker `pipeline-api` 플로우 테스트에서 기본 런타임이 `sentence-transformers`와 대형 `torch`/CUDA wheel을 설치해야 해 rebuild가 반복적으로 실패했습니다. Query 응답 플로우 자체를 검증할 때는 BGE-M3 embedding이 필수는 아니므로, 가벼운 lexical 검색 모드가 필요했습니다.

**변경된 것**

- `QUERY_EMBEDDING_MODE=text-only`이면 `StoredWikiPageEmbeddingSearch` 대신 BM25 기반 검색 점수를 embedding search 자리에 사용하도록 분기했습니다.
- 기본 모드는 기존과 같은 `bge-m3`로 유지해, 환경변수가 없으면 저장된 embedding/BGE-M3 경로를 사용합니다.

**검증**

- `.\.venv-query\Scripts\python.exe -m unittest tests.modules.query.test_answer_query tests.modules.query.test_query_chat_answer_generator` 통과.
- `.\.venv-query\Scripts\python.exe -m unittest discover -s tests` 통과.

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

## 2026-06-12

### refactor: llmPipeline 모듈 구조 정리

**배경**

기존 `fruition_lab` 패키지는 추출, 정규화, LLM 호출, DB 저장, Object Storage 접근이 flat package에 섞여 있어 `docs/python_convention.md`의 bounded context 구조와 맞지 않았습니다. Query Engine 확장 전에 Wiki 생성/수집 책임을 기능 단위 모듈로 분리했습니다.

**변경된 것**

- `app/modules/wiki_generation/` — source/concept page 생성, 정규화, LLM adapter, prompt 렌더링 책임으로 분리
- `app/modules/wiki_ingestion/` — PostgreSQL persistence, MinIO/S3 object storage, file IO 책임으로 분리
- `fruition_lab/` flat package 제거, 내부 import를 `app/modules/*` 경로로 일원화
- `run_lab.py`, `api.py`, query repository import를 새 bounded context 경로로 갱신
- `llmPipeline/README.md`의 모듈 설명을 새 구조 기준으로 갱신

**검증**

- `python -m unittest discover -s llmPipeline\tests`
- `python -m compileall llmPipeline\api.py llmPipeline\run_lab.py llmPipeline\app llmPipeline\tests`
- `api`, `run_lab` import 및 `/query`, `/health`, `/pipeline/runs` route 등록 확인

---

### feat: Wiki graph query engine 기반 추가

**배경**

기존 자연어 질의 응답은 단일 `highlighted_paths` 형태만으로는 source page, concept page, source-source 관계를 경로 단위로 표현하기 어렵습니다. Wiki graph를 질의 컨텍스트로 사용하기 위해 Python `llmPipeline`에 query bounded context를 먼저 구성했습니다.

**추가된 것**

- `llmPipeline/app/modules/query/` — domain/application/infrastructure/interfaces/http 레이어 기반 query 모듈 추가
- source-first retrieval, concept focus hint, `source_related_to` traversal 정책을 use case로 구현
- `POST /query` FastAPI route와 PostgreSQL wiki repository adapter 연결
- query engine 설계 문서 `docs/spec/query-engine.md` 추가
- fake port 기반 유닛 테스트로 concept hint backtracking, source-source traversal, depth limit, 빈 질문 검증 확인
- `BgeM3EmbeddingSearch`와 `Bm25Searcher`를 추가해 BGE-M3 vector similarity + BM25 lexical score 기반 hybrid retrieval 경로 구성
- query 단계별 Spring 콜백 로그(`QUERY_LOG_CALLBACK_URL`)와 Wiki Markdown 본문 기반 evidence context 구성 추가
- 답변 본문에서 context 밖 예시/비유를 만들지 않도록 query prompt 정책을 보강하고, `evidence_snippets`에 `page_slug`/`page_url`을 추가해 근거 문장별 Wiki page 링크 표시를 지원
- Wiki page 생성/저장 완료 후 별도 thread에서 BGE-M3 page embedding을 미리 생성해 `wiki_page_embeddings`에 저장하는 비동기 후처리 흐름 추가
- query retrieval이 저장된 `wiki_page_embeddings` vector를 우선 사용하고, 저장된 vector가 없는 page만 실시간 BGE-M3 계산으로 fallback하도록 변경
- query 답변 생성기를 `StaticAnswerGenerator`에서 Solar Pro 2 기본 query chat adapter로 전환하고, 기존 `UPSTAGE_*`/`LLM_*` 환경변수를 재사용하도록 구성

**검증**

- `python -m unittest discover -s llmPipeline\tests`
- `python -m compileall llmPipeline\api.py llmPipeline\app llmPipeline\tests`
- `api` import 후 `/query` route 등록 확인

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
