# Changelog — AI / Pipeline

llmPipeline(AI/LLM/pipeline) 변경 이력입니다. 날짜 역순으로 기록합니다.

---

## 2026-07-18

### feat: Wiki ingest evaluator를 LangGraph로 전환

**배경**

Wiki ingest evaluator가 일반 Python loop로 실행되어 LangSmith에서 의미 추출·평가·보정·재시도 흐름을 graph node 단위로 확인하기 어려웠고, API 기본값이 비활성화되어 evaluator가 요청마다 명시적으로 켜져야 했습니다.

**변경된 것**

- Wiki ingest evaluator loop를 `semantic_generation`, `normalize`, `evaluate`, `repair`, `reevaluate`, `prepare_retry` LangGraph node로 전환
- production과 같은 topology builder를 사용하는 `wiki_ingest_evaluator` Studio graph entry 추가
- `POST /pipeline/runs`, `POST /chat-wiki/runs`, CLI의 evaluator loop를 기본 활성화하고 명시적 비활성화 옵션 유지
- Wiki evaluator의 `issues`는 반드시 재시도하고 선택적 제안은 `warnings`로 분리하도록 평가 계약 보강
- Wiki evaluator의 concept/evidence/source block target에 대해 target block 주변 문맥과 기존 target 항목만 전달하는 `replace/remove/add` patch 경로 추가
- deterministic observation repair를 raw semantic note에 반영하고 다시 normalize해 최상위 registry와 `semantic_notes`가 일치하도록 보강
- patch의 수정 path와 source anchor를 검증하고 실패 시 관련 packet 재생성, target 해석 실패 시 전체 재생성으로 fallback
- 평가 기록에 `retry_mode`와 성공한 `applied_patch_operations`를 남겨 evaluator feedback의 실제 반영 내역을 추적
- 실제 문서에 없는 source block target은 전체 재생성으로 처리하고, debug retry artifact에도 확정된 재시도 방식과 patch operation을 기록
- patch에서 evaluator target과 무관한 semantic note와 의미 항목은 그대로 유지하고, 최대 시도 후 남은 issue는 manifest의 `generation_evaluation_status=unresolved`로 기록
- Wiki evaluator 시도별 상세 결과를 pipeline manifest와 DB run manifest에 보관
- Query evaluator에 `revise_answer` route를 추가하고, actionable feedback이 있는 답변은 재생성·재평가하도록 변경
- Query 수정이 최대 시도 후에도 해결되지 않으면 검증되지 않은 답변 대신 unsupported 답변 반환
- `LANGSMITH_TRACING=true`이더라도 `LANGSMITH_API_KEY`가 없으면 graph 실행은 유지하고 tracing만 생략
- Query production·Studio graph에도 key 없는 tracing 차단을 적용해 LangSmith 경고와 네트워크 재시도를 방지
- LangGraph 로컬 실행 산출물인 `llmPipeline/.langgraph_api/`를 Git 추적 대상에서 제외

**검증**

- Wiki generation graph, target 기반 patch·fallback, API 계약, CLI 인자, LangSmith tracing guard 관련 테스트 통과

---

## 2026-07-16

### feat: PDF 복원 흐름과 평가 기록 개선

**배경**

표와 수식에 같은 Text SLLM 경로를 적용하면 표의 행·열 관계가 손상되고 처리시간이 늘어났다. 또한 Docling group 내부 참고문헌 누락, Vision 거절 표현 판정, PDF 입력부터 최종 평가 Markdown까지의 시간 기록을 보완할 필요가 있었다.

**변경된 것**

- Docling 중첩 group을 문서 순서대로 탐색하고 참고문헌 `list_item` 원문을 보존하도록 변경
- Text SLLM은 코드만으로 복원하지 못한 수식에만 사용하고 표는 Vision SLLM 중심으로 처리하도록 분리
- Vision 거절 결과의 대괄호·대소문자 차이를 동일하게 판정
- 복원 시간과 evaluator 실행·누적·합산 처리시간을 평가 산출물에 기록
- 사용하지 않는 표·그림 Text SLLM prompt 제거
- 현재 흐름, prompt, PDF 4개 정확도·처리시간을 멘토 설명 문서로 추가

**검증**

- `cd llmPipeline && .venv/bin/python -m unittest discover -s tests -p 'test_*.py'` — 249개 통과
- 변경 Python 파일 `py_compile` 통과

---

## 2026-07-15

### feat: Markdown 편집 라우팅과 생성 결과 계약 보강

**배경**

일반적인 문서 전체 편집과 원문 구조 보존 요청이 template 재구성으로 차단되고, 아직 지원하지 않는 섹션 뒤 추가 요청이 `replace`로 처리될 수 있었습니다. 신규 Markdown 생성은 필수 필드가 누락돼도 LLM 재시도를 하지 않았습니다.

**추가/변경된 것**

- 외부 template 재구성만 보류하고 문서 전체·구조 보존형 일반 편집은 router가 처리하도록 범위를 좁혔습니다.
- `insert_after`를 `current_section` 전용 operation으로 추가하고, local guard와 application 계층 모두 현재 섹션 target이 없으면 `clarify`로 반환합니다.
- 삽입 결과가 기존 섹션 heading을 반복하면 계약 실패로 처리해 기존 내용의 중복 삽입을 막습니다.
- 신규 Markdown의 `title`, `summary`, `markdown`을 검증하고 한 번 재시도한 뒤 재실패 시 전용 422를 반환합니다.
- 편집 목적에서 operation을 결정하는 규칙과 Markdown 생성 결과의 정규화·검증 흐름을 공통화했습니다.
- Agent 연동 계약과 backend 출력 계약의 `insert_after`, Markdown 생성 및 422 오류 설명을 현재 구현과 일치시켰습니다.

**검증**

- `llmPipeline/.venv/bin/python -m pytest -q` 결과 292개 테스트와 28개 subtest를 통과했습니다.

## 2026-07-14

### refactor: 문서 복원·평가 pipeline DDD 이관

**배경**

PDF 문서 복원과 최종 Markdown 평가 코드가 `tmp/`의 실행 산출물과 함께 있어 제품 경계, 재사용 가능한 진입점, 테스트 위치가 불명확했습니다.

**추가/변경된 것**

- canonical 문서 복원 단계를 `app/modules/document_restoration`의 domain/application/infrastructure/interfaces 구조로 이관했습니다.
- `RestoreDocumentUseCase`가 단계 순서를 담당하고 파일·subprocess·Docling 실행은 application port 뒤의 infrastructure adapter로 분리했습니다.
- 외부 evaluator job과 local-first evaluator CLI를 `app/modules/document_evaluation/interfaces`로 이동했습니다.
- 복원 prompt, 전용 requirements, CLI 실행 문서와 회귀 테스트를 정식 경로에 추가했습니다.
- PDF, crop, model cache와 실행 산출물이 포함된 `tmp/`, `paddle_cache/`는 Git 추적 대상에서 제외했습니다.
- 얕은 output 경로에서도 Paddle cache를 안전하게 찾도록 보정하고 `pix2tex` 의존성과 `unittest` 수집 범위를 명확히 했습니다.

**검증**

- `llmPipeline/.venv/bin/python -B -m unittest discover -s tests -p 'test_*.py'` 결과 170개 테스트를 통과했습니다.
- document restoration/evaluation CLI 3개와 개별 infrastructure stage CLI 8개의 `--help` 실행을 확인했습니다.
- 정식 모듈과 테스트의 `compileall`, `git diff --check`를 통과했습니다.

**주의사항**

- 문서 복원 CLI는 `requirements-document-restoration.txt`와 시스템 `tesseract`가 필요합니다.
- Paddle FormulaRecognition은 `paddleocr`이 설치된 환경에서 선택적으로 사용됩니다.
- 수식 image-to-LaTeX 근거 생성에는 전용 requirements에 포함된 `pix2tex`가 필요합니다.

### perf: canonical PDF 복원 플로우 최적화 (v5~v8)

`docs/backlog/issue-2026-07-14.md`에 기록되었던 작업 완료 내용을 changelog로 이관한 항목입니다.

- 복원 플로우 실행 시간을 39.7% 단축했습니다 (v5 최적화).
- 최종 Markdown 평가를 외부 evaluator job 대신 local-first evaluator로 전환했습니다 (153.34초, 테스트 31개 통과).
- 불완전 종결 heuristic을 제거했습니다 (v7).
- 구조 이상 표는 직접 Vision 검토로 처리하도록 변경했습니다 (v8, 최종 결론).
- 해당 코드는 PR 74/75로 `llmPipeline/app/modules/document_restoration`, `document_evaluation`에 DDD 구조로 이관되었습니다. 당시 실측 리포트 경로(`tmp/canonical_flow_run_*`)는 git 추적 제외 산출물이라 현재는 stale입니다.

---

## 2026-07-10

### docs: Prompt 지시문 영문화

**배경**

LLM system prompt의 지시문은 영어로 유지하되, 모델이 생성해야 하는 한국어 결과와 한국어 사용자 표현 예시는 보존해야 했습니다.

**추가/변경된 것**

- `agent_turn_router`, `concept_page_generation`, `markdown_edit`, `wiki_schema_organizer` prompt에서 영어 지시문과 한국어 출력 예시의 역할을 구분했습니다.
- meeting notes 섹션명, schema organizer 출력 예시, 한국어 follow-up trigger처럼 실제 결과 구조나 사용자 입력 매칭에 필요한 한국어는 유지했습니다.

**검증**

- `rg -n "Discussion Items|Decisions|Pending Items|Next Actions|do it that way|contrasting concept|please do it|Prioritize it as a concept candidate" llmPipeline/prompts` 결과 잘못 영문화된 출력 예시가 없음을 확인했습니다.

---

### feat: Chat Wiki 누적 API 계약 분리

**배경**

채팅 Wiki page화는 일반 문서 ingest와 달리 `session_id:pair_id`를 source reference로 유지하고, 기존 chat source page가 있을 때만 신규 pair를 누적해야 했습니다. 기존 `/pipeline/runs` 계약에 섞으면 `selection_mode`와 inline Markdown 입력을 잘못 사용할 수 있어 chat 전용 API boundary가 필요했습니다.

**추가/변경된 것**

- `/chat-wiki/runs`를 추가하고 `document_id`, `selection_mode`를 필수로 검증합니다.
- `input_markdown`은 기존 source page가 있는 `full` 누적에서만 허용하고, `partial` 또는 최초 `full`에서는 거부합니다.
- chat Markdown의 `[session_id:pair_id]` prefix를 source reference로 보존합니다.
- 기존 source page markdown과 artifact를 context로 사용해 `full` 누적 source page를 평가/병합합니다.
- source accumulation evaluator 결과가 source page artifact, concept page 입력, DB summary에 반영되도록 조정했습니다.
- 후속 정리로 chat API 입력 해석 로직을 별도 함수로 분리해 `input_markdown` 허용 조건과 저장 문서 fallback 경로를 한 곳에서 읽히게 했습니다.
- chat source accumulation payload 생성과 evaluator 결과 적용 로직을 `chat_source_accumulation.py`로 분리해 pipeline orchestration과 구조화 결과 매핑 책임을 나눴습니다.
- 기존 source page row가 없으면 `full` 누적 context가 없는 것으로 판단해 `input_markdown`을 거부하도록 보정했습니다.

**검증**

- `PYTHONPATH=llmPipeline llmPipeline/.venv/bin/python -m pytest llmPipeline/tests/test_pipeline_run_api_contract.py llmPipeline/tests/modules/wiki_generation llmPipeline/tests/modules/wiki_ingestion` 통과.
- 최종 결과: `68 passed, 1 warning`
- 실제 API 실행으로 `full` 누적 시 기존 ref와 신규 ref가 source/concept page에 함께 반영되고 DB source summary가 evaluator 결과로 저장되는 것을 확인했습니다.

---

## 2026-07-04

### refactor: Chat completions JSON parser 분리

**배경**

`chat_completions_llm.py`가 HTTP client, LLM adapter, JSON parsing/repair 정책을 함께 들고 있어 외부 호출 경계와 순수 parsing 규칙이 섞여 있었습니다.

**추가/변경된 것**

- JSON fence 제거, JSON object repair, section polish output 정규화를 `json_output_parser.py`로 분리했습니다.
- 기존 `chat_completions_llm.py`의 parser import 경로는 유지해 호출부 계약을 바꾸지 않았습니다.
- JSON parsing/repair와 section polish schema normalization 동작을 테스트로 고정했습니다.

**검증**

- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m pytest tests/modules/wiki_generation/test_json_output_parser.py tests/modules/wiki_generation/test_wiki_generation_graph.py` 통과.
- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m py_compile app/modules/wiki_generation/infrastructure/chat_completions_llm.py app/modules/wiki_generation/infrastructure/json_output_parser.py tests/modules/wiki_generation/test_json_output_parser.py run_lab.py` 통과.

### refactor: Query graph path helper 분리

**배경**

`AnswerQueryUseCase`가 query orchestration과 direct concept match 시 graph node/path 보정 규칙을 함께 들고 있어 use case 본문이 길어졌습니다.

**추가/변경된 것**

- focus concept related page 병합, direct concept path backfill, focus concept 연결 source 확장, answer path 선택을 `query_graph_paths.py`로 분리했습니다.
- query scoring, traversal, answer generation, 응답 계약은 유지했습니다.
- 분리된 graph path helper의 정렬, 중복 제거, direct concept path 보정 동작을 단위 테스트로 고정했습니다.

**검증**

- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m pytest tests/modules/query/test_query_graph_paths.py tests/modules/query/test_answer_query.py` 통과.
- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m py_compile app/modules/query/application/answer_query.py app/modules/query/application/query_graph_paths.py tests/modules/query/test_query_graph_paths.py` 통과.

### refactor: Generated concept page assembler 분리

**배경**

`assemble.py`가 backend source/concept page 조립과 LLM generated concept page 출력 정규화/Markdown 조립을 함께 들고 있어 assembly 책임이 넓었습니다.

**추가/변경된 것**

- `GeneratedConceptPageAssembler`를 `generated_concept_page_assembler.py`로 분리했습니다.
- 기존 `assemble.py` import 경로는 유지해 호출부 계약을 바꾸지 않았습니다.
- generated concept page assembler의 block id mapping과 Markdown 조립 동작을 테스트로 고정했습니다.

**검증**

- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m pytest tests/modules/wiki_generation/test_source_extraction_artifact.py tests/modules/wiki_generation/test_ref_format.py` 통과.
- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m py_compile app/modules/wiki_generation/infrastructure/assemble.py app/modules/wiki_generation/infrastructure/generated_concept_page_assembler.py tests/modules/wiki_generation/test_source_extraction_artifact.py` 통과.

### refactor: Concept section polish stage 분리

**배경**

`run_pipeline()`가 source page polish와 별도로 concept page section polish payload 구성, LLM 호출, fallback 처리, page assembly를 직접 들고 있어 concept 생성 분기가 길어졌습니다.

**추가/변경된 것**

- concept definition/key points/related hint section polish 준비와 결과 mapping을 `_prepare_concept_section_polish()`로 분리했습니다.
- `section-polish`, `api/full-llm`, backend skeleton mode 분기와 manifest 계약은 유지했습니다.
- concept polish helper가 resolution link hint와 polished Markdown을 연결하는 동작을 테스트로 고정했습니다.

**검증**

- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m pytest tests/modules/wiki_generation/test_wiki_generation_graph.py tests/modules/wiki_generation/test_section_polish_mapping.py` 통과.
- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m py_compile run_lab.py tests/modules/wiki_generation/test_wiki_generation_graph.py` 통과.

### refactor: Source page polish stage 분리

**배경**

`run_pipeline()`가 pipeline stage orchestration과 source page section polish payload 구성/실패 처리/결과 mapping을 함께 들고 있어 stage 흐름이 길어졌습니다.

**추가/변경된 것**

- source page summary/key points section polish 준비와 결과 mapping을 `_prepare_source_page_polish()`로 분리했습니다.
- source page 생성, concept page 생성, manifest 계약은 유지했습니다.
- source polish helper의 skeleton mode와 polish mapping 동작을 테스트로 고정했습니다.

**검증**

- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m pytest tests/modules/wiki_generation/test_wiki_generation_graph.py tests/modules/wiki_generation/test_section_polish_mapping.py` 통과.
- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m py_compile run_lab.py tests/modules/wiki_generation/test_wiki_generation_graph.py` 통과.

### refactor: Wiki persistence payload helper 분리

**배경**

`postgres_wiki_ingestion_repository.py`가 DB write 흐름과 pipeline manifest/page payload 정규화 규칙을 함께 들고 있었습니다.

**추가/변경된 것**

- source summary, Markdown title, page payload, stored manifest, page id resolution helper를 `wiki_persistence_payload.py`로 분리했습니다.
- repository의 SQL/DB persistence 흐름과 저장 계약은 유지했습니다.
- persistence payload helper 단위 테스트를 추가했습니다.

**검증**

- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m pytest tests/modules/wiki_ingestion/test_wiki_persistence_payload.py tests/modules/wiki_ingestion/test_concept_index.py tests/modules/wiki_ingestion/test_markdown_sections.py tests/modules/wiki_ingestion/test_promotion_concept_page.py` 통과.
- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m py_compile app/modules/wiki_ingestion/infrastructure/postgres_wiki_ingestion_repository.py app/modules/wiki_ingestion/infrastructure/wiki_persistence_payload.py` 통과.

### refactor: Query evidence text helper 분리

**배경**

`EvidenceSelector`가 evidence 후보 선택과 Markdown 문단/sentence 분리, 토큰 정규화, specificity bonus 계산을 함께 담당하고 있었습니다.

**추가/변경된 것**

- evidence text unit 분리, section weight, sentence cleanup, token normalization을 `evidence_text.py`로 분리했습니다.
- `EvidenceSelector`는 기존 evidence 후보 생성과 선택 흐름을 유지하고 순수 text helper만 새 모듈에 위임합니다.
- evidence text helper 단위 테스트를 추가했습니다.

**검증**

- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m pytest tests/modules/query/test_evidence_text.py tests/modules/query/test_evidence_selector.py tests/modules/query/test_answer_query.py` 통과.
- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m py_compile app/modules/query/application/evidence_selector.py app/modules/query/application/evidence_text.py` 통과.

### refactor: Meaning cluster artifact assembler 분리

**배경**

`assemble.py`가 source/concept page 조립 외에 meaning cluster active/log artifact 조립까지 함께 담당해 파일 책임이 계속 커지고 있었습니다.

**추가/변경된 것**

- meaning cluster 후보 수집, active cluster Markdown, ingest log Markdown 조립을 `meaning_cluster_artifact.py`로 분리했습니다.
- 기존 `assemble.py` import 경로는 유지해 호출부 계약을 바꾸지 않았습니다.

**검증**

- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m pytest tests/modules/wiki_generation/test_source_extraction_artifact.py tests/modules/wiki_generation/test_ref_format.py` 통과.
- `/Users/jaehyeong/local-pilot/llmPipeline/.venv/bin/python -m py_compile app/modules/wiki_generation/infrastructure/assemble.py app/modules/wiki_generation/infrastructure/meaning_cluster_artifact.py` 통과.

### refactor: Wiki generation section polish mapping 분리

**배경**

`run_lab.py`가 wiki generation graph orchestration과 LLM section polish output 정규화 규칙을 함께 들고 있어 CLI 흐름의 책임이 커졌습니다.

**추가/변경된 것**

- section polish raw output의 text cleanup, anchor ref 검증, item mapping을 `section_polish_mapping.py`로 분리했습니다.
- `run_lab.py`는 기존 section polish 호출 흐름을 유지하고 mapping 함수만 application 모듈에서 가져오도록 정리했습니다.
- section polish mapping 단위 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m py_compile llmPipeline/run_lab.py llmPipeline/app/modules/wiki_generation/application/section_polish_mapping.py llmPipeline/tests/modules/wiki_generation/test_section_polish_mapping.py` 통과.
- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.wiki_generation.test_section_polish_mapping` 통과.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules/wiki_generation` 통과.
- `git diff --check` 통과.

### refactor: Wiki ingestion promotion concept page builder 분리

**배경**

`api.py`가 FastAPI route와 lint LLM client 구성 외에 promotion cluster LLM draft를 concept Markdown으로 변환하는 순수 조립 규칙까지 함께 들고 있었습니다.

**추가/변경된 것**

- promotion concept page Markdown 조립, representative 선택, lint ref 정규화/표기 함수를 `promotion_concept_page.py`로 분리했습니다.
- API route는 기존 lint materialization 흐름을 유지하고 promotion page builder만 새 모듈에서 가져오도록 정리했습니다.
- promotion concept page builder 단위 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m py_compile llmPipeline/api.py llmPipeline/app/modules/wiki_ingestion/infrastructure/promotion_concept_page.py llmPipeline/tests/modules/wiki_ingestion/test_promotion_concept_page.py` 통과.
- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.wiki_ingestion.test_promotion_concept_page` 통과.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules/wiki_ingestion` 통과.
- Docker 환경에서 `tests.modules.wiki_ingestion.test_concept_index`의 pytest-style 함수 테스트 직접 호출 통과.

### refactor: Wiki generation concept page section helper 분리

**배경**

`assemble.py`가 concept page Markdown 조립과 source key point, evidence fallback, related concept line 계산 규칙을 함께 들고 있어 page assembly 책임이 커졌습니다.

**추가/변경된 것**

- concept page의 source key point 수집, evidence 선택, key point line 생성, related concept line 계산을 `concept_page_sections.py`로 분리했습니다.
- `assemble.py`는 기존 Markdown 산출물 조립 흐름을 유지하고 concept section helper만 새 모듈에서 가져오도록 정리했습니다.
- concept page section helper 단위 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m py_compile llmPipeline/app/modules/wiki_generation/infrastructure/assemble.py llmPipeline/app/modules/wiki_generation/infrastructure/concept_page_sections.py llmPipeline/tests/modules/wiki_generation/test_concept_page_sections.py` 통과.
- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.wiki_generation.test_concept_page_sections llmPipeline.tests.modules.wiki_generation.test_ref_format` 통과.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules/wiki_generation` 통과.
- `git diff --check` 통과.

### refactor: Wiki generation evaluation guard 분리

**배경**

`run_lab.py`가 CLI/graph orchestration과 wiki generation evaluation guard, observation repair 규칙을 함께 들고 있어 pipeline 흐름을 읽기 어려웠습니다.

**추가/변경된 것**

- generation evaluation 보정 규칙과 observation repair 로직을 `evaluation_guards.py`로 분리했습니다.
- `run_lab.py`는 기존 graph 흐름을 유지하고 evaluation guard 함수만 application 모듈에서 가져오도록 정리했습니다.
- evaluation guard와 observation repair 단위 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m py_compile llmPipeline/run_lab.py llmPipeline/app/modules/wiki_generation/application/evaluation_guards.py llmPipeline/tests/modules/wiki_generation/test_evaluation_guards.py llmPipeline/tests/modules/wiki_generation/test_wiki_generation_graph.py` 통과.
- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.wiki_generation.test_evaluation_guards` 통과.
- 로컬 `test_wiki_generation_graph`는 `langgraph` 미설치로 Docker 환경에서 검증했습니다.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules/wiki_generation` 통과.
- `git diff --check` 통과.

### refactor: Query source reference parser 분리

**배경**

`EvidenceSelector`가 evidence 선택/scoring과 source block ref 문자열 파싱을 함께 담당해, citation ref 처리 규칙을 독립적으로 검증하기 어려웠습니다.

**추가/변경된 것**

- source ref 감지, block id 추출, structured `SourceReference` 변환, legacy field 변환, ref 제거 로직을 `source_references.py`로 분리했습니다.
- `EvidenceSelector`는 기존 evidence selection 흐름을 유지하고 source ref parser 함수만 새 모듈에서 가져오도록 정리했습니다.
- source ref parser 단위 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m py_compile llmPipeline/app/modules/query/application/evidence_selector.py llmPipeline/app/modules/query/application/source_references.py llmPipeline/tests/modules/query/test_source_references.py llmPipeline/tests/modules/query/test_evidence_selector.py` 통과.
- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.query.test_source_references llmPipeline.tests.modules.query.test_evidence_selector` 통과.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules/query` 통과.
- `git diff --check` 통과.

### refactor: Wiki ingestion active cluster helper 분리

**배경**

`postgres_wiki_ingestion_repository.py`가 DB persistence와 active cluster Markdown parser/merge, embedding unit 추출 규칙을 함께 들고 있어 repository 책임이 과도했습니다.

**추가/변경된 것**

- active cluster lint/merge 규칙을 `active_cluster_markdown.py`로 분리했습니다.
- wiki embedding unit 추출, canonical representation, hash helper를 `embedding_units.py`로 분리했습니다.
- repository는 기존 persistence 흐름을 유지하고 순수 Markdown/embedding helper만 새 모듈에서 가져오도록 정리했습니다.
- active cluster merge 중복 claim/relation 처리 characterization 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m py_compile llmPipeline/app/modules/wiki_ingestion/infrastructure/postgres_wiki_ingestion_repository.py llmPipeline/app/modules/wiki_ingestion/infrastructure/active_cluster_markdown.py llmPipeline/app/modules/wiki_ingestion/infrastructure/embedding_units.py llmPipeline/tests/modules/wiki_ingestion/test_concept_index.py` 통과.
- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.wiki_ingestion.test_markdown_sections` 통과.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules/wiki_ingestion` 통과.
- Docker 환경에서 `tests.modules.wiki_ingestion.test_concept_index`의 pytest-style 함수 테스트 직접 호출 통과.
- `git diff --check` 통과.

### refactor: Wiki ingestion Markdown section parser 분리

**배경**

`postgres_wiki_ingestion_repository.py`가 DB persistence 외에 concept Markdown section 파싱 보조 함수까지 함께 들고 있어, 순수 문자열 처리 규칙을 독립적으로 검증하기 어려웠습니다.

**추가/변경된 것**

- Markdown `## Heading` section 추출과 list item 추출 로직을 `markdown_sections.py`로 분리했습니다.
- repository는 기존 DB/persistence 흐름을 유지하고 Markdown parser 함수만 새 모듈에서 가져오도록 정리했습니다.
- Markdown section parser 단위 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.wiki_ingestion.test_markdown_sections` 통과.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules/wiki_ingestion` 통과.
- Docker 환경에서 `tests.modules.wiki_ingestion.test_concept_index`의 pytest-style 함수 테스트 직접 호출 통과.
- 관련 infrastructure 모듈 `py_compile` 통과.
- `git diff --check` 통과.

### refactor: Wiki generation ref formatting 분리

**배경**

`assemble.py`가 page/cluster artifact 조립과 source block ref 표기 규칙을 함께 들고 있어, citation 표기 규칙을 독립적으로 검증하기 어려웠습니다.

**추가/변경된 것**

- ref label, global ref, citation suffix formatting을 `ref_format.py`로 분리했습니다.
- `assemble.py`는 기존 page/cluster 조립 흐름을 유지하고 ref formatting 함수만 새 모듈에서 가져오도록 정리했습니다.
- ref formatting 단위 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.wiki_generation.test_ref_format` 통과.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules/wiki_generation` 통과.
- 관련 infrastructure 모듈 `py_compile` 통과.
- `git diff --check` 통과.

### refactor: LLM 환경변수 해석 helper 공통화

**배경**

agent, markdown edit, wiki schema, query LLM infrastructure가 endpoint/API key/model/numeric 옵션을 거의 같은 방식으로 해석하는 함수를 반복하고 있었습니다. 각 모듈의 환경변수 우선순위와 기본값은 유지하면서 공통 parsing만 분리했습니다.

**추가/변경된 것**

- `app/core/llm_env.py`를 추가해 chat completions endpoint, API key, model, float/int option 해석을 공통화했습니다.
- agent router, markdown editor, wiki schema organizer, query answer generator/evaluator가 공통 helper를 사용하도록 정리했습니다.
- API key trim 여부처럼 기존 모듈별 차이는 호출부 옵션으로 유지했습니다.
- env helper 단위 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.test_llm_env llmPipeline.tests.modules.agent.test_handle_agent_turn llmPipeline.tests.modules.markdown_edit.test_generate_markdown_edit llmPipeline.tests.modules.markdown_edit.test_generate_markdown_document llmPipeline.tests.modules.wiki_schema.test_chat_completions_schema_organizer llmPipeline.tests.modules.query.test_query_chat_answer_generator` 통과.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules` 통과.
- 관련 모듈 `py_compile` 통과.
- `git diff --check` 통과.

### refactor: Wiki embedding 결과 summary 조립 분리

**배경**

wiki page embedding use case가 동일한 결과 dict shape를 여러 return 지점에서 반복해, 응답 key 변경이나 집계 기준 수정 시 누락 위험이 있었습니다.

**추가/변경된 것**

- embedding 실행 결과 dict를 만드는 `embedding_result()` 보조 함수를 추가했습니다.
- `BuildWikiPageEmbeddingsUseCase`의 기존 반환 shape는 유지하면서 모든 return 지점이 같은 helper를 사용하도록 정리했습니다.
- 결과 summary shape를 고정하는 단위 테스트를 추가했습니다.

**검증**

- `PYTHONPATH=llmPipeline /opt/homebrew/bin/python3.12 -m unittest llmPipeline.tests.modules.wiki_embedding.test_build_wiki_page_embeddings` 통과.
- `docker run --rm -v /private/tmp/local-pilot-llmpipeline-refactor/llmPipeline:/app -w /app fruition-mvp-dev-pipeline-api python -m unittest discover -s tests/modules/wiki_embedding` 통과.
- 관련 application 모듈 `py_compile` 통과.
- `git diff --check` 통과.

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

### fix: 위키 lint dry-run과 workspace link scope 정리

**배경**

`POST /wiki/maintenance/lint`가 이름과 달리 기본값으로 promotion materialize를 실행할 수 있었고, dry-run 성격의 호출도 lint log object를 쓸 수 있었습니다. 또한 workspace/user scope가 추가된 뒤에도 `source_related_to` 재계산은 전역 link를 삭제하고 재생성해 다른 workspace의 link에 영향을 줄 수 있었습니다.

**추가/변경된 것**

- `WikiLintIn` 기본값을 `dry_run=true`, `materialize_promotions=false`로 변경했습니다.
- dry-run lint 호출에서는 lint log object를 쓰지 않도록 `write_log` 옵션을 분리했습니다.
- `source_related_to` 재계산을 현재 `user_id`/`workspace_id`의 source/concept page 범위로 제한했습니다.
- dry-run log 미작성과 workspace scoped link refresh regression test를 추가했습니다.

**검증**

- Docker `pipeline-api` 컨테이너에서 `python -m compileall -q /tmp/pr66-llmpipeline-ae7e5d5/app /tmp/pr66-llmpipeline-ae7e5d5/api.py /tmp/pr66-llmpipeline-ae7e5d5/run_lab.py` 통과.
- Docker `pipeline-api` 컨테이너에서 `python -m pytest /tmp/pr66-llmpipeline-ae7e5d5/tests` 통과 (`154 passed`).

## 2026-07-02

### feat: query evidence에 다중 원문 source_refs 추가

**배경**

같은 answer citation rank가 여러 원문 문서 block을 동시에 참조할 수 있는데, 기존 `source_document_id` + `source_block_ids` 구조는 document id를 하나만 담을 수 있어 `doc_a:B0001`, `doc_b:B0008` 같은 전역 ref를 정확히 표현하기 어려웠습니다.

**추가/변경된 것**

- `llmPipeline` query evidence domain과 FastAPI 응답에 `source_refs` 배열을 추가했습니다.
- `doc_id:B0001` 전역 ref를 `{source_document_id, source_block_id}` 객체로 구조화해 evidence snippet에 포함합니다.
- 기존 `source_document_id`, `source_block_ids`는 첫 번째 문서 기준 호환 필드로 유지했습니다.
- Spring backend와 frontend의 `source_refs` 소비 작업은 `docs/backlog/issue-2026-07-02.md` 후속작업으로 분리했습니다.

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
- backend/frontend 후속 반영 항목을 `docs/backlog/issue-2026-07-02.md`에 정리했습니다.

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

## 2026-06-27

### feat: query 근거 평가와 ingest 저장 흐름 개선

`docs/backlog/issue-2026-06-27.md`에 기록되었던 작업 완료 내용을 changelog로 이관한 항목입니다 (PR 49, 원문: `docs/backlog/issue-2026-06-27.md`).

- `POST /pipeline/runs` inline 입력(`input`/`input_path`) 실행에도 `source_document_id`와 `documents` row가 생성되도록 보강했습니다.
- `QueryAnswerEvaluator`를 추가해 답변/근거 정합성을 판단하고, 필요 시 web fallback 또는 internal web augmented route를 요청하도록 했습니다.
- `wiki_embedding_vectors`(canonical representation 공유)·`wiki_embedding_units`(page/unit 연결) 테이블을 추가해 source/concept 원자 단위 근거 저장 구조를 도입했습니다.
- Query context 조립이 저장된 embedding unit을 우선 사용하고, 없을 때만 markdown parsing fallback을 쓰도록 변경했습니다.
- evidence 선택을 고정 top-N에서 page별 최고 점수 대비 score band 방식으로 바꾸고, 구체성 신호(설계 변수·수치·단위·표 등)를 점수에 반영했습니다.
- 문장마다 자동 `[1]`을 붙이던 citation 후처리를 제거하고, LLM이 실제 인용한 evidence만 `evidence_snippets`에 남기도록 했습니다.
- `parse_json_object()` repair 후보 추가 등 semantic extraction JSON parsing과 prompt를 보강했습니다.

**검증**

- 원문 기준 pytest 31 passed. 논문 ingest 실험 결과는 `docs/backlog/issue-2026-06-27.md`의 실험 이력 참조.

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
- Spring/Frontend 후속 반영 항목은 `docs/backlog/issue-2026-06-21.md`에 정리했습니다.

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
