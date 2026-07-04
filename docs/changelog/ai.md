# Changelog — AI / Pipeline

llmPipeline(AI/LLM/pipeline) 변경 이력입니다. 날짜 역순으로 기록합니다.

---

## 2026-07-04

### refactor: Wiki schema section metadata 공통화

**배경**

schema fragment section 목록이 filter와 preview 렌더링 코드에 각각 정의되어 있어, 새 section을 추가할 때 한쪽만 갱신될 위험이 있었습니다.

**추가/변경된 것**

- `schema_sections.py`를 추가해 schema section field 이름과 preview title을 한 곳에서 관리하도록 했습니다.
- `filter_schema_fragments.py`와 `build_schema_preview.py`가 공통 section metadata를 사용하도록 정리했습니다.
- `SchemaFragments` 필드와 section metadata가 어긋나지 않도록 단위 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.wiki_schema.test_schema_filter llmPipeline.tests.modules.wiki_schema.test_schema_preview llmPipeline.tests.modules.wiki_schema.test_schema_sections` 통과.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules/wiki_schema` 통과.
- 관련 application 모듈 `py_compile` 통과.
- `git diff --check` 통과.

### refactor: Query event publish 보조 함수 분리

**배경**

query application 흐름에서 callback event publish 실패를 삼키고 본 흐름을 계속 진행하는 처리가 `AnswerQueryUseCase`와 `QueryWebAnswerBuilder`에 중복되어 있었습니다. 같은 실패 처리 정책을 한 곳에서 확인할 수 있도록 보조 함수로 분리했습니다.

**추가/변경된 것**

- `publish_query_event()`를 추가해 event publisher 없음/실패 시 조용히 반환하는 기존 정책을 공통화했습니다.
- `AnswerQueryUseCase`와 `QueryWebAnswerBuilder`의 `_publish()`는 새 보조 함수에 위임하도록 정리했습니다.
- event publish 성공/무시 동작을 검증하는 단위 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.query.test_answer_query llmPipeline.tests.modules.query.test_query_web_answer_builder llmPipeline.tests.modules.query.test_query_event` 통과.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules/query` 통과.
- 관련 application 모듈 `py_compile` 통과.
- `git diff --check` 통과.

### refactor: Query application 보조 책임 분리

**배경**

`AnswerQueryUseCase`가 질의 처리 orchestration 외에 대화 맥락 해석과 retrieval summary 조립까지 함께 담당해, query 흐름을 읽고 테스트하기 어려웠습니다. 외부 API 응답 계약은 유지하면서 순수 보조 로직만 분리했습니다.

**추가/변경된 것**

- 대화 맥락 기반 검색 질문 보강과 evidence 질문 선택 로직을 `conversation_context_resolver.py`로 분리했습니다.
- `RetrievalSummary` 조립을 `retrieval_summary.py`로 분리해 내부 답변과 web fallback 답변에서 같은 계산을 재사용하도록 했습니다.
- `AnswerQueryUseCase`와 `QueryWebAnswerBuilder`는 기존 흐름을 유지하고 새 보조 함수에 위임하도록 정리했습니다.
- 분리한 순수 로직에 단위 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.query.test_answer_query llmPipeline.tests.modules.query.test_query_web_answer_builder llmPipeline.tests.modules.query.test_conversation_context_resolver llmPipeline.tests.modules.query.test_retrieval_summary` 통과.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules/query` 통과.
- 관련 application 모듈 `py_compile` 통과.
- `git diff --check` 통과.

## 2026-07-02

### feat: query evidence에 다중 원문 source_refs 추가

**배경**

같은 answer citation rank가 여러 원문 문서 block을 동시에 참조할 수 있는데, 기존 `source_document_id` + `source_block_ids` 구조는 document id를 하나만 담을 수 있어 `doc_a:B0001`, `doc_b:B0008` 같은 전역 ref를 정확히 표현하기 어려웠습니다.

**추가/변경된 것**

- `llmPipeline` query evidence domain과 FastAPI 응답에 `source_refs` 배열을 추가했습니다.
- `doc_id:B0001` 전역 ref를 `{source_document_id, source_block_id}` 객체로 구조화해 evidence snippet에 포함합니다.
- 기존 `source_document_id`, `source_block_ids`는 첫 번째 문서 기준 호환 필드로 유지했습니다.
- Spring backend와 frontend의 `source_refs` 소비 작업은 `docs/issue/2026-07-02.md` 후속작업으로 분리했습니다.

**검증**

- `llmPipeline/.venv/bin/python -m pytest tests/modules/query` 통과.

**주의사항**

- 현재 Spring `/api/query`, `/api/chat/messages` 응답은 아직 `source_refs`를 저장/전달하지 않습니다. 이번 변경은 pipeline API 응답까지입니다.

### feat: 위키 클러스터 승격 lint 흐름 추가

**배경**

section/mention/evidence 후보가 ingest 시점에 너무 일찍 core concept으로 승격되거나, 반대로 active cluster에 쌓인 후보를 lint가 실제 page/link로 반영하지 못했습니다. 또한 source/concept page id를 `source:{id}` 같은 문자열 구조로 가정하면 workspace/user scope와 UUID 기반 page id 전환에 맞지 않았습니다.

**추가/변경된 것**

- `POST /pipeline/runs`에 `user_id`, `workspace_id`를 추가하고, 기존 concept index를 먼저 조회해 같은 concept 후보는 cluster 생성 대신 concept evidence 병합 후보로 처리하도록 변경했습니다.
- meaning cluster 정리본에서 `Summary`/`Observations`를 제거하고 `Evidence Claims`, `Core Relation Candidates`, `Promotion` 중심으로 유지하도록 조정했습니다.
- 새 cluster는 promotion candidate가 될 수 없고, 기존 active cluster에 근거가 누적된 경우에만 LLM 판단으로 promotion candidate가 되도록 prompt와 assembler를 보강했습니다.
- `POST /wiki/maintenance/lint`를 추가해 dry-run에서는 proposal만 조회하고, execute에서는 promotion concept page 생성/기존 concept 병합, active cluster archive 이동, materializable relation link 생성을 수행하도록 구현했습니다.
- lint가 새로 승격된 cluster 내부 relation뿐 아니라 `active.md` 전체 Core Relation Candidates를 처리하도록 확장했습니다.
- ref 없는 claim/promotion은 invalid로 분류해 materialization 대상에서 제외하도록 했습니다.
- `wiki_pages.id`를 opaque UUID 계열 id로 생성하고, page 중복 판단은 `(user_id, workspace_id, page_type, slug)` 기준으로 맞췄습니다.
- backend/frontend 후속 반영 항목을 `docs/issue/2026-07-02.md`에 정리했습니다.

**검증**

- `PYTHONPATH=llmPipeline llmPipeline/.venv/bin/python -m pytest llmPipeline/tests/modules/wiki_generation/test_source_extraction_artifact.py llmPipeline/tests/modules/wiki_ingestion/test_concept_index.py` 통과.
- Docker 재빌드 후 clean markdown 4개를 Upstage `solar-pro2`로 ingest/lint 재실행했습니다.
- dry-run lint에서 promotion 후보 2개, orphan ref 없음, invalid relation/promotion 없음 확인.
- execute lint에서 concept page 2개 생성, active promotion queue 제거, archive 이동, `anova-analysis uses_or_depends_on robust-design` link 1개 생성을 확인했습니다.

**주의사항**

- `related_evidence`는 core graph edge로 materialize하지 않습니다.
- lint execute는 `wiki_pages`, `wiki_page_links`, embedding unit/vector, MinIO `clusters/active.md`, `clusters/archived.md`, `logs/{yyyy-mm-dd}.md`를 변경할 수 있습니다.
- Spring backend와 frontend의 UUID page id, workspace/user scope, lint proxy, graph/detail 재동기화 반영은 후속 PR 대상입니다.

---

## 2026-07-01

### feat: LangGraph evaluator graph 모듈화와 Studio entry 추가

**배경**

Query evaluator loop가 `AnswerQueryUseCase` 내부에서 직접 LangGraph를 조립해 application layer가 LangGraph SDK에 의존하고, LangGraph Studio에서 graph 구조를 보기 어려웠습니다.

**추가/변경된 것**

- query evaluator retry 흐름을 `QueryEvaluatorGraphPort`와 `query_evaluator_flow.py`로 분리했습니다.
- 실제 LangGraph 실행 구현을 infrastructure의 `LangGraphQueryEvaluatorGraph`로 이동했습니다.
- LangGraph Studio/Agent Server용 `query_evaluator` graph entry와 `langgraph.json`을 추가했습니다.
- LangSmith tracing이 켜진 환경에서 query evaluator graph node와 LLM span을 확인할 수 있게 했습니다.

**검증**

- `llmPipeline/.venv/bin/python -m pytest tests/modules/query/test_answer_query.py tests/modules/query/test_query_evaluator_graph.py tests/modules/query/test_query_evaluator_studio_graph.py` 통과.
- `llmPipeline/.venv/bin/langgraph validate --config langgraph.json` 통과.
- 실제 `POST /pipeline/runs`, `POST /query` 실행 결과 LangSmith에서 `LangGraph`, `generate_answer`, `evaluate_answer`, `prepare_retry` trace 확인.

---

## 2026-06-26

### feat: 채팅 Wiki observation 생성과 평가 보정 루프 추가

**배경**

긴 채팅 원문을 source page로 변환할 때 멀티턴 지시어, chunk 경계, 중복 QA episode 때문에 검색용 source 구조가 깨질 수 있었습니다.

**추가/변경된 것**

- `semantic_extraction`에 `observations` 구조를 추가해 `qa_episode`, `follow_up`, `definition`, `comparison` 같은 검색 단위를 source page에 저장하도록 변경했습니다.
- query API에 `recent_conversation_summary`와 `reference_context`를 받아 멀티턴 질문의 검색 질의를 보강하도록 추가했습니다.
- source evidence 선택 시 `Core Concepts` 링크 섹션을 제외하고, bullet 단위 evidence와 observation을 우선 활용하도록 보정했습니다.
- wiki generation evaluator loop를 추가하고, `observation_missing_ref`, `broken_observation`, `duplicate_observation`을 감지해 명확한 observation 문제는 deterministic repair 후 재평가하도록 했습니다.
- `LLM_PROMPT_LOG_DIR` 환경변수 기반 LLM 요청/응답 로그 저장 옵션을 추가했습니다.

**검증**

- `PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.query.test_answer_query` 통과.
- `test_source_extraction_artifact` 직접 호출 검증 통과.
- 관련 `llmPipeline` Python 파일 `py_compile` 통과.
- 실험 run `/Users/jaehyeong/chat-wiki-source-lab/runs/chat-source-multiturn-context-repair-agent`에서 observation repair 후 evaluator `passed=true`, `overall=0.95` 확인.

### fix: query citation 번호를 실제 사용 근거 기준으로 재정렬

**배경**

pipeline이 evidence 후보 전체 순위로 citation rank를 부여하고, 답변 LLM은 그중 일부만 사용하면서 최종 답변에 `[1]`, `[3]`, `[5]`처럼 중간 번호가 비어 보일 수 있었습니다.

**추가/변경된 것**

- 답변 생성 후 실제 답변에 등장한 citation만 사용 순서대로 `[1]..[N]`으로 다시 매핑하도록 변경했습니다.
- 최종 `evidence_snippets`도 답변에 사용된 근거만 반환하고, 답변 본문의 citation 번호와 같은 `rank`를 갖도록 조정했습니다.
- 답변 citation 재매핑 회귀 테스트를 추가했습니다.

**검증**

- `python3 -m unittest tests.modules.query.test_answer_query` 통과.

---

## 2026-06-21

### feat: query evidence를 원본 source block 기준으로 변경

**배경**

기존 `evidence_snippets`는 Wiki page의 문장 위치(`page_id`, `paragraph_index`, `sentence_index`)를 기준으로 반환되어 답변 citation을 원본 문서 block 하이라이트로 연결하기 어려웠습니다.

**추가/변경된 것**

- `llmPipeline` query evidence 응답을 `rank`, `source_document_id`, `source_block_ids`, `text` 중심으로 변경했습니다.
- block citation(`[B0005]` 등)이 없는 Wiki 문장은 evidence 후보에서 제외했습니다.
- `source_blocks(document_id, block_id, text)` 테이블을 추가하고 pipeline 산출 block을 저장하도록 연결했습니다.
- source page의 `Categories`, `Core Concepts`, `Section Candidates`, `Mentions` 섹션을 별도 retrieval representation으로 점수 계산에 반영했습니다.
- Spring/Frontend 후속 반영 항목은 `docs/issue/2026-06-21.md`에 정리했습니다.

**검증**

- Docker `python:3.12-slim` 컨테이너에서 `pip install -q -r requirements.txt && python -m unittest discover` 실행 결과 22개 테스트가 통과했습니다.
- `git diff --check`를 통과했습니다.

---

## [Unreleased] — feat/backend-api

query engine 관련 pipeline 작업 브랜치입니다.

### feat: Query 검색 정제와 웹 검색 fallback 추가

**배경**

짧은 개념 질의에서 자연어 전체를 그대로 embedding query로 사용하면 concept page가 존재해도 source page 중심 검색에 묻히는 문제가 있었습니다. 또한 내부 Wiki 근거가 충분하지 않은 질문은 외부 출처를 찾아 근거 기반으로 답변할 fallback 경로가 필요했습니다.

**변경된 것**

- `RuleBasedQueryRewriter`를 추가해 한국어 조사 제거와 핵심 검색어 정제를 수행하도록 했습니다.
- concept title/slug/alias 직접 매치가 있으면 embedding 점수가 낮아도 focus concept으로 유지되도록 name match boost를 추가했습니다.
- direct concept match로 context에 추가된 concept에 대해 기존 `source_mentions_concept` edge를 찾아 `graph_context.edges`와 `traversal_paths`에 backfill하도록 했습니다.
- 내부 Wiki 최고 관련도가 `QUERY_MIN_INTERNAL_RELEVANCE_SCORE`보다 낮으면 `WebSearchPort`를 통해 웹 검색 fallback을 수행할 수 있도록 했습니다.
- Tavily 기반 `WebSearchPort` 구현을 추가하고, 결과를 기존 최종 응답 구조 안에서 `page_type=web`, `role=web_search_result`로 표현하도록 했습니다.
- `embedding_vector`가 `"-"`처럼 비정상 값이거나 dimension이 맞지 않을 때 해당 문서만 fallback scoring으로 넘기도록 방어 로직을 추가했습니다.
- Query representation hash 계산이 embedding 생성 경로와 맞도록 `.strip()`을 적용했습니다.
- 기존 최종 API 응답 구조(`answer`, `related_pages`, `evidence_snippets`, `graph_context`, `traversal_paths`)는 변경하지 않았습니다.

**검증**

- `.\.venv-query\Scripts\python.exe -m unittest tests.modules.query.test_answer_query tests.modules.query.test_stored_wiki_page_embedding_search` 통과.
- `.\.venv-query\Scripts\python.exe -m unittest discover -s tests\modules` 통과.
- 실험 환경에서 `소리꾼은 뭐야?` 질의가 `concept:sorikkun`을 `focus_concept`로 선택하고, `source:golden:06_culture_pansori -> concept:sorikkun` 경로를 `traversal_paths`에 반환하는 것을 확인했습니다.
- 실험 환경에서 내부 문서가 없는 최신성 질의가 Tavily web fallback으로 전환되는 것을 확인했습니다.

---

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

*커밋 단위 이력은 `git log` 로 확인하세요.*
