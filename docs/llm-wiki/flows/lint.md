# llmPipeline Wiki lint 흐름

## 1. 문서 목적

이 문서에서 `lint`는 Markdown 문법 검사가 아니라 다음 Wiki maintenance API를 뜻한다.

```text
POST /wiki/maintenance/lint
```

이 lint는 ingest가 누적한 active meaning cluster와 reingest 변경 기록을 검사하고, 필요하면 stale 구조를 정리하거나 promotion/relation 후보를 실제 Wiki에 반영한다.

다음 질문에 답하는 것이 목적이다.

- active cluster에서 무엇을 검사하는가?
- `dry_run=true`와 실제 반영은 어떻게 다른가?
- reingest로 무효화된 source ref, concept, relation을 어떻게 찾는가?
- promotion candidate를 언제 LLM에 보내고, prompt가 어떤 결과를 요구하는가?
- LLM 출력은 어떻게 concept Markdown과 DB row로 조립되는가?
- 어떤 결과가 PostgreSQL, MinIO, embedding unit, lint log에 반영되는가?

주요 기준 코드:

- HTTP 진입점: `llmPipeline/app/modules/wiki_ingestion/interfaces/http/routes.py`
- maintenance orchestration: `llmPipeline/app/modules/wiki_ingestion/infrastructure/wiki_maintenance.py`
- lint·materialization: `llmPipeline/app/modules/wiki_ingestion/infrastructure/postgres_wiki_ingestion_repository.py`
- active cluster parser: `llmPipeline/app/modules/wiki_ingestion/infrastructure/active_cluster_markdown.py`
- reingest reconciliation: `llmPipeline/app/modules/wiki_ingestion/infrastructure/source_contribution_reconciliation.py`
- promotion page 조립: `llmPipeline/app/modules/wiki_ingestion/infrastructure/promotion_concept_page.py`

## 2. 한눈에 보는 전체 흐름

```text
POST /wiki/maintenance/lint
  │
  ▼
dry_run / materialize 조건 해석
  │
  ▼
MinIO active.md 로드
  │
  ▼
reingest reconciliation candidate 조회
  │
  ├─ dry_run=false
  │    └─ stale cluster claim/relation 제거
  ▼
active cluster Markdown parse
  │
  ├─ source refs
  ├─ claims
  ├─ relations
  └─ promotion status
  │
  ▼
lint 결과 계산
  ├─ orphan refs
  ├─ invalid relations
  ├─ invalid promotions
  ├─ promotion candidates
  ├─ needs review
  └─ relation candidates
  │
  ├─ dry_run=false
  │    └─ stale document-concept/relation 구조 정리
  │
  ├─ dry_run=false + materialize_promotions=true
  │    ├─ promotion cluster별 LLM concept draft
  │    ├─ llmPipeline Markdown 조립·ref 검증
  │    ├─ 신규 concept 생성 또는 기존 concept 병합
  │    └─ relation materialization
  │
  └─ dry_run=false
       ├─ 활성 contribution 기반 고아 간선 제거
       ├─ operation Markdown·기여 JSON 저장
       ├─ 일일 lint log append
       └─ active/archive object 반영 후 DB commit
  │
  ▼
WikiLintOut 반환
```

## 3. HTTP 요청과 실행 Mode 분기

**역할과 입력**

`workspace_id`, `user_id`, `operation_id`, `dry_run`,
`materialize_promotions`를 검증하고 active cluster, source block, pipeline run
contribution, Wiki page/link와 embedding unit을 읽는다.

HTTP 진입점은 `POST /wiki/maintenance/lint`다.

```json
{
  "user_id": "local-user",
  "workspace_id": "local-workspace",
  "operation_id": "op_lint_1",
  "materialize_promotions": false,
  "dry_run": true,
  "provider": "upstage",
  "model": "solar-pro2",
  "temperature": 0.2,
  "timeout_seconds": 180
}
```

provider/model 계열 값은 신규 promotion concept을 실제 생성할 때만 사용한다.
`dry_run=false`이면 복구 가능한 변경 기록을 남겨야 하므로 `operation_id`가
필수다.

**Mode 결정 규칙**

`dry_run=true`이면 어떤 materialization도 하지 않는다.

| 조건 | 실행 범위 |
| --- | --- |
| dry-run | 검사만 |
| write, promotion off | reconciliation + 고아 간선 + operation/lint log |
| write, promotion on | reconciliation + promotion/relation + 고아 간선 + operation/lint log |

로드한 artifact는 active cluster parse로 넘어간다.

active cluster의 논리 경로와 현재 page/link/embedding 상태는 repository가 workspace 범위로 읽는다. source contribution은 어느 document/run/block이 cluster와 concept에 기여했는지 reconciliation할 때 사용한다.

## 4. Active Cluster Parse와 규칙 검사

**역할과 입력**

cluster Markdown의 claim, evidence ref, promotion 표시와 relation candidate를 구조화한다.

**Parse·판정 규칙**

heading/section 계약에 맞는 값만 읽고 ref는 현재 source block과 대조한다.

구현은 `active_cluster_markdown.py`다. parser가 읽는 대표 구조:

```markdown
## cluster: manufacturing-uncertainty

### Evidence Claims
- 주문 변동성이 생산 계획을 흔든다. [doc-7:B0003]

### Core Relation Candidates
- part_of -> supply-risk [doc-7:B0003]

### Promotion
- status: candidate
```

**출력**

- `orphan_refs`: 현재 block에 없는 ref
- `invalid_promotions`: 승격 필수 정보나 근거가 부족한 항목
- `promotion_candidates`: page 승격 조건을 충족한 cluster
- `needs_review`: 자동 판단하기 어려운 항목
- `relation_candidates`: 허용 type과 근거를 가진 관계

추가로 구조가 잘못된 관계는 `invalid_relations`, promotion 계약이 잘못된 항목은 원인 정보와 함께 `invalid_promotions`에 들어간다. `source_ref_count`는 검사한 ref 규모를, `cluster_count`는 parse된 cluster 규모를 나타낸다.

이 판정 결과는 dry-run이면 곧바로 응답 조립에 사용되고 write mode이면 reconciliation로 넘어간다.

## 5. Write Mode: Reingest Reconciliation

**역할과 입력**

최신 block, 과거 run contribution과 현재 page/link/embedding 구조를 비교해 stale 항목을 정리한다.

**Reconciliation 규칙**

`dry_run=true`에서는 변경 후보만 계산하고, `dry_run=false`에서만 active cluster contribution, document-concept/link와 embedding unit을 갱신한다.

**출력과 다음 단계**

적용 또는 예상되는 reconciliation count와 변경 목록을 만든다. promotion이 꺼져 있으면 lint log/응답으로, 켜져 있으면 promotion 대상 분기로 넘어간다. orphan ref와 stale contribution은 서로 다른 판정으로 유지한다.

`reconciliation_candidates`는 dry-run에서도 반환되고 실제 적용분은 `applied_reconciliations`, cluster Markdown에 적용한 항목은 `applied_cluster_reconciliation`으로 분리한다. DB 구조 정리는 document-concept 관계, page link와 stale embedding unit을 포함할 수 있다.

write는 하나의 repository transaction에서 실행한다. reconciliation과 간선 삭제
SQL은 operation artifact와 일일 로그를 저장하기 전에 실행되지만 아직 commit되지
않는다. 로그 저장이 실패하면 DB 변경을 rollback한다. MinIO object와 PostgreSQL을
하나의 원자 transaction으로 묶을 수는 없다.

## 6A. Promotion 분기: 기존 Concept 병합

**역할과 입력**

promotion candidate slug와 같은 concept page가 이미 있는지 확인한다.

**기존 Concept 병합 규칙**

cluster claim과 검증된 ref만 기존 `Evidence` section에 append하고 중복 근거를 만들지 않는다.

**출력과 다음 단계**

concept Markdown과 embedding unit을 갱신하고 대상은 `merged_promotions`에 기록한다. 신규 slug만 concept generation prompt로 넘어간다.

Evidence 병합은 기존 section을 파싱해 같은 claim/ref 중복을 피하고, 신규 cluster 근거만 추가한다. title/slug가 이미 존재한다는 이유로 모델에게 concept 전체를 다시 작성하게 하지 않는다.

## 6B. Promotion 분기: 신규 Concept 생성

**역할과 입력**

새 concept page에 필요한 의미 내용을 LLM으로 만들고 저장 가능한 Markdown으로 조립한다.

**prompt 구성**

- system: `llmPipeline/prompts/concept_page_generation.system.md` + promotion 전용 suffix
- user: cluster id/title, evidence claims, allowed source refs, relation candidates
- active Wiki schema는 이 stage에 자동 주입되지 않는다.

system prompt는 definition, why it matters, key points, aliases, evidence, related concepts를 JSON으로만 반환하도록 요구한다. evidence의 `source_refs`는 user payload의 allow-list 안에 있어야 하며 모델이 frontmatter나 최종 Markdown 전체를 만들지 않는다.

promotion suffix는 base prompt 뒤에 다음 stage 규칙을 추가한다.

```text
Stage=PromotionClusterConceptPageGeneration
- 하나의 promotion cluster와 evidence/relation/source block을 입력받는다.
- 제공된 evidence만으로 실제 concept draft를 만든다.
- allowed_anchor_refs를 정확한 anchor_block_ids로 사용한다.
- global ref(doc_id:B0001 형태)를 허용한다.
- allow-list에 없는 ref를 사용하지 않는다.
```

user payload의 논리 구조:

```json
{
  "promotion_cluster": {
    "cluster_id": "manufacturing-uncertainty",
    "title": "Manufacturing Uncertainty",
    "claims": [
      {
        "text": "주문 변동성이 생산 계획을 흔든다.",
        "source_refs": ["doc-7:B0003"]
      }
    ]
  },
  "allowed_anchor_refs": ["doc-7:B0003"],
  "relation_candidates": [
    {
      "type": "part_of",
      "target_slug": "supply-risk",
      "source_refs": ["doc-7:B0003"]
    }
  ],
  "source_blocks": [
    {
      "source_ref": "doc-7:B0003",
      "text": "..."
    }
  ]
}
```

source block 원문 전체를 무제한 전달하는 것이 아니라 promotion 판단에 연결된 claim/ref와 그 근거를 전달한다. relation candidate는 related concept 문맥으로 사용할 수 있지만, 모델이 반환한 relation을 바로 DB edge로 쓰지는 않는다.

**모델 출력 계약**

```json
{
  "definition": "수요와 조달 변동으로 생산 계획의 예측 가능성이 낮아지는 상태",
  "why_it_matters": "재고와 납기 결정에 영향을 준다.",
  "key_points": ["주문 변동성", "긴 조달 시간"],
  "aliases": [],
  "evidence": [
    {
      "text": "주문 변동성과 긴 조달 시간이 불확실성을 키운다.",
      "source_refs": ["doc-7:b3"]
    }
  ],
  "related_concepts": ["supply-risk"]
}
```

금지되는 출력:

- Markdown page 전체 또는 frontmatter
- allow-list에 없는 `source_refs`
- cluster evidence에 없는 새 정의·수치·사례
- 존재가 확인되지 않은 related concept을 확정 관계처럼 표현하는 값
- JSON 밖의 설명문

이 prompt가 만드는 것은 의미 draft다. 신규 page 여부는 이미 lint 규칙으로 결정됐고, ref 정규화·citation·section 순서·relation materialization은 이후 llmPipeline 단계가 담당한다.

**Prompt가 Concept Page 결과를 만드는 예시**

입력 cluster:

```text
title: Manufacturing Uncertainty
claim: 주문 변동성과 긴 조달 시간이 생산 계획의 불확실성을 키운다.
allowed_anchor_refs: [doc-7:B0003]
relation_candidate: part_of -> supply-risk
```

prompt는 제공 evidence만으로 definition과 key point를 만들고 ref는 allow-list에서만 고르도록 한다. 그래서 모델은 다음과 같은 의미 draft를 반환할 수 있다.

```json
{
  "definition": "수요와 조달 변동으로 생산 계획의 예측 가능성이 낮아지는 상태",
  "why_it_matters": "재고와 납기 의사결정에 영향을 준다.",
  "key_points": ["주문 변동성", "긴 조달 시간"],
  "aliases": [],
  "evidence": [
    {
      "text": "주문 변동성과 긴 조달 시간이 생산 계획의 불확실성을 키운다.",
      "source_refs": ["doc-7:B0003"]
    }
  ],
  "related_concepts": ["supply-risk"]
}
```

llmPipeline은 이 JSON을 그대로 저장하지 않는다. `doc-7:B0003`을 allow-list와 대조하고, slug/frontmatter/section/citation을 조립해 Concept Markdown을 만든다. 모델이 `doc-9:B0010`을 추가하면 그 evidence는 제거된다. `part_of` relation도 모델의 `related_concepts`만 보고 생성하지 않고 7단계의 page 존재·relation type·근거 검증을 별도로 통과해야 한다.

**llmPipeline 검증·조립**

JSON field를 parse하고 allow-list 밖 ref를 제거한다. slug, frontmatter, `Definition`, `Why It Matters`, `Key Points`, `Aliases`, `Evidence`, `Related Concepts`, `Reference Summary` 순서와 citation은 llmPipeline이 만든다.

예를 들어 모델이 `doc-9:B0010`을 만들었지만 allowed refs가 `doc-7:B0003`뿐이면 전자는 삭제된다. 유효 evidence가 사라진 경우에는 입력 claim 기반 fallback을 사용할 수 있으며 허위 ref를 보존하는 방식으로 복구하지 않는다.

최종 Markdown 예:

```markdown
---
type: concept
slug: manufacturing-uncertainty
promoted_from: cluster:manufacturing-uncertainty
---

# Manufacturing Uncertainty

## Definition
수요와 조달 변동으로 생산 계획의 예측 가능성이 낮아지는 상태입니다.

## Evidence
- 주문 변동성이 생산 계획을 흔든다. [doc-7:B0003]
```

**출력과 다음 단계**

신규 page를 PostgreSQL·MinIO에 저장하고 embedding unit을 pending으로 만들며 `materialized_promotions`에 기록한다. provider/parse/계약 실패 결과는 정상 materialization으로 기록하지 않는다.

신규 candidate마다 generation 호출이 발생할 수 있다. dry-run, reconciliation-only, 기존 slug 병합에는 LLM 호출이 없다.

## 7. Relation Materialization

**역할과 입력**

검증된 cluster relation과 실제 source/target concept page를 Wiki edge로 만든다.

**Relation 생성 규칙**

허용 type은 `part_of`, `child_of`, `uses_or_depends_on`, `contrasts_with`, `supports_or_enables`이며 `related_evidence`는 실제 edge로 만들지 않는다. source와 target page 존재, 서로 다른 page, 근거 ref 유효성을 모두 확인한다.

**출력과 다음 단계**

통과한 relation만 `wiki_page_links`에 저장하고 결과 목록을 archive/log 단계로 넘긴다.

필수 조건은 source cluster id와 연결되는 concept page 존재, target concept page 존재, source/target이 다름, 허용 relation type, 실제 ref 또는 ref를 가진 claim으로 근거 해석 가능함이다. 실패 항목은 materialized 목록에 넣지 않고 invalid/review 정보로 남긴다.

## 8. 분기 합류: Operation Log, 고아 간선, Archive와 응답

**역할과 입력**

검사 결과, reconciliation, merged/materialized promotion, relation과 고아 간선
결과를 복구 가능한 실행 기록으로 확정한다.

**고아 간선 규칙**

`wiki_page_contributions`의 모든 operation JSON과 현재 `active=true`인 JSON을
`sequence_revision` 순서로 읽는다. ingest의 `links`, lint의 `removed_links`와
`added_links`를 차례로 재생해 현재 활성 작업이 지지하는 간선 집합을 만든다.

현재 간선은 endpoint page가 삭제되었거나, operation 로그에서 관리된 적이 있지만
활성 작업 중 어느 것도 더 이상 지지하지 않을 때만 고아 후보가 된다. operation
로그 도입 전에 만들어진 legacy 간선은 로그가 없다는 이유만으로 제거하지 않는다.

**Operation artifact 규칙**

write mode는 변경된 Concept마다 다음 key를 저장한다.

```text
wiki/{workspace_id}/pages/{page_id}/ops/{operation_id}.md
wiki/{workspace_id}/pages/{page_id}/ops/{operation_id}.json
```

lint 기여 JSON은 `content_action=create|append_evidence|none`과
`added_links`, `removed_links`를 기록한다. 이후 restore에서 ingest 기여와 같은
순서로 재생할 수 있다.

실제 적용 순서는 다음과 같다.

```text
1. PostgreSQL transaction 시작
2. reconciliation·고아 간선 DB 변경 실행     아직 미커밋
3. lint operation Markdown·기여 JSON 저장
4. 일일 lint log 저장
5. active/archive object 반영
6. DB commit
```

dry-run에서는 operation artifact, 일일 로그, active/archive object와 DB 변경을
저장하지 않는다.

**출력**

`WikiLintOut`에 orphan/invalid/review/candidate 목록, 변경 count, `merged_promotions`, `materialized_promotions`와 relation 결과를 조립한다. 응답은 변경 요약이며 저장된 Markdown 전문이나 모든 내부 trace를 포함하지 않는다.

전체 주요 응답 필드:

```json
{
  "user_id": "local-user",
  "workspace_id": "local-workspace",
  "operation_id": "op_lint_1",
  "active_path": "wiki/.../clusters/active.md",
  "cluster_count": 1,
  "source_ref_count": 2,
  "orphan_refs": [],
  "promotion_candidates": ["manufacturing-uncertainty"],
  "needs_review": [],
  "relation_candidates": [],
  "invalid_relations": [],
  "invalid_promotions": [],
  "reconciliation_candidates": [],
  "applied_reconciliations": [],
  "applied_cluster_reconciliation": {},
  "materialized_promotions": [],
  "merged_promotions": [],
  "materialized_relations": [],
  "orphan_link_candidates": [],
  "removed_orphan_links": [],
  "operation_artifacts": [],
  "changed_pages": []
}
```

write mode의 변경 위치:

| 결과 | 저장 위치 |
| --- | --- |
| concept metadata | PostgreSQL `wiki_pages` |
| relation | PostgreSQL `wiki_page_links` |
| embedding 검색 단위 | PostgreSQL `wiki_embedding_units` |
| concept 본문 | MinIO concept Markdown |
| 처리 완료 cluster | MinIO active cluster archive |
| 실행 요약 | MinIO lint log |
| 작업별 Concept 본문·기여 | MinIO operation `.md`, `.json` |

설정 실패는 요청 오류로, 예상하지 못한 repository/provider 실패는 실행 실패로 전달한다. 정상 응답의 count와 materialized 목록을 통해 실제 적용 범위를 확인해야 한다.

## 운영상 주의점

- 이 lint는 Markdown syntax lint가 아니라 Wiki 의미 구조 유지보수다.
- `materialize_promotions=true`여도 `dry_run=true`이면 write와 LLM 호출을 하지 않는다.
- orphan ref와 reingest stale contribution은 서로 다른 판정이다.
- promotion prompt에는 active Wiki schema가 자동 주입되지 않는다.
- object storage 부작용 전체가 DB transaction과 동일하게 원자적이라고 가정하지 않는다.
- 고아 간선 판정은 활성 contribution 재생 결과를 사용하며 legacy 간선은 자동
  관리 대상으로 간주하지 않는다.
- lint operation JSON과 ingest operation JSON은 Concept restore에서 같은 순서로
  재생된다.

## 관련 문서

- `docs/evaluation/current-evaluator-metrics.md`
- `docs/llm-wiki/flows/operation-recovery.md`
- `docs/spec/llmpipeline-backend-output-contract.md`
