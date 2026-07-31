# llmPipeline Wiki operation 로그와 복구 흐름

## 1. 문서 목적

이 문서는 ingest, reingest와 lint가 Wiki를 변경할 때 작업별 산출물을 어떻게
남기고, 특정 작업을 제외해야 할 때 Concept 본문과 간선을 어떻게 다시 조립하는지
설명한다.

다음 질문에 답하는 것이 목적이다.

- Source snapshot과 Concept 기여 JSON은 왜 다른가?
- 현재 Wiki를 변경하기 전에 무엇을 operation artifact로 저장하는가?
- reingest에서 이번 작업의 기여가 있는 Concept을 어떻게 구분하는가?
- callback 실패와 422, 409 응답을 어떻게 처리하는가?
- restore가 어떤 기여 JSON을 읽어 Concept과 간선을 재조립하는가?
- lint는 어떤 근거로 고아 간선을 제거하는가?
- llmPipeline과 Backend가 각각 어떤 상태를 소유하는가?

주요 기준 코드:

- ingest 기여 생성: `llmPipeline/app/modules/wiki_ingestion/application/concept_contribution.py`
- ingest operation 저장: `llmPipeline/app/modules/wiki_ingestion/infrastructure/operation_artifacts.py`
- lint operation 저장: `llmPipeline/app/modules/wiki_ingestion/infrastructure/lint_operation_artifacts.py`
- Concept 재조립: `llmPipeline/app/modules/wiki_ingestion/infrastructure/concept_contribution_rebuild.py`
- 복구 실행: `llmPipeline/app/modules/wiki_ingestion/application/restore_wiki_pages.py`
- 고아 간선 판정: `llmPipeline/app/modules/wiki_ingestion/domain/orphan_link_lint.py`
- callback: `llmPipeline/app/modules/wiki_ingestion/infrastructure/pipeline_result_callback.py`

## 2. 전체 생명주기

```text
Backend
  └─ operation_id 생성, processing 저장
       │
       ▼
llmPipeline ingest 또는 lint
  ├─ 변경할 page id와 최종 본문 계산
  ├─ operation별 Markdown·기여 JSON을 불변 key에 저장
  ├─ 현재 Wiki object와 PostgreSQL 변경
  └─ changed_pages 반환 또는 callback
       │
       ▼
Backend
  ├─ page revision과 operation 순서 확정
  ├─ wiki_page_contributions의 active 상태 관리
  └─ 복구 시 page별 keep_contributions 계산
       │
       ▼
llmPipeline restore
  ├─ 남길 ingest·lint 기여 JSON을 순서대로 로드
  ├─ Concept Markdown과 지원 간선 재조립
  └─ restore operation artifact와 callback 결과 반환
```

현재 Wiki는 조회와 서비스에 사용하는 최신 상태다. operation artifact는 특정
작업을 제외하거나 다시 적용할 때 사용하는 불변 재생 기록이다.

## 3. 식별자와 참조 형식

서로 다른 목적의 식별자를 혼동하지 않아야 한다.

| 값 | 예시 | 역할 |
| --- | --- | --- |
| Wiki page id | `page_c2` | Backend가 관리하는 Source·Concept page 식별자 |
| Source page ref | `source:doc_A` | Wiki page link의 Source endpoint |
| Concept page ref | `concept:back-emf` | Wiki page link의 Concept endpoint |
| Source evidence ref | `doc_A:B0003` | 주장과 간선을 지지하는 원문 block |
| Evidence id | `op_ingest_A:claim_001` | 작업 사이에서 충돌하지 않는 evidence 식별자 |
| Operation id | `op_ingest_A` | 한 번의 ingest, lint 또는 restore 작업 |

`doc_id:block_id`는 page link의 endpoint가 아니다. Concept 간 관계의 endpoint는
`concept:slug`이고 `doc_id:block_id`는 그 관계를 지지하는 원문 근거다.

```json
{
  "source": "concept:back-emf",
  "target": "concept:motor-efficiency",
  "relation": "affects",
  "evidence": ["doc_A:B0003"]
}
```

기여 JSON을 만들 때 짧은 `B0003` ref는 `doc_A:B0003`으로 전역화하고, claim id는
`operation_id` prefix를 붙인다. 여러 문서와 여러 작업의 기여를 한 Concept에
합쳐도 ref와 evidence id가 충돌하지 않게 하기 위한 규칙이다.

## 4. 작업별 object storage 계약

### 4.1 Source Page

Source Page는 원문 문서와 1:1로 종속된다. 여러 문서의 의미 기여를 합성하지 않고
작업별 전체 Markdown snapshot만 저장한다.

```text
wiki/{workspace_id}/pages/{source_page_id}/ops/{operation_id}.md
```

Source 복구는 Concept처럼 JSON 조각을 합치는 작업이 아니라 Backend가 남길
revision의 snapshot을 선택하는 작업이다.

### 4.2 Concept Page

Concept는 여러 문서와 lint 작업이 함께 기여할 수 있으므로 Markdown과 기여 JSON을
함께 저장한다.

```text
wiki/{workspace_id}/pages/{concept_page_id}/ops/{operation_id}.md
wiki/{workspace_id}/pages/{concept_page_id}/ops/{operation_id}.json
```

| 파일 | 내용 |
| --- | --- |
| `.md` | 해당 operation이 반영된 Concept 전체 Markdown |
| `.json` | 해당 operation이 Concept에 기여한 재조립 입력 |

기여 JSON의 주요 필드는 다음과 같다.

```json
{
  "schema_version": 1,
  "operation_id": "op_ingest_A",
  "document_id": "doc_A",
  "page_id": "page_c2",
  "concept": {
    "slug": "back-emf",
    "title": "Back EMF",
    "source_document_ids": ["doc_A"],
    "evidence_claim_ids": ["op_ingest_A:claim_001"]
  },
  "evidence_units": [],
  "source_blocks": [],
  "source_key_points": [],
  "links": []
}
```

callback 또는 lint 응답의 `changed_pages`에는 본문을 직접 넣지 않고 key와 hash만
넣는다.

```json
{
  "page_id": "page_c2",
  "page_type": "concept",
  "markdown_key": "wiki/ws/pages/page_c2/ops/op_ingest_A.md",
  "contribution_key": "wiki/ws/pages/page_c2/ops/op_ingest_A.json",
  "content_hash": "sha256:..."
}
```

llmPipeline은 version 또는 revision 번호를 만들지 않는다. Backend가 callback과
operation 순서를 검증한 뒤 revision, 현재 pointer와 contribution 활성 여부를
관리한다.

## 5. Ingest와 reingest 저장 순서

ingest 영속화는 다음 순서를 사용한다.

```text
1. Source·Concept page id 확보                 아직 DB 미커밋
2. same_concept evidence 적용 결과 미리 계산   현재 object 미변경
3. operation Markdown·기여 JSON 저장            불변 key
4. source_blocks와 현재 Source·Concept 반영      PostgreSQL + 현재 object
5. page link, embedding, meaning cluster 반영
6. DB commit
7. embedding job 시작
8. Backend result callback
```

Page id 확보와 이후 DB 변경은 같은 PostgreSQL transaction 안에서 실행된다.
operation artifact 저장에 실패하면 transaction이 rollback되며 현재 Wiki object를
갱신하는 단계로 진행하지 않는다.

Object storage와 PostgreSQL은 하나의 원자 transaction이 아니다. artifact 저장 후
DB commit이 실패하면 사용되지 않는 operation object가 남을 수 있다. 반대로 현재
Wiki는 바뀌었지만 복구 artifact가 없는 상태를 피하기 위해 artifact를 먼저 쓴다.

### 5.1 reingest 대상 제한

reingest가 기존 Concept을 결과에 유지했다고 해서 모두 이번 operation의 기여는
아니다. 다음 Concept만 이번 operation artifact에 포함한다.

1. 이번 실행에서 새로 생성한 Concept
2. 이번 실행의 기여 JSON이 실제로 만들어진 Concept
3. `same_concept` 결정으로 새 evidence가 추가된 기존 Concept

이전 실행에서 만들어졌고 이번에는 그대로 유지된 Concept은 제외한다. 이렇게 해야
이번 작업이 만들지 않은 기여 JSON을 요구하지 않고, operation 로그가 실제 변경분과
일치한다.

## 6. Ingest 결과 callback과 재시도

성공 callback의 핵심 필드는 다음과 같다.

```json
{
  "operation_id": "op_ingest_A",
  "operation_type": "ingest",
  "status": "succeeded",
  "workspace_id": "ws",
  "user_id": "user_1",
  "target_document_id": "doc_A",
  "summary": "Wiki ingest를 완료했습니다.",
  "changed_pages": []
}
```

pipeline 자체가 실패해도 `status=failed`, 실패 summary와 빈 `changed_pages`를
callback한다. Backend에 미리 생성된 operation이 계속 `processing`으로 남지 않게
하기 위한 처리다.

callback은 최대 5회 전송하고 각 실패 뒤 `1, 2, 4, 8`초 간격으로 기다린다.

| 결과 | 처리 |
| --- | --- |
| 2xx | 완료 |
| 네트워크 오류·5xx | 같은 payload 재전송 |
| 422 | Markdown·기여 JSON을 규정 prefix에 다시 쓰고 hash를 재계산한 뒤 재전송 |
| 409 | payload 충돌로 판단하고 HTTP 재시도 중단 |
| 그 외 4xx | 재시도하지 않고 실패 처리 |

최종 전송 실패는 ingest와 embedding 성공을 되돌리지 않는다. `pipeline_runs`를
`notify_pending`으로 바꾸고 저장 manifest에 재전송 정보를 남긴다.

```json
{
  "pending_notification": {
    "callback_url": "http://backend/...",
    "payload": {},
    "status_code": 503
  }
}
```

다음 endpoint가 이 payload를 다시 보낸다.

```text
POST /pipeline/runs/{run_id}/result-callback/retry
```

409가 저장된 pending callback은 다시 보낼 수 없다. 재전송에 성공하면
`pending_notification`을 제거하고 원래 payload의 성공·실패 상태로 run을
복원한다.

현재 callback 요청에는 별도 token 또는 signature header를 추가하지 않는다.
배포 환경의 VPC 내부 통신과 네트워크 정책으로 접근 범위를 제한한다는 전제다.

## 7. Concept 복구와 페이지 재조립

복구 진입점은 다음과 같다.

```text
POST /wiki/restore-runs
```

Backend는 operation 순서와 활성 상태를 기준으로 page마다 남길 기여 목록을 만든다.

```json
{
  "operation_id": "op_restore_1",
  "workspace_id": "ws",
  "result_callback_url": "http://backend/...",
  "rebuild_pages": [
    {
      "page_id": "page_c2",
      "keep_contributions": [
        {"operation_id": "op_ingest_A", "document_id": "doc_A"},
        {"operation_id": "op_lint_B", "document_id": "lint:op_lint_B"}
      ]
    }
  ],
  "restored_pages": [],
  "deleted_pages": []
}
```

llmPipeline은 각 Concept Page를 다음 순서로 재조립한다.

1. `page_id + operation_id`로 기여 JSON key 계산
2. `keep_contributions` 순서대로 JSON 로드
3. JSON의 `operation_id`, `page_id`가 요청과 일치하는지 검증
4. Concept metadata와 전역 evidence 병합
5. source key point 병합
6. ingest와 lint의 link action 재생
7. `ConceptPageAssembler`로 Markdown 재생성
8. restore operation의 새 `.md` key에 저장
9. hash와 최종 지원 간선 반환

결과 예시:

```json
{
  "operation_id": "op_restore_1",
  "operation_type": "restore",
  "status": "succeeded",
  "changed_pages": [
    {
      "page_id": "page_c2",
      "markdown_key": "wiki/ws/pages/page_c2/ops/op_restore_1.md",
      "content_hash": "sha256:...",
      "supported_links": []
    }
  ],
  "failed_pages": []
}
```

한 페이지의 JSON이 없거나 손상되어도 다른 페이지 복구를 중단하지 않는다. 해당
페이지를 `failed_pages`에 `reason=contribution_missing`으로 넣고 전체 상태를
`partially_succeeded`로 반환한다.

Source snapshot의 선택, 삭제 page 처리와 restore 결과에 따른 revision/pointer
변경은 Backend 범위다. llmPipeline의 restore endpoint는 여러 기여가 섞인 Concept
재조립을 담당한다.

## 8. Lint operation 로그

`dry_run=false`인 lint는 `operation_id`가 필수다. lint가 만든 Concept 변경과
간선 변경도 ingest와 같은 `.md`, `.json` key에 저장한다.

lint 기여 JSON은 다음 action을 추가로 가진다.

| 필드 | 의미 |
| --- | --- |
| `content_action=create` | lint promotion으로 Concept 생성 |
| `content_action=append_evidence` | 기존 Concept에 evidence 추가 |
| `content_action=none` | 본문 변경 없이 간선만 변경 |
| `added_links` | 이 operation이 새로 지지하는 간선 |
| `removed_links` | 이 operation이 지원 집합에서 제거하는 간선 |

본문 변경과 간선 변경이 하나의 기여 JSON에 기록되므로 restore가 ingest와 lint를
구분된 별도 복구 방식으로 처리할 필요가 없다. Backend가 남기라고 지정한 ingest와
lint contribution을 한 순서로 전달하면 된다.

non-dry-run lint 순서:

```text
1. PostgreSQL transaction 시작
2. reconciliation과 고아 간선 DB 변경 실행       아직 미커밋
3. lint operation Markdown·기여 JSON 저장
4. 일일 lint log 저장
5. active/archive cluster object 반영
6. transaction commit
```

3번이나 4번이 실패하면 고아 간선 삭제와 reconciliation DB 변경은 rollback된다.
Object storage와 DB의 완전한 원자성은 없지만 DB commit 전에 재생 가능한 operation
기록이 존재하도록 순서를 보장한다.

## 9. 고아 간선 판정과 제거

lint는 `wiki_page_contributions`에서 workspace의 Concept 기여 row를
`sequence_revision` 순서로 읽는다.

- 모든 contribution: 과거 operation 로그로 관리된 적이 있는 간선 판정
- `active=true` contribution: 현재 간선을 지지하는 operation 판정

활성 기여 JSON은 다음 규칙으로 재생한다.

```text
ingest JSON links          → 지원 집합에 추가
lint JSON removed_links    → 지원 집합에서 제거
lint JSON added_links      → 지원 집합에 추가
```

예를 들어 다음 순서라면 최종 지원 집합에는 `A → B`가 남는다.

```text
op-A ingest: A → B 추가
op-B lint:   A → B 제거
op-C lint:   A → B 다시 추가
```

`op-C`가 비활성화되면 최종 지원 집합에서 `A → B`가 사라진다.

현재 DB 간선은 다음 조건 중 하나일 때만 고아 후보가 된다.

1. source 또는 target page가 삭제 상태다.
2. operation 로그가 관리한 간선이지만 활성 기여 재생 결과 아무 작업도 지지하지
   않는다.

기여 로그 도입 전에 만들어져 operation 로그에서 관리된 적이 없는 legacy 간선은
로그가 없다는 이유만으로 삭제하지 않는다. 후보와 실제 삭제 결과는 각각
`orphan_link_candidates`, `removed_orphan_links`에 반환하고 일일 lint log에도
기록한다.

## 10. llmPipeline과 Backend 책임 경계

| llmPipeline | Backend/Flyway |
| --- | --- |
| 기여 JSON 생성과 ref 전역화 | operation 생성과 사용자 권한 검증 |
| operation Markdown·JSON 저장 | `wiki_page_contributions` schema와 row 저장 |
| content hash와 changed_pages 생성 | revision, current pointer, sequence 관리 |
| callback 재시도와 pending payload 저장 | callback payload·prefix·hash 검증 |
| keep contribution 기반 Concept 재조립 | restore 대상 operation과 활성 여부 결정 |
| 활성 contribution 기반 고아 간선 계산·삭제 | restore 결과 적용과 page 활성·삭제 상태 확정 |

llmPipeline은 Backend가 Flyway로 제공하는 table을 소비하지만 migration을 생성하지
않는다. 특히 `wiki_page_contributions.active`, `object_key`, `sequence_revision`이
lint의 고아 간선 재생 입력이다.

## 11. 실패 경계와 운영상 주의점

- object storage와 PostgreSQL은 하나의 transaction이 아니다. 실패한 DB 작업의
  미참조 operation object가 남을 수 있다.
- operation object는 불변 key다. 같은 operation의 422 복구 외에는 현재 Wiki
  object처럼 덮어쓰는 용도로 사용하지 않는다.
- callback 최종 실패는 Wiki 생성 성공과 분리한다. `notify_pending`을 운영에서
  조회하고 재전송해야 한다.
- 409는 같은 operation의 payload 충돌이므로 자동·수동 재전송 대상이 아니다.
- contribution JSON은 Concept 재조립의 입력이다. Backend callback이 끝난 뒤에도
  object를 삭제하면 안 된다.
- 활성 기여 기반 Concept 의미 전체 재작성과 embedding의 완전한 원자 갱신은 별도
  후속 범위다. 이 문서의 복구는 Backend가 선택한 기여 목록을 재조립하는 흐름이다.

## 관련 문서

- `docs/llm-wiki/flows/ingest.md`
- `docs/llm-wiki/flows/lint.md`
- `docs/spec/llmpipeline-backend-output-contract.md`
- `docs/issue/ai/2026-07-27.md`
