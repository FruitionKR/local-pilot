# Fruition LLM Pipeline

Spring 백엔드가 업로드한 Markdown/text 문서를 받아 LLM Wiki 산출물을 만드는 FastAPI 워커입니다. 기본 실행은 source page만 LLM으로 섹션 polish하고, concept page는 backend skeleton으로 조립합니다.

## 실행방법

### Docker Compose

저장소 루트에서 실행합니다.

```bash
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml -f infra/docker-compose.pipeline.yml up -d --build
```

호스트에서는 `http://localhost:8000`, 같은 compose 네트워크 안에서는 `http://pipeline-api:8000`으로 접근합니다.

필수 환경변수는 `infra/.env`에 둡니다.

```env
UPSTAGE_API_KEY=up_...
UPSTAGE_MODEL=solar-pro2
PIPELINE_API_PORT=8000
DATABASE_URL=postgresql://...@postgresql:5432/...
S3_ENDPOINT=http://minio:9000
```

### CLI 실행

`llmPipeline` 폴더에서 실행합니다.

```bash
python run_lab.py \
  --input examples/llm-wiki.md \
  --out runs/llm_wiki_demo \
  --mode api \
  --source-page-mode section-polish \
  --concept-page-mode skeleton \
  --env-file ../infra/.env
```

기본 정책:

- `source-page-mode=auto`: API 모드에서는 `section-polish`
- `concept-page-mode=auto`: `skeleton`
- refs는 `B0001` 같은 짧은 block id로 통일
- 중간 raw/debug JSON은 저장하지 않음
- 디버깅이 필요하면 `--save-debug-json` 사용

### FastAPI 실행

개발 환경에서 직접 띄울 때:

```bash
uvicorn api:app --host 0.0.0.0 --port 8000 --reload
```

주요 endpoint:

```text
GET  /health
POST /admin/init-db
GET  /documents/{document_id}
POST /pipeline/runs
GET  /pipeline/runs/{run_id}
GET  /pipeline/runs/{run_id}/logs
```

`POST /documents`는 없습니다. 문서 업로드와 `documents` row 생성은 Spring이 담당합니다.

## 코드파일별 하는 일

### `api.py`

FastAPI 서버입니다.

- `document_id`, `input_markdown`, `input_path` 중 하나를 받아 pipeline run 생성
- Spring/DB 문서 입력이면 MinIO에서 Markdown/text를 읽어 로컬 입력 파일로 materialize
- background task 또는 `wait=true` 동기 실행 지원
- 실행 결과를 `pipeline_runs`에 저장
- 진행 로그 조회 API 제공

### `run_lab.py`

CLI 엔트리포인트이자 파이프라인 오케스트레이터입니다.

- CLI/API 인자 해석
- `.env` 로드와 OpenAI-compatible API 설정
- block extraction, packet build, semantic extraction, normalize, concept resolution, page assembly 실행
- `pipeline.log`, `normalized.json`, `manifest.json`, `wiki/links.json`, `review_report.md` 생성
- `--save-debug-json`이 켜진 경우에만 raw LLM output과 packet/debug JSON 저장

### `app/modules/wiki_generation/infrastructure/extract.py`

Markdown 문서를 `SourceDocument`와 `SourceBlock` 목록으로 분해합니다.

- 문서 제목 추정
- `B0001` 같은 짧은 block id 생성
- DB/export용 `source_reference_id` 유지

### `app/modules/wiki_generation/infrastructure/packet.py`

block 목록을 LLM 입력 packet으로 나눕니다.

- `max_packet_chars` 기준 chunking
- `overlap_blocks` 적용
- LLM에는 짧은 `[B0001]` anchor만 전달

### `app/modules/wiki_generation/infrastructure/chat_completions_llm.py`

OpenAI-compatible chat completions client와 LLM stage wrapper입니다.

- semantic extraction
- concept resolution
- optional section polish
- legacy full concept page generation
- JSON/section polish output 파싱과 부분 복구

### `app/modules/wiki_generation/infrastructure/prompt_io.py`

LLM user prompt를 만듭니다.

- semantic extraction prompt
- concept resolution prompt
- source/concept section polish prompt
- legacy concept page prompt
- concept별 관련 source block 수집

### `app/modules/wiki_generation/infrastructure/normalize.py`

LLM semantic extraction 결과를 backend 구조로 정규화합니다.

- concept slug 정규화와 같은 slug 병합
- evidence unit 생성
- concept mention expansion
- missing related hint 수집
- refs를 짧은 `B0001` 형태로 검증/유지

### `app/modules/wiki_generation/infrastructure/concept_resolution.py`

concept 후보의 의미적 병합/링킹을 정규화하고 적용합니다.

- 새로 뽑힌 concept끼리 intra-batch 비교 결과 반영
- 기존 wiki concept와의 merge/link 반영
- missing related hint를 current/existing concept로 매핑
- evidence related slug 재매핑

### `app/modules/wiki_generation/infrastructure/assemble.py`

최종 wiki markdown과 graph/review 파일을 조립합니다.

- source page 생성
- concept page skeleton 생성
- optional concept section polish 반영
- source key point ref를 concept key point로 내려보냄
- 같은 evidence/source key point/LLM resolution 기반 related concept 생성
- `wiki/links.json`, `review_report.md` 생성

### `app/modules/wiki_ingestion/infrastructure/postgres_wiki_ingestion_repository.py`

PostgreSQL persistence 계층입니다.

- `documents`, `pipeline_runs`, `wiki_pages`, `document_wiki_links`, `wiki_page_links` 관리
- 성공 시 wiki page와 graph edge upsert
- 실패 시 document/pipeline status와 error 저장

### `app/modules/wiki_ingestion/infrastructure/object_storage.py`

MinIO/S3 compatible object storage에서 텍스트 객체를 읽습니다.

### `app/modules/wiki_generation/domain/entities.py`

파이프라인 공용 dataclass 모델입니다.

### `app/modules/wiki_ingestion/infrastructure/file_io.py`

파일/JSON/log 입출력 유틸리티입니다.

### `app/modules/wiki_generation/domain/text_utils.py`

slug, SHA1, 공백 정규화, 중복 제거 같은 문자열 유틸리티입니다.


### `prompts/*.system.md`

LLM stage별 system prompt입니다.

- `semantic_extraction.system.md`
- `concept_resolution.system.md`
- `section_polish.system.md`
- `concept_page_generation.system.md`

## 실행흐름

### 1. 입력 결정

CLI는 `--input` 파일을 사용합니다. FastAPI는 `document_id`, `input_markdown`, `input_path` 중 하나를 받아 입력 Markdown 경로를 준비합니다.

### 2. Block Extraction

`MarkdownBlockExtractor`가 문서를 block으로 나누고 `B0001` anchor를 붙입니다.

전달 데이터:

- `document`
- `blocks`

### 3. Packet Build

`SemanticPacketBuilder`가 block 목록을 LLM 입력 packet으로 나눕니다.

전달 데이터:

- `packets`

### 4. Semantic Extraction

LLM이 packet별로 다음 JSON을 반환합니다.

- `semantic_summary`
- `key_points`
- `concept_candidates`
- `evidence_claims`

### 5. Normalize

backend가 loose LLM output을 정규화합니다.

- concept ledger 생성
- evidence units 생성
- missing related hints 수집
- mention/display refs 계산

### 6. Concept Resolution

LLM이 concept 의미 관계를 판단합니다.

- current batch concept끼리 merge/link
- existing wiki concept와 merge/link
- missing related hint를 current/existing concept로 매핑

기존 wiki를 비교하려면 `--existing-wiki-dir`를 넘깁니다.

### 7. Source Section Polish

`source-page-mode=section-polish`이면 source summary/key points/title만 LLM이 다듬습니다. evidence는 polish하지 않습니다.

### 8. Source Page Assembly

backend가 source page markdown을 생성합니다.

파일명은 source title slug를 사용합니다.

```text
wiki/sources/{title-slug}.md
```

### 9. Concept Page Assembly

기본은 `concept-page-mode=skeleton`입니다.

- definition/why/evidence는 normalized data에서 조립
- key points는 source key point와 concept refs가 겹치는 항목을 가져옴
- related concepts는 shared evidence, shared source key point, LLM resolution 기반으로 채움

`concept-page-mode=section-polish`를 명시하면 concept별 LLM polish 호출이 추가됩니다.

### 10. Link/Review/Manifest

최종 산출물을 생성합니다.

```text
wiki/links.json
review_report.md
normalized.json
manifest.json
pipeline.log
```

FastAPI에서 `document_id`로 실행한 경우 성공 시 DB에도 저장합니다.

## 예시

### CLI로 `llm-wiki.md` 실행

```bash
python run_lab.py \
  --input examples/llm-wiki.md \
  --out runs/llm_wiki_latest \
  --mode api \
  --source-page-mode section-polish \
  --concept-page-mode skeleton \
  --env-file ../infra/.env
```

확인할 파일:

```text
runs/llm_wiki_latest/wiki/sources/*.md
runs/llm_wiki_latest/wiki/concepts/*.md
runs/llm_wiki_latest/wiki/links.json
runs/llm_wiki_latest/review_report.md
runs/llm_wiki_latest/manifest.json
```

### FastAPI에 inline Markdown으로 실행

```bash
curl -X POST http://localhost:8000/pipeline/runs \
  -H "Content-Type: application/json" \
  -d '{
    "input_markdown": "# LLM Wiki\n\nLLM이 지속 관리하는 위키...",
    "out": "runs/api_inline_llm_wiki",
    "mode": "api",
    "source_page_mode": "section-polish",
    "concept_page_mode": "skeleton",
    "wait": true
  }'
```

### Spring/DB 문서로 실행

```bash
curl -X POST http://localhost:8000/pipeline/runs \
  -H "Content-Type: application/json" \
  -d '{
    "document_id": "doc_123",
    "mode": "api",
    "source_page_mode": "section-polish",
    "concept_page_mode": "skeleton",
    "log_callback_url": "http://host.docker.internal:8080/internal/pipeline/logs"
  }'
```
