# Fruition Pipeline API

Spring 백엔드가 업로드한 문서를 받아 Solar Pro 2 기반 Wiki 생성 파이프라인을 실행하는 FastAPI 워커입니다.

## Docker Compose 실행

저장소 루트에서 실행합니다.

```bash
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml -f infra/docker-compose.pipeline.yml up -d --build
```

호스트에서 접근할 때:

```text
http://localhost:8000
```

같은 compose 네트워크 안의 다른 컨테이너에서 접근할 때:

```text
http://pipeline-api:8000
```

Spring Boot를 호스트에서 `./gradlew bootRun`으로 실행 중이면 Spring은 아래 주소로 호출하면 됩니다.

```text
http://localhost:8000
```

Spring도 나중에 compose 안에서 실행하면 Spring 컨테이너는 아래 주소로 호출하면 됩니다.

```text
http://pipeline-api:8000
```

## 필요한 환경변수

`infra/.env`에 아래 값을 추가합니다.

```env
UPSTAGE_API_KEY=up_...
UPSTAGE_MODEL=solar-pro2
PIPELINE_API_PORT=8000
```

`docker-compose.pipeline.yml`은 기존 `docker-compose.dev.yml`의 `postgresql`, `minio` 서비스에 붙도록 설정되어 있습니다.

컨테이너 내부 연결값:

```text
DATABASE_URL=postgresql://...@postgresql:5432/...
S3_ENDPOINT=http://minio:9000
```

## 엔드포인트

```text
POST /admin/init-db
GET  /documents/{document_id}
POST /pipeline/runs
GET  /pipeline/runs/{run_id}
GET  /pipeline/runs/{run_id}/logs
```

`POST /documents`는 없습니다. 문서 업로드와 `documents` row 생성은 Spring이 담당합니다.

## Spring 연동 흐름

합의한 흐름은 아래와 같습니다.

```text
Spring이 문서 업로드를 받음
  -> Spring이 원본 파일을 MinIO에 저장
  -> Spring이 PostgreSQL documents row 생성
  -> Spring이 pipeline-api에 document_id 전달
  -> pipeline-api가 documents.source_uri 또는 documents.extracted_text_uri 조회
  -> pipeline-api가 MinIO에서 Markdown/text 입력을 읽음
  -> pipeline-api가 Solar Pro 2 파이프라인 실행
  -> pipeline-api가 wiki_pages / document_wiki_links / wiki_page_links 저장
  -> pipeline-api가 documents.status와 pipeline_runs 갱신
  -> log_callback_url이 있으면 진행 로그를 Spring으로 POST
```

파이프라인 API는 `documents` row를 만들지 않습니다. `documents`의 소유자는 Spring입니다.

Markdown 문서는 `documents.source_uri`가 업로드된 Markdown object를 바로 가리키면 됩니다.

PDF나 binary 문서는 파이프라인 실행 전에 Spring 또는 converter worker가 추출된 Markdown/text를 만들고, 그 object key를 `documents.extracted_text_uri`에 저장해야 합니다.

## DB 정합성

### Spring이 소유하는 테이블

```text
documents
  id
  filename
  mime_type
  byte_size
  status
  source_uri
  extracted_text_uri
  content_hash
  uploaded_at
  processed_at
  error_message
```

파이프라인 API는 이 테이블을 조회하고 상태만 갱신합니다.

`POST /pipeline/runs`가 `document_id`를 받으면 입력은 아래 규칙으로 결정합니다.

```text
extracted_text_uri가 있으면:
  MinIO에서 extracted_text_uri를 읽음
아니고 Markdown/text 문서이면:
  MinIO에서 source_uri를 읽음
그 외 PDF/binary 문서이면:
  extracted_text_uri가 필요하므로 409로 거절
```

### 파이프라인이 저장하는 테이블

```text
pipeline_runs
  실행 상태, manifest, error, 로그/출력 위치

wiki_pages
  source page / concept page 메타데이터

document_wiki_links
  document -> source/concept page 관계

wiki_page_links
  source/concept page 간 graph edge 관계
```

### 성공 시 저장되는 값

```text
documents.status = completed
documents.processed_at = now()
documents.error_message = null

pipeline_runs.status = succeeded
pipeline_runs.manifest = {...}

wiki_pages:
  source:{document_id}
  concept:{concept_slug}

document_wiki_links:
  document_id -> source:{document_id}, relation_type=source_of
  document_id -> concept:{concept_slug}, relation_type=extracted_concept

wiki_page_links:
  source_mentions_concept
  concept_related_to
```

중요한 정합성 규칙:

```text
추출된 concept은 전부 concept page markdown으로 생성합니다.
생성된 모든 concept page는 wiki_pages와 document_wiki_links에 저장합니다.
```

그래서 `normalized.json`의 concept ledger, `wiki/concepts/*.md`, `wiki_pages`, `document_wiki_links`가 같은 concept 집합을 바라봅니다.

링크 정합성 규칙:

```text
기존 DB에 있던 concept page와 이번 실행에서 새로 생성된 concept page를 모두 link target 후보로 봅니다.

source_mentions_concept:
  이번 문서 source page -> 이번 실행에서 추출/생성된 concept page

concept_related_to:
  이번 실행에서 생성된 concept page -> 이번 실행에서 생성된 concept page
  이번 실행에서 생성된 concept page -> 기존 DB에 이미 있던 concept page
```

기존 `wiki_page_links` row는 삭제하지 않습니다. 새로 감지된 링크는 upsert하고, 같은 `(from_page_id, to_page_id, link_type)`이 있으면 label/confidence/updated_at만 갱신합니다.

LLM이 related hint를 만들었더라도 target slug에 해당하는 `wiki_pages` row가 기존 DB나 이번 실행 결과에 없으면, DB에는 저장하지 않습니다. 그래프가 없는 node를 가리키지 않게 하기 위해서입니다.

### 실패 시 저장되는 값

```text
documents.status = failed
documents.processed_at = now()
documents.error_message = error

pipeline_runs.status = failed
pipeline_runs.error = error
```

## Source / Concept 저장 방식

source page:

```text
wiki_pages.id = source:{document_id}
wiki_pages.page_type = source
wiki_pages.slug = {document_id}
wiki_pages.markdown_uri = runs/.../wiki/sources/{document_id}.md
document_wiki_links.relation_type = source_of
```

concept page:

```text
wiki_pages.id = concept:{concept_slug}
wiki_pages.page_type = concept
wiki_pages.slug = {concept_slug}
wiki_pages.markdown_uri = runs/.../wiki/concepts/{concept_slug}.md
document_wiki_links.relation_type = extracted_concept
```

page 간 링크:

```text
wiki_page_links.from_page_id = source:{document_id} 또는 concept:{slug}
wiki_page_links.to_page_id = concept:{slug}
wiki_page_links.link_type = source_mentions_concept 또는 concept_related_to
```

현재 Markdown 파일은 pipeline container의 `/app/runs` volume 아래에 생성됩니다. DB의 `wiki_pages.markdown_uri`에는 이 경로가 저장됩니다. 나중에 Wiki Markdown을 MinIO로 옮기면 `markdown_uri`만 아래처럼 object key로 바꾸면 됩니다.

```text
wiki/sources/{document_id}.md
wiki/concepts/{concept_slug}.md
```

## 로그 콜백

Spring이 진행 로그를 직접 받고 싶으면 endpoint를 하나 열고, 파이프라인 요청에 `log_callback_url`을 넘깁니다.

예시:

```text
POST /internal/pipeline/logs
```

Spring이 호스트에서 실행 중이고 pipeline-api만 Docker에 있으면:

```json
{
  "log_callback_url": "http://host.docker.internal:8080/internal/pipeline/logs"
}
```

Spring도 compose 내부 서비스이면:

```json
{
  "log_callback_url": "http://spring-service-name:8080/internal/pipeline/logs"
}
```
