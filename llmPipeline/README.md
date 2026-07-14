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

LangGraph evaluator loop를 LangSmith에서 확인하려면 아래 값도 `infra/.env`에 설정합니다.

```env
LANGSMITH_TRACING=true
LANGSMITH_API_KEY=lsv2_...
LANGSMITH_PROJECT=local-pilot-dev
LANGSMITH_ENDPOINT=https://api.smith.langchain.com
QUERY_EVALUATOR_MODE=llm
QUERY_EVALUATOR_MAX_ATTEMPTS=2
```

현재 LLM 호출은 Upstage OpenAI-compatible API를 사용합니다. `LANGSMITH_TRACING=true`이면 LangGraph node 실행과 `upstage_chat_completions` LLM span이 LangSmith project에 기록됩니다. 실제 비밀값은 `infra/.env`에만 두고 커밋하지 않습니다.

LangSmith Cloud region은 계정을 만든 URL과 맞아야 합니다.

- US 기본값: `LANGSMITH_ENDPOINT=https://api.smith.langchain.com`
- APAC/Sydney: `LANGSMITH_ENDPOINT=https://apac.api.smith.langchain.com`
- EU: `LANGSMITH_ENDPOINT=https://eu.api.smith.langchain.com`
- AWS US: `LANGSMITH_ENDPOINT=https://aws.api.smith.langchain.com`

한국에서 새 workspace를 만들 수 있다면 APAC/Sydney가 가장 가까운 SaaS region입니다. 이미 `smith.langchain.com`에서 만든 기본 US workspace의 API key라면 US endpoint를 그대로 써야 합니다.

### LangGraph Studio에서 evaluator graph 보기

`langgraph.json`은 `llmPipeline/langgraph.json`에 있습니다. 이 설정은 `query_evaluator` graph를 Studio/Agent Server에 노출하고, 환경변수는 `../infra/.env`에서 읽습니다.

```bash
cd llmPipeline
./.venv/bin/python -m pip install -U "langgraph-cli[inmem]"
./.venv/bin/langgraph dev
```

Studio에서 `query_evaluator` graph를 열고 아래 형태의 입력으로 실행할 수 있습니다.

```json
{
  "question": "Query evaluator graph 구조는 어디서 실행되나요?",
  "resolved_retrieval_question": "Query evaluator graph 구조는 어디서 실행되나요?",
  "answer": "LangGraph evaluator graph는 infrastructure 모듈에서 실행됩니다. [1]",
  "stop_reason": "answer_context_selected",
  "max_attempts": 1,
  "related_pages": [
    {
      "id": "query-evaluator-graph",
      "page_type": "concept",
      "title": "Query Evaluator Graph",
      "role": "concept",
      "score": 0.95,
      "summary": "Query evaluator graph는 LangGraph infrastructure 모듈로 분리되어 실행된다."
    }
  ],
  "evidence_snippets": [
    {
      "rank": 1,
      "source_document_id": "local-graph-guide",
      "source_block_ids": ["graph-1"],
      "text": "Query evaluator graph는 application use case가 아니라 infrastructure의 LangGraphQueryEvaluatorGraph에서 실행된다."
    }
  ]
}
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
  --wiki-evaluation-loop \
  --max-eval-attempts 2 \
  --env-file ../infra/.env
```

기본 정책:

- `source-page-mode=auto`: API 모드에서는 `section-polish`
- `concept-page-mode=auto`: `skeleton`
- refs는 `B0001` 같은 짧은 block id로 통일
- 중간 raw/debug JSON은 저장하지 않음
- 디버깅이 필요하면 `--save-debug-json` 사용

### PDF 문서 복원 CLI

문서 복원 전용 Python 의존성을 설치하고 `llmPipeline` 폴더에서 모듈 CLI를 실행합니다. `tesseract`는 별도 시스템 명령으로 설치되어 있어야 하며, Paddle FormulaRecognition은 `paddleocr`이 설치된 환경에서 선택적으로 사용됩니다.

```bash
python -m pip install -r requirements-document-restoration.txt
python -m app.modules.document_restoration.interfaces.cli \
  --pdf-file /path/to/paper.pdf \
  --output-dir /path/to/output \
  --document-slug paper \
  --use-local-sllm \
  --use-local-vision
```

조립된 Markdown을 local-first evaluator로 검사하려면 다음 CLI를 사용합니다.

```bash
python -m app.modules.document_evaluation.interfaces.local_cli \
  --markdown-file /path/to/output/final/paper.restored.md \
  --pdf-file /path/to/paper.pdf \
  --output-file /path/to/output/final/paper.evaluation.json \
  --output-markdown-file /path/to/output/final/paper.evaluated.md \
  --output-report-file /path/to/output/final/paper.evaluator_report.md
```

외부 evaluator에 전달할 job만 만들 때는 `app.modules.document_evaluation.interfaces.cli`를 사용합니다. 외부 API 환경변수가 없으면 API를 호출하지 않고 `pending_external_evaluator` job을 생성합니다.

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
