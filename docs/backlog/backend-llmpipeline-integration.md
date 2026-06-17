# 이전 자료

이 문서는 backend와 `llmPipeline`을 처음 연결하던 당시의 작업 기록입니다.
일부 해결해야 하는 이슈와 구현 상태가 현재 코드와 다를 수 있습니다.
현재 Query 연결 기준은 `docs/backend-llmpipeline-integration.md`를 확인합니다.

---

# Backend와 llmPipeline 연결 정리

## 목적

Spring Boot backend에서 문서를 업로드하면 Docker로 실행 중인 `llmPipeline` FastAPI 워커가 문서를 처리하고, 생성된 Wiki 결과를 PostgreSQL에 저장한 뒤 Spring API에서 조회할 수 있도록 연결했다.

## 실행 구성

로컬 개발 기준 실행 구성은 다음과 같다.

- Spring Boot backend: `http://localhost:8080`
- Pipeline API: `http://localhost:8000`
- PostgreSQL: `localhost:5432`
- MinIO API: `localhost:9000`
- MinIO Console: `http://localhost:9001`

Pipeline API는 `infra/docker-compose.pipeline.yml`의 `pipeline-api` 서비스로 실행한다. 해당 서비스는 `../llmPipeline`을 Docker build context로 사용하고, compose 내부의 `postgresql`, `minio` 서비스에 연결한다.

```bash
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml -f infra/docker-compose.pipeline.yml up -d --build
```

## 변경 내용

### backend

- 문서 업로드 후 처리 요청 endpoint 기본값을 `http://localhost:8000/pipeline/runs`로 변경했다.
- 기존 `{ document_id, source_uri }` 요청 대신 `llmPipeline`의 실제 API 계약에 맞춰 `{ "document_id": "..." }`만 전송하도록 변경했다.
- 업로드 트랜잭션이 커밋된 뒤 pipeline을 호출하도록 변경했다.
  - pipeline은 `documents` row를 직접 조회하므로, 커밋 전 호출하면 방금 업로드한 문서를 찾지 못할 수 있다.
- Spring `RestClient`의 HTTP request factory를 `SimpleClientHttpRequestFactory`로 고정했다.
  - 기본 HTTP 클라이언트에서 FastAPI/Uvicorn과 맞지 않는 upgrade 요청이 발생해 body가 비는 문제가 있었다.

### infra

- `infra/docker-compose.dev.yml`의 `minio-init` 실행 방식을 수정했다.
  - 기존 `entrypoint`와 `command` 조합이 일부 Docker Compose 환경에서 shell script로 전달되지 않아 bucket 생성이 실패했다.
  - 수정 후 `fruition-storage` bucket이 정상 생성된다.
- `infra/.env.example`에 pipeline 실행에 필요한 값을 추가했다.
  - `PROCESSING_ENDPOINT`
  - `PIPELINE_API_PORT`
  - `UPSTAGE_API_KEY`
  - `UPSTAGE_MODEL`
  - `UPSTAGE_BASE_URL`

### llmPipeline

- API 키 누락 시 `SystemExit` 대신 `RuntimeError`를 발생시키도록 변경했다.
  - FastAPI background task에서 오류를 잡아 `pipeline_runs.status=failed`, `documents.status=failed`로 저장할 수 있게 했다.
- pipeline 실패 시 컨테이너 로그에 `ERROR` 로그를 남기도록 변경했다.
- 실패 메시지는 DB 컬럼 길이를 넘지 않도록 truncate 처리했다.
- Spring JPA가 생성한 NOT NULL timestamp 컬럼과 맞도록 DB insert 시각을 명시했다.
  - `wiki_pages.created_at`, `wiki_pages.updated_at`
  - `document_wiki_links.created_at`
  - `wiki_page_links.created_at`, `wiki_page_links.updated_at`

## 검증 결과

### API 키 누락 검증

`UPSTAGE_API_KEY`가 비어 있는 상태에서 pipeline run을 실행하면 다음 동작을 확인했다.

- pipeline 컨테이너 로그에 `ERROR` 출력
- `pipeline_runs.status=failed`
- `documents.status=failed`
- `documents.error_message`에 API 키 누락 메시지 저장

예시 오류:

```text
ERROR: Missing API key. Set UPSTAGE_API_KEY=... or pass --api-key
```

### LLM 처리 검증

`infra/.env`에 `UPSTAGE_API_KEY`를 설정한 뒤 `docs/development.md`를 업로드해 end-to-end 흐름을 확인했다.

- 업로드 문서 ID: `doc_75fb1975`
- 성공 run ID: `4d78cc03-0d2a-4c82-a2d3-fe8da83de03b`
- pipeline 상태: `succeeded`
- 문서 상태: `completed`
- 생성된 Wiki node 수: 4
- 생성된 Wiki edge 수: 3
- 생성된 concept 수: 3

Spring API에서 다음 결과를 확인했다.

```text
GET /api/documents/doc_75fb1975
  status = completed

GET /api/wiki/graph
  nodes = source 1개 + concept 3개
  edges = source_mentions_concept 3개

GET /api/wiki/pages/source:doc_75fb1975
  source_documents 포함
  related_pages 포함

GET /api/wiki/pages/concept:fruition-mvp-infrastructure
  source_documents 포함
```

## 확인 가능한 URL

로컬 서버가 실행 중이면 아래에서 결과를 확인할 수 있다.

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Wiki graph API: `http://localhost:8080/api/wiki/graph`
- Source page 상세: `http://localhost:8080/api/wiki/pages/source:doc_75fb1975`
- Concept page 상세: `http://localhost:8080/api/wiki/pages/concept:fruition-mvp-infrastructure`
- Pipeline run 상태: `http://localhost:8000/pipeline/runs/4d78cc03-0d2a-4c82-a2d3-fe8da83de03b`
- Pipeline run 로그: `http://localhost:8000/pipeline/runs/4d78cc03-0d2a-4c82-a2d3-fe8da83de03b/logs`

## 해결해야 하는 이슈

- `GET /api/documents/{document_id}`의 `wiki_pages`가 아직 빈 배열이다.
  - `document_wiki_links`와 `wiki_pages` 데이터는 생성되지만 `DocumentService.findById()`에서 아직 연결 조회를 하지 않는다.
- `./gradlew test`가 기존 테스트 파일의 `TestcontainersConfiguration` 참조 문제로 실패한다.
  - 대상 파일: `backend/src/test/java/fruition/poc/BackendApplicationTests.java`
  - 대상 파일: `backend/src/test/java/fruition/poc/TestBackendApplication.java`
- LLM 결과 Markdown은 현재 pipeline 컨테이너의 `/app/runs` volume 경로를 `markdown_uri`로 저장한다.
  - 추후 Wiki Markdown을 MinIO에 저장하도록 바꾸면 Spring에서 컨테이너 내부 경로에 의존하지 않아도 된다.
- 이전 실패 검증 과정에서 생성된 `processing` 또는 `failed` 상태 문서가 로컬 DB에 남아 있다.
  - 필요하면 개발 DB 초기화 또는 테스트 데이터 정리 절차를 별도로 마련해야 한다.
