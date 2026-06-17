# Wiki Graph Query Engine Spec

## 1. 목적

이 문서는 Fruition MVP에서 Wiki 기반 자연어 질의를 구현하기 위한 Python AI service spec이다.

목표는 일반 raw chunk RAG가 아니라, 이미 생성된 `source page`, `concept page`, `wiki_page_links`를 이용해 질문과 관련된 Wiki graph subgraph를 찾고, 그 경로를 근거로 답변을 생성하는 것이다.

참고 문서:

- `docs/python_convention.md`
- `docs/Fruition_MVP_API_Contract.md`

`Fruition_MVP_API_Contract.md`의 `/api/query`는 기존 계약 참고용이다. 이 spec에서는 `highlighted_paths` 대신 `graph_context`, `traversal_paths`, `retrieval_summary`를 사용하는 확장 응답을 기준으로 한다.

## 2. 핵심 방향

Query Engine은 아래 세 단계를 담당한다.

```text
질문
  -> source/concept hybrid retrieval
  -> Wiki graph traversal
  -> context build + LLM answer generation
```

Fruition은 이미 `llmPipeline`을 통해 지식 구조화를 수행한다.

```text
raw document
  -> source page
  -> concept page
  -> source_mentions_concept
  -> concept_related_to
  -> source_related_to
```

따라서 Query Engine은 GraphRAG 전체를 새로 만드는 것이 아니라, 이미 만들어진 Wiki graph 위에서 검색, 탐색, 맥락 구성, 답변 생성을 수행한다.

## 3. 아키텍처 규칙

Python service는 `docs/python_convention.md`를 따른다.

필수 규칙:

- `app/modules/query/`를 bounded context로 사용한다.
- FastAPI route는 얇게 유지한다.
- LLM, embedding model, DB, Object Storage 접근은 `infrastructure/`에 둔다.
- `application/`은 포트와 use case orchestration만 가진다.
- `domain/`은 순수 값 객체, 엔티티, 점수 계산 규칙, 예외만 가진다.
- heavy model은 app startup에서 한 번만 로드한다.
- route에서 embedding model이나 LLM을 직접 호출하지 않는다.

## 4. 추천 폴더 구조

```text
app/
└─ modules/
   └─ query/
      ├─ __init__.py
      ├─ domain/
      │  ├─ __init__.py
      │  ├─ entities.py
      │  ├─ value_objects.py
      │  ├─ scoring.py
      │  └─ exceptions.py
      ├─ application/
      │  ├─ __init__.py
      │  ├─ ports.py
      │  ├─ answer_query.py
      │  ├─ build_query_context.py
      │  └─ traverse_wiki_graph.py
      ├─ infrastructure/
      │  ├─ __init__.py
      │  ├─ bge_m3_embedding_model.py
      │  ├─ postgres_wiki_repository.py
      │  ├─ minio_wiki_markdown_reader.py
      │  ├─ bm25_searcher.py
      │  └─ llm_answer_generator.py
      └─ interfaces/
         └─ http/
            ├─ __init__.py
            ├─ routes.py
            ├─ schemas.py
            └─ dependencies.py
```

파일명은 `snake_case.py`, 클래스명은 `PascalCase`를 사용한다.

## 5. 데이터 전제

Query Engine은 최소 아래 데이터를 사용한다.

### `wiki_pages`

- `id`
- `page_type`: `source`, `concept`
- `title`
- `slug`
- `summary`
- `markdown_uri`
- `status`

### `wiki_page_links`

지원 edge type:

```text
source_mentions_concept
concept_related_to
source_related_to
```

`concept_contrasts_with`는 계약상 존재할 수 있으나 MVP Query Engine의 기본 traversal 대상에서는 제외한다.

### Object Storage

`markdown_uri`는 `s3://fruition-storage/wiki/...` 형식을 권장한다. 실제 backend는 MinIO 같은 S3-compatible storage를 사용할 수 있다.

## 6. Retrieval 정책

### 6.1 기본 원칙

질의의 기본 seed는 `source page`다.

단, 검색 단계에서는 `concept page`도 함께 본다. concept은 시작점 자체가 아니라 `focus_concept_hint`로 사용한다.

```text
질문
  -> source retrieval top N
  -> concept retrieval top M
  -> 강한 concept match가 있으면 연결 source를 seed에 추가
  -> source-first graph traversal
```

이 정책은 아래 이유 때문에 필요하다.

- source page는 원본 근거와 연결 concept을 함께 갖고 있어 안정적인 시작점이다.
- 사용자가 `qmd`, `Obsidian Web Clipper`, `RAG`처럼 특정 concept 이름을 직접 말하면 concept retrieval이 더 정확할 수 있다.
- concept direct match를 무시하면 특정 개념 질의에서 source seed가 흔들릴 수 있다.

### 6.2 Source representation text

source embedding은 원문 전체나 제목만 사용하지 않는다. 아래 필드를 합친 검색용 projection text를 사용한다.

```text
Title: {source title}
Summary: {source summary}
Key Points:
{source page key points}
Extracted Concepts:
{connected concept titles}
```

### 6.3 Concept representation text

concept embedding은 제목만 사용하지 않는다.

```text
Title: {concept title}
Aliases: {aliases if available}
Definition: {definition}
Key Points:
{key points}
Related Concepts:
{related concept titles}
Sources:
{connected source titles}
```

### 6.4 Hybrid score

초기 기본값은 BGE-M3 vector similarity를 주력으로 한다.

권장 source score:

```text
source_retrieval_score =
  0.80 * embedding_similarity
+ 0.20 * bm25_score
```

테스트 결과, 한국어 우회 표현에서는 BGE-M3 source vector가 강하게 동작했다. BM25는 exact keyword, 고유명사, 짧은 질의에서 보조 역할로 사용한다.

Concept hint score:

```text
concept_hint_score =
  0.80 * concept_embedding_similarity
+ 0.20 * concept_bm25_score
```

`concept_hint_score >= 0.60`이면 해당 concept과 연결된 source를 seed 후보에 추가한다. threshold는 평가 결과에 따라 조정한다.

## 7. Graph traversal 정책

### 7.1 Traversal 대상 edge

기본 traversal edge:

```text
source -> concept: source_mentions_concept
concept -> concept: concept_related_to
source -> source: source_related_to
concept -> source: source_mentions_concept 역방향
```

프론트에 보여줄 때는 방향을 원본 edge 방향으로 보존한다. 내부 traversal은 역방향 탐색을 허용한다.

### 7.2 Source-source edge

`source_related_to`는 top-k 제한으로 만들지 않는다. 높은 minimum score threshold를 통과한 관계만 저장한다.

기본 생성 기준:

```text
source_related_score =
  weighted shared concept cosine

store if:
  source_related_score >= 0.75
```

공유 concept이 너무 많은 hub concept일 경우 영향력을 줄이기 위해 concept source count 기반 weight를 적용한다.

```text
concept_weight = 1 / concept_source_count
```

DB 저장:

```text
from_page_id = source:A
to_page_id = source:B
link_type = source_related_to
label = "공유 concept: ..."
confidence = source_related_score
```

### 7.3 Traversal budget

기본값:

```text
max_depth = 3
min_node_score = 0.40
relevance_decay_margin = 0.18
frontier_limit = 8
```

멈춤 조건:

- depth가 `max_depth`에 도달한다.
- 다음 frontier의 최고 점수가 이전 frontier 최고 점수보다 `relevance_decay_margin` 이상 떨어진다.
- 다음 frontier에 `min_node_score` 이상인 노드가 없다.
- 답변에 필요한 selected path와 evidence가 충분하다.

## 8. Context Builder 정책

LLM에는 전체 후보를 넣지 않는다.

Context Builder는 selected subgraph만 압축한다.

포함 대상:

- seed source page summary/key points
- focus concept definition/key points
- supporting source page summary/key points
- selected traversal paths
- source references

제외 대상:

- 점수가 낮은 후보
- 중복 source
- hub concept만으로 연결된 약한 경로
- selected path에 포함되지 않은 주변 노드

## 9. LLM 호출 정책

기본 질의는 LLM 1회 호출을 목표로 한다.

```text
retrieval + traversal + context build: deterministic code
answer generation: LLM 1회
```

예외적으로 seed confidence가 낮거나 후보가 과도하게 모호하면 query rewrite를 위해 LLM 1회를 추가할 수 있다.

```text
if seed_confidence < threshold:
    LLM query rewrite
    retrieval 재실행
LLM answer generation
```

LLM은 그래프를 직접 탐색하지 않는다. LLM은 선택된 context를 바탕으로 답변을 생성한다.

## 10. Application ports

`application/ports.py`에는 외부 의존성을 모두 Protocol로 정의한다.

예시:

```python
from typing import Protocol


class WikiRepositoryPort(Protocol):
    def list_active_pages(self) -> list["WikiPageRecord"]:
        ...

    def list_active_links(self) -> list["WikiPageLinkRecord"]:
        ...


class WikiMarkdownReaderPort(Protocol):
    def read_markdown(self, markdown_uri: str) -> str:
        ...


class EmbeddingModelPort(Protocol):
    def encode(self, texts: list[str]) -> list[list[float]]:
        ...


class TextSearchPort(Protocol):
    def score(self, query: str, documents: list[str]) -> list[float]:
        ...


class AnswerGeneratorPort(Protocol):
    def generate_answer(self, context: "QueryContext") -> "GeneratedAnswer":
        ...
```

실제 구현은 `infrastructure/`에 둔다.

## 11. Use case 흐름

`AnswerQueryUseCase.execute(question: str)`는 아래 순서를 따른다.

```text
1. Question value object 검증
2. active wiki page/link 로드
3. source representation 생성
4. concept representation 생성
5. source hybrid retrieval
6. concept focus hint retrieval
7. concept -> source backtracking으로 seed 보강
8. source-first graph traversal
9. selected path/context 생성
10. LLM answer generation
11. Query response 반환
```

FastAPI route는 이 use case만 호출한다.

## 12. HTTP API

기존 contract의 `POST /api/query`를 유지하되 응답 구조를 확장한다.

### Request

```json
{
  "question": "LLM Wiki가 일반 RAG와 뭐가 달라?"
}
```

### Response

```json
{
  "user_message": {
    "id": "51f4f0d6-f383-4b71-a65b-c4d1702d0555",
    "role": "user",
    "content": "LLM Wiki가 일반 RAG와 뭐가 달라?",
    "status": "completed",
    "created_at": "2026-06-04T10:05:00Z"
  },
  "assistant_message": {
    "id": "62b8f691-6401-460b-b227-f6f7bfb80666",
    "role": "assistant",
    "content": "LLM Wiki는 raw chunk를 매번 다시 검색하는 방식이 아니라, 문서를 source page와 concept page로 미리 구조화해 지속적인 Wiki graph를 질의하는 방식입니다.",
    "status": "completed",
    "created_at": "2026-06-04T10:05:03Z"
  },
  "related_pages": [
    {
      "id": "source:doc_123",
      "page_type": "source",
      "title": "LLM Wiki와 RAG 비교",
      "slug": "llm-wiki-rag",
      "relevance_score": 0.92,
      "role": "seed_source"
    },
    {
      "id": "concept:rag",
      "page_type": "concept",
      "title": "RAG",
      "slug": "rag",
      "relevance_score": 0.88,
      "role": "focus_concept"
    }
  ],
  "evidence_snippets": [
    {
      "page_id": "source:doc_123",
      "page_type": "source",
      "page_title": "LLM Wiki와 RAG 비교",
      "page_slug": "llm-wiki-rag",
      "page_url": "/api/wiki/pages/source:doc_123",
      "page_role": "seed_source",
      "score": 0.91,
      "rank": 1,
      "paragraph_index": 2,
      "sentence_index": 0,
      "text": "LLM Wiki는 문서를 미리 source page와 concept page로 컴파일한다."
    }
  ],
  "graph_context": {
    "nodes": [
      {
        "id": "source:doc_123",
        "page_type": "source",
        "title": "LLM Wiki와 RAG 비교",
        "slug": "llm-wiki-rag",
        "relevance_score": 0.92,
        "role": "seed_source",
        "depth": 0
      },
      {
        "id": "concept:rag",
        "page_type": "concept",
        "title": "RAG",
        "slug": "rag",
        "relevance_score": 0.88,
        "role": "focus_concept",
        "depth": 1
      },
      {
        "id": "concept:llm-wiki",
        "page_type": "concept",
        "title": "LLM Wiki",
        "slug": "llm-wiki",
        "relevance_score": 0.91,
        "role": "focus_concept",
        "depth": 1
      }
    ],
    "edges": [
      {
        "from_page_id": "source:doc_123",
        "to_page_id": "concept:rag",
        "link_type": "source_mentions_concept",
        "role": "seed_to_focus",
        "score": 0.88
      },
      {
        "from_page_id": "concept:rag",
        "to_page_id": "concept:llm-wiki",
        "link_type": "concept_related_to",
        "role": "context_expansion",
        "score": 0.84
      }
    ]
  },
  "traversal_paths": [
    {
      "path_id": "path_1",
      "role": "primary_answer_path",
      "used_for_answer": true,
      "score": 0.91,
      "stop_reason": "answer_context_selected",
      "nodes": [
        "source:doc_123",
        "concept:llm-wiki",
        "concept:rag"
      ],
      "edges": [
        {
          "from_page_id": "source:doc_123",
          "to_page_id": "concept:llm-wiki",
          "link_type": "source_mentions_concept"
        },
        {
          "from_page_id": "concept:llm-wiki",
          "to_page_id": "concept:rag",
          "link_type": "concept_related_to"
        }
      ]
    }
  ],
  "retrieval_summary": {
    "source_candidate_count": 15,
    "concept_candidate_count": 10,
    "visited_node_count": 24,
    "returned_node_count": 8,
    "used_source_count": 2,
    "used_concept_count": 4,
    "max_depth": 3,
    "stop_reason": "relevance_decay"
  }
}
```

### 기존 `highlighted_paths` 처리

`highlighted_paths`는 edge 단일 목록처럼 동작하므로 더 이상 핵심 응답 필드로 사용하지 않는다.

프론트는 다음 필드를 사용한다.

- `graph_context.nodes`
- `graph_context.edges`
- `traversal_paths`

`highlighted_paths`가 필요하면 backend adapter에서 `traversal_paths.edges`를 flatten해 backward-compatible field로 생성할 수 있다.

## 13. Scoring role

Graph node role:

```text
seed_source
supporting_source
focus_concept
related_context
candidate
```

Graph edge role:

```text
seed_to_focus
source_to_source
context_expansion
evidence_backfill
```

Path role:

```text
primary_answer_path
supporting_evidence_path
candidate_path
```

## 14. 테스트 기준

Unit test는 real BGE-M3나 real LLM을 로드하지 않는다.

테스트 전략:

- application use case는 fake ports로 테스트한다.
- graph traversal은 deterministic test data로 테스트한다.
- HTTP route는 FastAPI `TestClient`와 fake dependency로 테스트한다.
- real embedding 성능 평가는 `experiments/`나 별도 evaluation script로 분리한다.

권장 평가 지표:

```text
source Recall@3
source MRR
concept Recall@5
selected path sanity check
```

초기 실험 기준:

```text
source vector retrieval은 BGE-M3에서 강하게 동작했다.
hybrid는 BM25 weight가 과하면 vector 정답을 흐릴 수 있다.
graph traversal은 source retrieval을 대체하기보다 concept/path context 보강에 사용한다.
```

## 15. 구현 시 주의사항

- Query Engine MVP에서 vector DB는 필수로 두지 않는다. 작은 데이터셋에서는 PostgreSQL row + in-memory embedding cache로 시작할 수 있다.
- embedding cache는 page `updated_at` 또는 representation hash로 invalidation한다.
- BGE-M3 같은 heavy embedding model은 startup에서 한 번 로드한다.
- LLM answer generator는 selected context 밖의 내용을 단정하지 않는다.
- graph traversal 결과와 answer context는 분리한다. 탐색 후보 전체를 LLM에 넣지 않는다.
- source-source edge는 저장 단계에서 threshold로 정제하고, query 단계에서는 relevance decay로 확장을 제한한다.

## 16. 구현 우선순위

1. `app/modules/query/` skeleton 생성
2. fake repository 기반 graph traversal unit test
3. source/concept representation builder
4. in-memory BGE-M3 embedding adapter
5. BM25 searcher
6. source retrieval + concept hint + backtracking
7. graph traversal + path selection
8. LLM answer generator port와 fake 구현
9. HTTP `POST /api/query`
10. real LLM adapter 연결

MVP에서 가장 먼저 검증할 것은 답변 문장 품질보다 아래 흐름이다.

```text
question
  -> seed sources
  -> focus concepts
  -> traversal_paths
  -> graph_context
```
