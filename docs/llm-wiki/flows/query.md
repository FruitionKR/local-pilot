# llmPipeline Query 흐름

## 1. 문서 목적

이 문서는 현재 `llmPipeline` Query Engine이 사용자 질문을 받을 때 다음 과정을 어떻게 수행하는지 설명한다.

- 질문과 대화 context를 어떻게 검색 질의로 바꾸는가?
- source/concept page 후보를 어떻게 찾고 점수화하는가?
- Wiki graph를 어떤 규칙으로 탐색하는가?
- 어떤 근거를 LLM 답변 context로 선택하는가?
- 답변 prompt와 evaluator prompt는 어떻게 구성되는가?
- evaluator 결과에 따라 retry, web fallback, unsupported를 어떻게 결정하는가?
- 최종 응답에는 어떤 결과가 포함되는가?

현재 코드 구현을 기준으로 하며, 주요 기준 코드는 다음과 같다.

- HTTP 진입점: `llmPipeline/app/modules/query/interfaces/http/routes.py`
- 전체 orchestration: `llmPipeline/app/modules/query/application/answer_query.py`
- page scoring: `llmPipeline/app/modules/query/application/query_page_scorer.py`
- graph traversal: `llmPipeline/app/modules/query/application/traverse_wiki_graph.py`
- evidence 선택: `llmPipeline/app/modules/query/application/evidence_selector.py`
- 답변 조립: `llmPipeline/app/modules/query/application/query_answer_assembler.py`
- evaluator graph: `llmPipeline/app/modules/query/infrastructure/query_evaluator_graph.py`

## 2. 한눈에 보는 전체 흐름

```text
POST /query
  │
  ▼
질문 검증
  │
  ▼
대화 context로 질문 보강
  │
  ▼
Rule-based query rewrite
  │
  ▼
PostgreSQL candidate page 조회
  ├─ metadata / full-text
  ├─ embedding unit text
  └─ 선택적으로 semantic page embedding
  │
  ▼
MinIO Wiki Markdown 로드
  │
  ▼
source / concept page 재점수화
  ├─ embedding
  ├─ BM25
  ├─ title/slug/alias 직접 일치
  └─ source 구조 section 가중치
  │
  ▼
seed source / focus concept 선택
  │
  ▼
Wiki graph traversal
  │
  ▼
관련 page Markdown + embedding unit 로드
  │
  ▼
evidence snippet 선택
  │
  ▼
LLM answer context 조립
  │
  ▼
LLM 답변 생성
  │
  ▼
citation marker 검증·정리
  │
  ▼
선택적 LLM evaluator
  ├─ internal_supported ───────────────┐
  ├─ revise_answer -> retry            │
  ├─ web_fallback -> web 검색          │
  ├─ internal_web_augmented -> 병합    │
  └─ unsupported -> 근거 부족 응답     │
  │                                   │
  └───────────────────────────────────┘
  │
  ▼
answer + related_pages + evidence_snippets
       + graph_context + traversal_paths
```

Query는 ingest 결과인 `wiki_pages`, `wiki_page_links`, `wiki_embedding_units`와 MinIO Markdown을 함께 사용한다.

## 3. HTTP 요청과 질문 Context 보강

**역할과 입력**

`question`, `recent_conversation_summary`, `recent_messages`에서 생략된 대상을 보강하고 검색 표현을 만든다.

HTTP 진입점은 `POST /query`이며 요청 계약은 다음과 같다.

```json
{
  "workspace_id": "workspace-id",
  "user_id": "user-id",
  "question": "그 방식은 일반 RAG와 무엇이 달라?",
  "request_id": "request-id",
  "log_callback_url": "https://callback.example/query-events",
  "recent_conversation_summary": "직전에는 Persistent Wiki를 설명했다.",
  "reference_context": {"active_topic": "Persistent Wiki"}
}
```

구현은 `conversation_context_resolver.py`, `rule_based_query_rewriter.py`, `answer_query.py`에 있다.

**Context 보강 규칙**

원 질문의 의도를 새로 만들지 않고 대화에서 명확한 대상만 보충한다.

**출력과 다음 단계**

`effective_question`, 검색 term/phrase를 만들어 candidate 조회에 전달한다. 보강할 정보가 없으면 원 질문을 유지한다.

rewrite 결과는 원 질문, normalized query, token/phrase와 web용 query를 구분한다. event publisher가 설정돼 있으면 request id와 함께 단계 진행 event를 callback으로 보낼 수 있다.

## 4. Candidate Page 조회와 본문 로드

**역할과 입력**

`workspace_id`, rewrite 결과로 PostgreSQL metadata/full-text/embedding 후보를 조회하고 MinIO에서 Markdown을 읽는다.

**조회·점수화 규칙**

`workspace_id`가 검색 범위를 결정하며 source/concept score와 direct name match를 결합한다.

구현은 `postgres_wiki_repository.py`, `stored_wiki_page_embedding_search.py`, `query_page_scorer.py`, `minio_wiki_markdown_reader.py`에 있다. 후보 조회는 metadata/FTS/embedding 신호를 사용하고, concept title·slug·alias가 질문과 직접 일치하면 direct concept 후보로 별도 보강한다.

**출력과 다음 단계**

ranked source/concept pages와 본문을 만든다. 상위 page는 seed/focus concept이 되어 graph traversal로 넘어간다.

page별 결과에는 id, `page_type`, title, slug, relevance score와 역할이 붙는다. MinIO 본문을 읽지 못한 후보는 근거 생성에 그대로 사용할 수 없으며 검색 metadata와 답변 evidence를 구분한다.

## 5. Graph Traversal과 Evidence 선택

**역할과 입력**

seed page, `wiki_page_links`, page Markdown, `wiki_embedding_units`에서 답변 근거를 고른다.

**Traversal·Evidence 규칙**

graph는 `source_mentions_concept`, `concept_related_to`만 따라가며 evidence는 실제 source block으로 추적 가능해야 한다.

seed는 초기 검색에서 선택한 page이고 focus concept은 질문의 직접 concept match와 점수를 반영해 정한다. traversal은 depth와 score를 기록하며 related page를 많이 찾는 것보다 답변에 쓸 수 있는 path/evidence를 우선한다.

**출력 예시**

```json
{
  "evidence": [
    {
      "rank": 1,
      "page_slug": "persistent-wiki",
      "source_document_id": "doc-1",
      "source_block_ids": ["B0012"],
      "source_refs": [
        {"source_document_id": "doc-1", "source_block_id": "B0012"}
      ],
      "text": "새 문서의 개념을 기존 페이지와 병합한다."
    }
  ],
  "traversal_paths": [
    ["source-doc-1", "persistent-wiki"]
  ]
}
```

선택된 evidence, related page와 path를 answer context 조립으로 넘긴다.

evidence selector는 embedding unit, page section과 source reference를 함께 사용한다. citation marker `[1]`은 이 시점의 영속 값이 아니라 다음 prompt context에서 rank에 맞춰 붙이는 임시 표기다.

## 6. Answer Context와 Prompt 조립

**역할과 입력**

질문, page context와 evidence에 `[1]`, `[2]` 형태의 marker를 붙여 모델 messages를 만든다.

**system prompt의 핵심 내용**

- 제공된 Wiki context만 사용한다.
- 근거가 있는 문장에 해당 evidence marker를 붙인다.
- context에 없는 사실은 추측하지 않고 근거 부족을 밝힌다.
- marker와 출처를 임의로 만들지 않는다.
- active Wiki schema의 Query fragment를 따른다.

user prompt에는 원 질문/보강 질문, 관련 page 요약과 번호가 붙은 evidence가 들어간다.

구현은 `answer_context_formatter.py`, `query_chat_answer_generator.py`에 있다. LLM wrapper에는 active Wiki schema의 `query` fragment가 실제 주입되며 ingest와 달리 현재 composition에서 활성화돼 있다.

실제 system prompt는 코드의 `QUERY_ANSWER_SYSTEM_PROMPT`에 active schema fragment를 덧붙여 만든다. 핵심 계약은 다음과 같다.

- 사용자에게 보여줄 conversational answer body만 반환한다.
- evidence rank 번호만 `[1]`, `[2]` citation으로 사용한다.
- evidence를 사용한 모든 factual sentence 끝에 citation을 붙인다.
- score, path id, page id, URL과 내부 link type을 노출하지 않는다.
- evidence에 직접 정의나 설명이 없으면 정확한 답이 충분히 지원되지 않는다고 말한다.
- evidence에 없는 예시·비유·외부 지식을 만들지 않는다.

대표 messages:

```text
system:
  Wiki context에 근거해 답한다.
  제공되지 않은 사실은 추측하지 않는다.
  근거 문장에는 [1], [2]를 사용한다.

user:
  resolved question: ...
  mode-specific answer policy: ...
  related page context: ...
  evidence:
    [1] source_document_id=doc-1 source_block_ids=B0012 ...
```

answer context의 page/graph 정보는 주제를 이해시키는 보조 context이고, factual citation의 직접 근거는 rank가 붙은 evidence snippet이다. 이 구분 때문에 모델이 관련 page 이름만 보고 근거 없는 세부사항을 확장해서는 안 된다.

**출력 계약과 다음 단계**

모델은 citation marker가 들어간 자연어 답변을 반환한다. page JSON이나 새로운 Wiki artifact를 만드는 단계가 아니다. raw answer는 citation 검증으로 넘어간다.

**Prompt가 답변 범위를 결정하는 예시**

전달된 evidence:

```text
[1] Persistent Wiki는 신규 문서의 개념을 기존 concept page와 병합한다.
[2] 각 설명은 원본 source block reference를 유지한다.
```

prompt는 `외부 지식 금지`, `factual sentence마다 evidence rank citation`, `근거 없는 예시 금지`를 요구한다. 따라서 기대 모델 출력은 다음 범위다.

```text
Persistent Wiki는 새 문서를 독립 chunk로만 조회하지 않고 기존 concept page에 병합합니다. [1]
또한 설명을 원본 source block까지 추적할 수 있습니다. [2]
```

“항상 실시간 동기화된다”는 문장은 context에 없으므로 추가하면 계약 위반이다. “일반 RAG보다 무조건 정확하다” 역시 evidence가 없으면 출력할 수 없다. llmPipeline은 모델이 사용한 `[1]`, `[2]`를 실제 snippet과 연결해 응답에 넣는다.

## 7. 기본 답변 생성과 Citation 검증

**역할과 입력**

raw answer의 marker를 3단계 evidence map과 대조한다.

**Citation 검증 규칙**

존재하지 않는 marker는 신뢰하지 않고 실제 evidence 순서에 맞춰 번호를 정리한다.

**출력**

검증된 `answer`, `related_pages`, `evidence_snippets`, `graph_context`, `traversal_paths`를 만든다. evaluator가 비활성이면 이것이 최종 HTTP 응답이며 Wiki 데이터는 수정하지 않는다.

답변에 실제로 사용된 marker를 추출해 evidence와 다시 연결한다. 모델이 없는 `[9]`를 만들거나 같은 marker를 잘못 사용하면 허용된 evidence map 기준으로 정리한다. `evidence_snippets`는 page 요약이 아니라 source document/block까지 내려가는 근거다.

## 8. Query Evaluator 분기

**역할과 입력**

evaluator가 활성화됐을 때 질문, 검증된 답변, evidence와 retrieval 정보를 평가한다.

**prompt의 핵심 내용**

답변의 질문 충족도, 내부 근거 충실도와 추가 검색 필요성을 검사하고 다음 route 중 하나만 JSON으로 반환하도록 요구한다.

```json
{
  "route": "revise_answer",
  "evidence_relevance": 0.8,
  "citation_evidence_alignment": 0.5,
  "unsupported_refusal_accuracy": null,
  "reason": "질문에는 답했지만 일부 citation이 주장을 직접 지원하지 않는다.",
  "feedback": "지원되지 않는 문장을 제거하고 [2] 근거로 다시 작성한다.",
  "warnings": [],
  "web_query": null
}
```

허용 route는 `internal_supported`, `revise_answer`, `web_fallback`, `internal_web_augmented`, `unsupported`다.

system prompt는 `llmPipeline/prompts/query_answer_evaluator.system.md`다. user payload에는 question, query rewrite, answer, selected evidence, related pages, retrieval summary가 들어간다. 단순 문체 취향이 아니라 answerability, groundedness, citation coverage와 내부 근거로 수정 가능한지를 판정한다.

route별 prompt 규칙:

| route | 선택 조건 | `feedback` / `web_query` |
| --- | --- | --- |
| `internal_supported` | 답변과 citation이 내부 근거로 충분함 | feedback은 빈 문자열, web query는 null |
| `revise_answer` | 같은 내부 evidence로 답변을 고칠 수 있음 | 실행 가능한 수정 feedback |
| `web_fallback` | 내부 근거가 핵심 답을 지원하지 않으며 public web 검색이 적절함 | 질문의 핵심 external query |
| `internal_web_augmented` | 내부 근거가 주제를 식별·부분 지원하지만 최신/구현 정보가 부족함 | 부족한 외부 facet query |
| `unsupported` | 내부와 적절한 web 모두 안전하게 답할 수 없음 | web query는 null |

prompt는 “web이 더 자세할 것 같다”는 이유만으로 web route를 고르지 못하게 한다. 내부 근거가 충분하면 `internal_supported`, 같은 근거로 수정 가능하면 `revise_answer`가 우선이다.

**Evaluator Prompt가 다음 Flow를 바꾸는 예시**

첫 답변이 “Persistent Wiki는 실시간으로 동기화됩니다. [1]”인데 `[1]`이 concept 병합만 설명한다면 evaluator는 citation alignment를 낮추고 `revise_answer`를 선택한다. 같은 evidence에서 고칠 수 있기 때문에 web search로 보내지 않는다. feedback이 answer prompt에 추가되고 두 번째 답변은 실시간 주장을 제거한다.

반대로 질문이 “2026년 현재 제품 가격은?”이고 내부 evidence가 제품 구조만 설명한다면, prompt의 “current external facet” 규칙에 따라 `internal_web_augmented`와 가격 검색용 `web_query`가 나올 수 있다. 이 JSON route가 9단계에서 내부+web context를 사용할지 직접 결정한다.

**llmPipeline 처리와 다음 단계**

route와 issue schema를 검증한다. `internal_supported`는 현재 답변을 확정하고, `revise_answer`는 feedback을 answer prompt에 추가해 다시 4단계로 보낸다. web route는 web evidence 단계로, `unsupported`는 근거 부족 응답으로 간다.

evaluator graph는 시도 횟수와 route history를 유지한다. 최대 시도를 초과하면 무한 재작성을 하지 않고 현재 검증 답변 또는 unsupported 결과로 종료한다. evaluator가 설정되지 않았거나 provider가 지원하지 않으면 기본 내부 답변 경로를 사용한다.

## 9. Web Fallback·Internal Web Augmentation

**역할과 입력**

내부 근거가 부족하고 web 사용이 허용될 때 질문 또는 evaluator 보완 query로 web 결과를 얻는다.

**Answer Prompt 추가 구성**

검색 자체는 설정된 web provider를 사용한다. 검색 결과는 title, URL, snippet과 rank를 가진 web evidence context로 변환한 뒤 6단계의 answer system prompt를 다시 사용한다.

`web_fallback` user context에는 내부 Wiki가 핵심 답을 지원하지 않는다는 mode policy와 web evidence를 넣는다. `internal_web_augmented`에는 내부 evidence, web evidence와 “내부에서 지원하는 부분과 외부에서 보충하는 부분을 구분하라”는 policy를 함께 넣는다. 모델은 검색 결과 밖의 사실을 추가하지 않고, URL·검색 metadata를 내부 Wiki page id처럼 표현하지 않아야 한다.

evaluator가 반환한 `web_query`가 있으면 그 값을 검색어로 사용하고, 없으면 resolved question에서 web query를 만든다. web answer 역시 사용자에게 보여줄 본문만 반환하며 검색 trace나 score를 노출하지 않는다.

따라서 web 검색 결과가 세 개 들어왔다고 해서 세 결과를 요약하는 것이 목적이 아니다. answer prompt는 질문에 필요한 claim만 선택하고 제공된 web snippet으로 지원되는 문장만 만들도록 한다. `internal_web_augmented`에서는 내부 Wiki의 개념 설명과 web의 최신 사실을 각각 해당 evidence에 연결하며, web 사실을 기존 Wiki source block이 지원한 것처럼 표시하지 않는다.

**출력과 다음 단계**

보강 context를 answer 생성에 다시 전달한다. web 실패 시 내부 답변 또는 unsupported로 축소하며 web 결과를 Wiki page로 저장하지 않는다.

`web_fallback`은 내부 근거만으로 답할 수 없을 때 web 중심으로 답한다. `internal_web_augmented`는 내부 Wiki가 설명의 일부를 지원하고 최신·외부 사실만 부족할 때 두 context를 함께 사용한다. evaluator가 비활성이어도 선행 조건에서 내부 evidence가 전혀 없고 web fallback이 구성돼 있으면 web 경로를 사용할 수 있다.

## 10. 분기 합류: 최종 QueryResponse

**역할과 입력**

최종 answer와 retrieval/graph artifact를 HTTP 응답 계약으로 조립한다.

**응답 조립 규칙**

응답의 citation과 `evidence_snippets`가 같은 evidence map을 가리켜야 한다.

**최종 출력**

```json
{
  "answer": "Persistent Wiki는 ... [1]",
  "related_pages": [
    {
      "id": "page-id",
      "page_type": "concept",
      "title": "Persistent Wiki",
      "slug": "persistent-wiki",
      "relevance_score": 0.92,
      "role": "focus",
      "depth": 0
    }
  ],
  "evidence_snippets": [
    {
      "rank": 1,
      "source_document_id": "doc-1",
      "source_block_ids": ["B0012"],
      "source_refs": [
        {"source_document_id": "doc-1", "source_block_id": "B0012"}
      ],
      "text": "새 문서의 개념을 기존 페이지와 병합한다."
    }
  ],
  "graph_context": {
    "nodes": [],
    "edges": []
  },
  "traversal_paths": [
    {
      "path_id": "path-1",
      "role": "supporting",
      "used_for_answer": true,
      "score": 0.88,
      "stop_reason": "evidence_selected",
      "nodes": ["source-page-id", "concept-page-id"],
      "edges": []
    }
  ]
}
```

내부 evaluator raw JSON과 retrieval summary 전체는 HTTP 응답에 포함되지 않는다. 채팅 메시지 저장은 Spring backend 책임이다.

실행 중에는 rewrite, retrieval, graph traversal, evidence selection, answer generation과 evaluator route 등의 event를 callback publisher가 전달할 수 있다. callback 실패가 Query 결과와 같은 실패인지 여부는 publisher 구현의 오류 처리 경계를 따라 확인한다.

## 운영상 주의점

- Query는 Wiki page, graph와 embedding unit을 읽기만 하며 Wiki를 수정하지 않는다.
- graph traversal은 모든 relation이 아니라 `source_mentions_concept`, `concept_related_to`만 사용한다.
- evaluator는 기본 비활성일 수 있으며 HTTP 응답에는 evaluator raw JSON 전체가 포함되지 않는다.
- citation은 page 자체가 아니라 선택된 source block evidence를 가리킨다.
- web 보강 결과는 자동으로 Wiki page가 되지 않는다.

## 관련 문서

- `docs/evaluation/current-evaluator-metrics.md`
- `docs/evaluation/llm-evaluation-metrics.md`
- `docs/spec/llmpipeline-backend-output-contract.md`
