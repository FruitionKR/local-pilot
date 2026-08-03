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

## 2A. 외부 API 입출력 계약

operation 흐름의 외부 입력은 HTTP request이고, 출력은 즉시 HTTP response와
비동기 result callback으로 나뉜다.

| 흐름 | Backend → llmPipeline 입력 | llmPipeline의 즉시 출력 | 후속 출력 |
| --- | --- | --- | --- |
| ingest | `POST /pipeline/runs` | `PipelineRunOut` | ingest result callback |
| reingest | `POST /pipeline/reingest-runs` | `PipelineRunOut` | ingest result callback |
| lint | `POST /wiki/maintenance/lint` | `WikiLintOut` | 별도 callback 없음 |
| restore | `POST /wiki/restore-runs` | restore 결과 | 같은 restore 결과 callback |
| callback 재전송 | `POST /pipeline/runs/{run_id}/result-callback/retry` | 저장된 result payload | Backend callback 재전송 |

### 2A.1 Ingest 입력

일반 ingest는 Backend가 먼저 저장한 document를 `document_id`로 지정한다.
llmPipeline은 request의 `user_id`, `workspace_id`를 권한 근거로 사용하지 않고
document row에서 실제 scope와 입력 object URI를 읽는다.

```http
POST /pipeline/runs
Content-Type: application/json
```

```json
{
  "document_id": "doc_A",
  "operation_id": "op_ingest_A",
  "result_callback_url": "http://backend/api/ai-operations/op_ingest_A/result",
  "input_name": "design.md",
  "wait": false,
  "source_page_mode": "auto",
  "concept_page_mode": "auto"
}
```

operation 로그와 callback을 사용할 때 `operation_id`와
`result_callback_url`은 반드시 함께 넣는다. 둘 중 하나만 보내면 request schema
검증에서 422가 반환된다. 두 필드를 모두 생략하면 기존 비-operation ingest로
실행되며 작업별 artifact와 result callback을 만들지 않는다.

주요 입력 필드:

| 필드 | 필수·기본값 | 결정 주체 | 의미와 빈 값 처리 |
| --- | --- | --- | --- |
| `document_id` | 필수 | Backend | 처리할 document id다. 이 row가 없거나 비활성이면 실행하지 않는다. |
| `operation_id` | 선택 | Backend | 작업별 object key와 callback 멱등 식별자다. `null`이면 operation artifact를 만들지 않는다. |
| `result_callback_url` | 선택 | Backend | 최종 성공·실패를 받을 내부 URL이다. `operation_id`와 반드시 함께 존재하거나 함께 `null`이어야 한다. |
| `input_name` | 선택, 기본 `null` | Backend | log와 실행 입력에 표시할 파일명이다. 비어 있으면 document filename 또는 `inline.md`를 사용한다. |
| `wait` | 선택, 기본 `false` | Backend | `false`는 background 등록 후 즉시 반환하고 `true`는 완료까지 HTTP 요청을 유지한다. |
| `source_page_mode` | 선택, 기본 `auto` | Backend | `auto`, `skeleton`, `section-polish` 중 Source Page 생성 전략이다. |
| `concept_page_mode` | 선택, 기본 `auto` | Backend | `auto`, `api`, `full-llm`, `skeleton`, `section-polish` 중 Concept Page 생성 전략이다. |
| `user_id` | 선택, 기본 `null` | 호환 입력 | request 값은 저장 scope의 권한 근거가 아니다. 실제 값은 document row에서 다시 읽는다. |
| `workspace_id` | 선택, 기본 `null` | 호환 입력 | request 값은 저장 scope의 권한 근거가 아니다. 실제 값은 document row에서 다시 읽는다. |
| provider/model 계열 | 선택 | 배포 설정 또는 Backend | LLM provider와 model override다. 비어 있으면 환경 기본값을 사용한다. 세부 필드는 `ingest.md`를 따른다. |

일반 ingest의 Markdown 본문은 request body로 받지 않는다. `document_id`로 조회한
document의 object storage URI에서 읽는다.

### 2A.2 Reingest 입력

reingest는 같은 document의 최신 전체 Markdown을 `input_markdown`으로 받는다.
이전 Source Page와 source block은 `document_id`로 llmPipeline이 조회한다.

```http
POST /pipeline/reingest-runs
Content-Type: application/json
```

```json
{
  "document_id": "doc_A",
  "operation_id": "op_reingest_B",
  "result_callback_url": "http://backend/api/ai-operations/op_reingest_B/result",
  "input_markdown": "# 수정된 문서\n\n새 본문",
  "input_name": "design.md",
  "wait": false
}
```

`input_markdown`은 필수다. llmPipeline은 새 Markdown과 기존 source block을 비교하고
added/modified block만 다시 의미 추출한다. 출력 `changed_pages`에는 유지된 전체
Wiki가 아니라 이번 reingest가 실제로 기여한 Source·Concept만 들어간다.

reingest 추가 필드:

| 필드 | 필수·기본값 | 결정 주체 | 의미와 빈 값 처리 |
| --- | --- | --- | --- |
| `input_markdown` | 필수 | Backend | document의 최신 전체 Markdown이다. 빈 문자열이면 모든 기존 source block을 제거하는 reingest로 해석할 수 있다. |
| `input_name` | 선택, 기본 `null` | Backend | 비어 있으면 기존 document filename, 그것도 없으면 `{document_id}.md`를 사용한다. |

### 2A.3 Ingest와 reingest의 즉시 출력

두 endpoint는 같은 `PipelineRunOut`을 반환한다.

`wait=false`이면 background 실행을 등록한 직후 다음처럼 반환한다.

```json
{
  "run_id": "run_123",
  "status": "running",
  "manifest": null,
  "output_dir": "runs/api_run_123",
  "log_path": "runs/api_run_123/pipeline.log"
}
```

이 응답은 ingest 성공을 의미하지 않는다. 이후 성공 또는 실패는 result callback과
`GET /pipeline/runs/{run_id}`에서 확인한다.

`wait=true`이면 실행이 끝난 뒤 다음처럼 runtime manifest를 포함한다.

```json
{
  "run_id": "run_123",
  "status": "succeeded",
  "manifest": {
    "operation_id": "op_ingest_A",
    "source_page": {},
    "concept_pages": [],
    "operation_artifacts": []
  },
  "output_dir": "runs/api_run_123",
  "log_path": "runs/api_run_123/pipeline.log"
}
```

`wait=true`여도 `operation_id`와 callback URL이 있으면 result callback을 별도로
보낸다. HTTP response의 runtime manifest는 내부 관찰 정보가 포함된 실행 결과이고,
callback의 `changed_pages`는 Backend가 revision을 등록할 operation artifact
목록이다.

`PipelineRunOut` 필드:

| 필드 | 생성 주체 | 의미와 빈 값 처리 |
| --- | --- | --- |
| `run_id` | llmPipeline | 실행 등록 시 만든 UUID다. run 조회와 callback 재전송 path에 사용한다. |
| `status` | llmPipeline | 즉시 응답은 `running`, `wait=true` 성공 응답은 `succeeded`다. background 최종 상태는 run 조회나 callback으로 확인한다. |
| `manifest` | llmPipeline | runtime 산출물 묶음이다. `wait=false` 즉시 응답에서는 `null`이고, 동기 성공 시 객체다. |
| `output_dir` | llmPipeline | pipeline log와 로컬 실행 artifact의 논리 디렉터리다. 요청 `out`이 없으면 run id로 생성한다. |
| `log_path` | llmPipeline | 해당 run의 pipeline log 경로다. |

예시 manifest 필드:

| 필드 | 의미 |
| --- | --- |
| `operation_id` | 이 manifest를 만든 Backend operation id다. operation 없이 실행하면 `null`일 수 있다. |
| `source_page` | 조립된 Source Page metadata와 runtime Markdown이다. 저장 manifest에서는 본문이 제거될 수 있다. |
| `concept_pages` | 생성·유지된 Concept Page runtime 결과다. callback 변경 목록과 동일하다고 가정하면 안 된다. |
| `operation_artifacts` | 이번 operation에서 실제로 저장된 Source·Concept artifact 목록이다. callback의 `changed_pages` 원본이다. |

### 2A.4 Ingest 결과 callback 출력

llmPipeline은 request로 받은 `result_callback_url`에 다음 JSON을 POST한다.

```http
POST {result_callback_url}
Content-Type: application/json; charset=utf-8
```

성공 예시:

```json
{
  "operation_id": "op_ingest_A",
  "operation_type": "ingest",
  "status": "succeeded",
  "workspace_id": "ws_1",
  "user_id": "user_1",
  "target_document_id": "doc_A",
  "summary": "Wiki ingest를 완료했습니다.",
  "changed_pages": [
    {
      "page_id": "page_source_A",
      "page_type": "source",
      "markdown_key": "wiki/ws_1/pages/page_source_A/ops/op_ingest_A.md",
      "content_hash": "sha256:..."
    },
    {
      "page_id": "page_concept_C2",
      "page_type": "concept",
      "markdown_key": "wiki/ws_1/pages/page_concept_C2/ops/op_ingest_A.md",
      "contribution_key": "wiki/ws_1/pages/page_concept_C2/ops/op_ingest_A.json",
      "content_hash": "sha256:..."
    }
  ]
}
```

Source artifact에는 `contribution_key`가 없고 Concept artifact에는 존재한다.

callback 상위 필드:

| 필드 | 생성 주체 | 의미와 빈 값 처리 |
| --- | --- | --- |
| `operation_id` | Backend 입력을 llmPipeline이 전달 | 어떤 Backend operation을 완료하는 결과인지 식별한다. |
| `operation_type` | llmPipeline | 이 callback에서는 항상 `ingest`다. 일반 ingest와 reingest 모두 같은 값이다. |
| `status` | llmPipeline | `succeeded` 또는 `failed`다. callback 전달 성공 여부가 아니라 pipeline 처리 결과다. |
| `workspace_id` | llmPipeline | document row에서 확정한 실제 workspace다. Backend는 요청 operation의 scope와 일치하는지 검증한다. |
| `user_id` | llmPipeline | document row에서 확정한 실제 사용자다. Backend는 이 값을 새로운 권한 근거로 신뢰하지 않고 일치 여부만 확인한다. |
| `target_document_id` | llmPipeline | ingest 또는 reingest 대상 document다. 실패 callback에도 포함된다. |
| `summary` | llmPipeline | 성공 요약 또는 실패 원인 문자열이다. 성공 여부 판정은 `status`를 사용한다. |
| `changed_pages` | llmPipeline | 이번 operation이 실제로 기록한 page artifact다. 실패하거나 변경 page가 없으면 빈 배열이다. |

`changed_pages[]` 필드:

| 필드 | 필수 | 의미 |
| --- | --- | --- |
| `page_id` | 필수 | Backend Wiki page id다. object key와 revision을 이 page에 연결한다. |
| `page_type` | ingest callback에서 필수 | `source` 또는 `concept`이다. Source snapshot과 Concept contribution 처리를 구분한다. |
| `markdown_key` | 필수 | llmPipeline이 저장한 operation Markdown의 bucket 내부 key다. bucket 이름은 보내지 않는다. |
| `contribution_key` | Concept만 필수 | Concept 재조립용 JSON key다. Source snapshot에는 없다. |
| `content_hash` | 필수 | Markdown UTF-8 byte 기준 `sha256:` hash다. Backend가 잘린 쓰기나 다른 본문을 검증한다. |

pipeline 실패 예시:

```json
{
  "operation_id": "op_ingest_A",
  "operation_type": "ingest",
  "status": "failed",
  "workspace_id": "ws_1",
  "user_id": "user_1",
  "target_document_id": "doc_A",
  "summary": "실패 원인",
  "changed_pages": []
}
```

### 2A.5 Lint 입력과 출력

lint는 HTTP 요청 안에서 동기 실행된다.

dry-run 입력:

```json
{
  "user_id": "user_1",
  "workspace_id": "ws_1",
  "dry_run": true,
  "materialize_promotions": false
}
```

실제 적용 입력:

```json
{
  "user_id": "user_1",
  "workspace_id": "ws_1",
  "operation_id": "op_lint_A",
  "dry_run": false,
  "materialize_promotions": true
}
```

`dry_run=false`일 때는 `operation_id`가 필수다. 누락하면 schema 검증에서 422가
반환된다. promotion을 실제 생성할 때만 provider/model 계열 설정을 사용한다.

lint 입력 필드:

| 필드 | 필수·기본값 | 결정 주체 | 의미와 빈 값 처리 |
| --- | --- | --- | --- |
| `user_id` | 선택, 기본 `local-user` | Backend | lint할 사용자 scope다. ingest 호환 필드와 달리 lint DB 조회에 직접 사용한다. |
| `workspace_id` | 선택, 기본 `local-workspace` | Backend | lint할 workspace scope다. |
| `operation_id` | write mode 필수 | Backend | lint Markdown·기여 JSON의 operation id다. dry-run에서는 `null`이어도 된다. |
| `dry_run` | 선택, 기본 `true` | Backend | `true`이면 후보만 계산하고 DB와 object storage를 변경하지 않는다. |
| `materialize_promotions` | 선택, 기본 `false` | Backend | write mode에서 promotion candidate를 실제 Concept으로 만들지 결정한다. dry-run에서는 `true`여도 적용하지 않는다. |
| provider/model 계열 | promotion 시 선택 | 배포 설정 또는 Backend | 신규 promotion Concept을 생성하는 LLM 설정이다. promotion을 만들지 않으면 사용하지 않는다. |

출력 예시:

```json
{
  "user_id": "user_1",
  "workspace_id": "ws_1",
  "operation_id": "op_lint_A",
  "active_path": "wiki/user_1/ws_1/clusters/active.md",
  "cluster_count": 2,
  "source_ref_count": 4,
  "orphan_refs": [],
  "promotion_candidates": [],
  "needs_review": [],
  "relation_candidates": [],
  "invalid_relations": [],
  "invalid_promotions": [],
  "reconciliation_candidates": [],
  "applied_reconciliations": [],
  "applied_cluster_reconciliation": {
    "removed_claims": [],
    "removed_relations": []
  },
  "materialized_promotions": [],
  "merged_promotions": [],
  "materialized_relations": [],
  "orphan_link_candidates": [],
  "removed_orphan_links": [],
  "operation_artifacts": [],
  "changed_pages": []
}
```

dry-run의 `operation_artifacts`, `changed_pages`, `removed_orphan_links`는 비어 있다.
write mode의 `changed_pages`는 `operation_artifacts`와 같은 목록이며 Backend가 lint
operation의 page revision과 contribution을 등록할 때 사용한다. lint에는 별도
result callback이 없으므로 Backend는 이 HTTP response를 직접 처리한다.

`WikiLintOut` 필드:

| 필드 | 의미와 빈 값 처리 |
| --- | --- |
| `user_id`, `workspace_id` | 실제 lint 조회·변경 scope다. |
| `operation_id` | write mode의 lint operation id다. dry-run이면 `null`일 수 있다. |
| `active_path` | 검사한 active meaning cluster Markdown의 object key다. |
| `cluster_count` | active Markdown에서 정상 parse한 cluster 수다. |
| `source_ref_count` | cluster들이 참조한 중복 제거 source ref 수다. |
| `orphan_refs` | 현재 `source_blocks`에서 찾지 못한 `doc_id:block_id` 목록이다. page link 고아 판정과 다른 개념이다. |
| `promotion_candidates` | 근거가 있고 자동 승격 조건을 만족한 cluster id다. 실제 생성 여부는 `materialize_promotions`와 write mode에 달려 있다. |
| `needs_review` | 자동 적용하지 않고 사람 또는 후속 판단이 필요한 cluster id다. 없으면 빈 배열이다. |
| `relation_candidates` | active cluster에서 읽은 relation 후보와 근거다. DB에 저장됐다는 뜻은 아니다. |
| `invalid_relations` | relation type, target 또는 evidence 계약을 통과하지 못한 후보다. |
| `invalid_promotions` | promotion 표시는 있지만 필수 source ref 등이 부족한 cluster와 원인이다. |
| `reconciliation_candidates` | reingest 결과로 stale해진 document–concept, relation, block 구조 후보다. dry-run에서도 반환한다. |
| `applied_reconciliations` | write mode에서 실제 DB 구조 정리를 적용한 결과다. dry-run이면 빈 배열이다. |
| `applied_cluster_reconciliation` | active cluster Markdown에서 제거한 stale claim과 relation을 각각 `removed_claims`, `removed_relations`에 담는다. |
| `materialized_promotions` | write mode에서 새 Concept으로 만든 cluster와 page id다. |
| `merged_promotions` | 새 page 대신 기존 Concept에 evidence를 합친 cluster와 page id다. |
| `materialized_relations` | 검증 후 실제 `wiki_page_links`에 upsert한 relation이다. |
| `orphan_link_candidates` | 삭제 page endpoint 또는 활성 기여 부재 때문에 고아로 판정한 현재 Wiki 간선이다. dry-run에서도 후보를 반환한다. |
| `removed_orphan_links` | write mode에서 실제 삭제한 고아 간선이다. dry-run이면 빈 배열이다. |
| `operation_artifacts` | write mode가 저장한 lint operation `.md`·`.json` metadata다. 변경이 없거나 dry-run이면 빈 배열이다. |
| `changed_pages` | Backend 소비용 alias이며 현재 `operation_artifacts`와 같은 배열이다. |

### 2A.6 Restore 입력과 출력

Backend는 page별 활성 contribution을 operation 순서로 정렬해
`keep_contributions`에 넣는다. 배열 순서가 Concept 조립 순서다.

```http
POST /wiki/restore-runs
Content-Type: application/json
```

```json
{
  "operation_id": "op_restore_A",
  "workspace_id": "ws_1",
  "result_callback_url": "http://backend/api/ai-operations/op_restore_A/result",
  "rebuild_pages": [
    {
      "page_id": "page_concept_C2",
      "keep_contributions": [
        {"operation_id": "op_ingest_A", "document_id": "doc_A"},
        {"operation_id": "op_lint_B", "document_id": "lint:op_lint_B"}
      ]
    }
  ],
  "restored_pages": ["page_source_A"],
  "deleted_pages": ["page_concept_C1"]
}
```

| 필드 | 필수·기본값 | 결정 주체 | 의미와 빈 값 처리 |
| --- | --- | --- | --- |
| `operation_id` | 필수 | Backend | 새 restore Markdown key와 결과 operation id다. |
| `workspace_id` | 필수 | Backend | contribution object prefix를 계산하는 workspace다. |
| `result_callback_url` | 필수, 빈 문자열 불가 | Backend | restore 결과를 받을 내부 URL이다. |
| `rebuild_pages` | 필수 | Backend | llmPipeline이 기여 JSON으로 재조립할 Concept 목록이다. 빈 배열이면 Concept 조립 없이 알림만 처리한다. |
| `rebuild_pages[].page_id` | page마다 필수 | Backend | 재조립할 Concept의 Wiki page id다. |
| `keep_contributions` | page마다 필수 | Backend | 남길 기여 목록이며 배열 순서가 조립 순서다. 빈 배열이면 해당 page는 `contribution_missing` 실패가 된다. |
| `keep_contributions[].operation_id` | contribution마다 필수 | Backend | 읽을 `{operation_id}.json` object를 지정한다. JSON 내부 id와도 일치해야 한다. |
| `keep_contributions[].document_id` | contribution마다 필수 | Backend | 기여 출처를 설명하는 식별자다. 현재 object key 계산에는 사용하지 않지만 Backend 조립 지시 계약에 포함된다. |
| `restored_pages` | 선택, 기본 빈 배열 | Backend | Source snapshot 선택처럼 Backend가 직접 복원한 page id를 결과에 전달하기 위한 알림이다. llmPipeline이 이 page를 다시 쓰지 않는다. |
| `deleted_pages` | 선택, 기본 빈 배열 | Backend | 남은 기여가 없어 Backend가 삭제 대상으로 확정한 page id 알림이다. llmPipeline이 이 배열만으로 page를 삭제하지 않는다. |

llmPipeline이 반환하고 callback으로도 보내는 결과:

```json
{
  "operation_id": "op_restore_A",
  "operation_type": "restore",
  "status": "partially_succeeded",
  "changed_pages": [
    {
      "page_id": "page_concept_C2",
      "markdown_key": "wiki/ws_1/pages/page_concept_C2/ops/op_restore_A.md",
      "content_hash": "sha256:...",
      "supported_links": []
    }
  ],
  "failed_pages": [
    {
      "page_id": "page_concept_C3",
      "reason": "contribution_missing"
    }
  ],
  "restored_pages": ["page_source_A"],
  "deleted_pages": ["page_concept_C1"]
}
```

모든 page가 성공하면 `status=succeeded`, 하나라도 기여 JSON 로드·조립·저장에
실패하면 `status=partially_succeeded`다. restore callback 전송이 최종 실패하면
endpoint도 500으로 실패하며 ingest의 `notify_pending` 저장 경로를 사용하지 않는다.

restore 결과 필드:

| 필드 | 의미와 빈 값 처리 |
| --- | --- |
| `operation_id` | request의 restore operation id다. |
| `operation_type` | 항상 `restore`다. |
| `status` | 모든 rebuild 성공 시 `succeeded`, 하나 이상 실패 시 `partially_succeeded`다. rebuild 대상이 없어도 실패가 없으므로 `succeeded`다. |
| `changed_pages` | llmPipeline이 새 restore Markdown을 저장한 Concept 목록이다. 재조립 대상이 없거나 모두 실패하면 빈 배열이다. |
| `failed_pages` | page별 복구 실패다. 현재 외부 reason은 `contribution_missing`으로 정규화한다. |
| `restored_pages` | request에서 전달한 Backend 직접 복원 page 알림을 그대로 반환한다. |
| `deleted_pages` | request에서 전달한 Backend 삭제 page 알림을 그대로 반환한다. |

restore `changed_pages[]` 필드:

| 필드 | 의미 |
| --- | --- |
| `page_id` | 재조립한 Concept Wiki page id다. |
| `markdown_key` | restore operation으로 새로 저장한 Markdown key다. |
| `content_hash` | 재조립 Markdown의 `sha256:` hash다. |
| `supported_links` | 남긴 ingest·lint contribution의 link action을 순서대로 재생한 최종 지원 간선이다. Backend가 복구 후 graph를 맞출 때 사용한다. |

`failed_pages[]` 필드:

| 필드 | 의미 |
| --- | --- |
| `page_id` | 복구하지 못한 Concept page id다. |
| `reason` | 기여 object 부재, JSON 손상, identity 불일치 또는 조립 실패를 현재 `contribution_missing`으로 전달한다. |

### 2A.7 Pending callback 재전송 입력과 출력

재전송 endpoint는 request body를 받지 않고 path의 `run_id`만 사용한다.

```http
POST /pipeline/runs/run_123/result-callback/retry
```

처리 과정:

1. `pipeline_runs.manifest.pending_notification` 조회
2. 저장된 `callback_url`, `payload`, `status_code` 검증
3. 409 충돌이면 재전송 거부
4. 저장 payload를 Backend에 다시 POST
5. 성공하면 pending 정보를 제거하고 원래 run 상태 복원

HTTP response body는 재전송한 원래 payload다.

```json
{
  "operation_id": "op_ingest_A",
  "operation_type": "ingest",
  "status": "succeeded",
  "changed_pages": []
}
```

run이 없으면 404, pending callback이 없거나 409 충돌이면 409를 반환한다. Backend
callback이 다시 실패하면 pending 정보를 유지하고 endpoint는 500을 반환한다.

pending 저장 필드:

| 필드 | 생성 주체 | 의미와 빈 값 처리 |
| --- | --- | --- |
| `callback_url` | llmPipeline | 원래 ingest request가 지정한 Backend callback URL이다. 비어 있으면 재전송할 수 없다. |
| `payload` | llmPipeline | 마지막으로 전송하려던 성공 또는 실패 JSON 전체다. 재전송 시 새 결과를 만들지 않고 그대로 사용한다. |
| `status_code` | callback client | 마지막 HTTP 오류 code다. 네트워크 오류처럼 HTTP 응답이 없으면 `null`이다. 409이면 terminal conflict로 재전송을 거부한다. |

### 2A.8 내부 데이터 전달

외부 request는 다음 경계를 거쳐 저장과 callback 출력으로 바뀐다.

```text
HTTP JSON
  → Pydantic input schema
  → application command
  → run_lab runtime manifest
  → contribution JSON + operation artifact
  → PostgreSQL/current Wiki 반영
  → HTTP response 또는 result callback
```

| 단계 | 주요 입력 | 주요 출력 |
| --- | --- | --- |
| HTTP schema | Backend JSON | 검증된 `PipelineRunIn`, `WikiLintIn`, `WikiRestoreRunIn` |
| command 조립 | schema + document row | 실제 user/workspace와 기존 Wiki context가 들어간 command |
| pipeline | Markdown, source block, 기존 Concept index | Source·Concept page, link, meaning cluster, contribution |
| persistence | runtime manifest | operation `.md`·`.json`, 현재 Wiki와 DB 변경 |
| callback 조립 | command + `operation_artifacts` | Backend가 읽을 `changed_pages`와 상태 |
| restore | Backend `keep_contributions` | 재조립 Markdown, hash, `supported_links` |

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

기여 JSON 상위 필드:

| 필드 | 생성 주체 | 의미와 빈 값 처리 |
| --- | --- | --- |
| `schema_version` | llmPipeline | 기여 JSON 계약 버전이다. 현재 값은 `1`이다. |
| `operation_id` | Backend 입력을 llmPipeline이 기록 | 이 기여를 만든 ingest 또는 reingest 작업 id다. object key의 operation id와 일치해야 한다. |
| `document_id` | llmPipeline | 기여의 원문 document id다. ref 전역화와 출처 병합에 사용한다. |
| `page_id` | llmPipeline persistence | 이 기여가 속한 Concept Wiki page id다. 복구 요청의 `page_id`와 일치하지 않으면 해당 page 복구를 실패시킨다. |
| `concept` | llmPipeline | 해당 operation이 추출하거나 갱신한 Concept metadata다. 복수 기여를 조립할 때 같은 `slug`인지 확인한 뒤 병합한다. |
| `evidence_units` | llmPipeline | 이 operation이 Concept에 제공한 주장과 근거다. 없으면 빈 배열이다. |
| `source_blocks` | llmPipeline | `evidence_units`가 참조하는 원문 block의 operation 시점 사본이다. 없으면 빈 배열이다. |
| `source_key_points` | llmPipeline | Concept 본문 조립에 사용할 원문 핵심 포인트다. 없으면 빈 배열이다. |
| `links` | llmPipeline | ingest가 이 Concept에 대해 지지한 page link다. restore와 lint 고아 간선 판정에서 지원 집합에 추가한다. 없으면 빈 배열이다. |

`concept` 필드:

| 필드 | 의미와 빈 값 처리 |
| --- | --- |
| `slug` | Concept의 안정적인 의미 식별자다. 같은 page의 모든 기여는 같은 slug여야 한다. |
| `title` | 조립할 Concept Page의 표시 제목이다. 뒤 작업의 비어 있지 않은 값이 병합 결과에 반영될 수 있다. |
| `definition` | 이 operation이 제공한 정의다. 빈 문자열이면 기존의 비어 있지 않은 정의를 지우지 않는다. |
| `anchor_reference_ids` | Concept을 직접 뒷받침하는 `doc_id:block_id` 목록이다. 없으면 빈 배열로 취급한다. |
| `source_document_ids` | Concept 근거를 제공한 document id 목록이다. 복구 시 중복을 제거해 합친다. |
| `evidence_claim_ids` | `evidence_units[].evidence_id` 참조 목록이다. ingest에서는 operation id prefix를 붙여 충돌을 막는다. |

추출 결과에 따라 `concept`에는 `aliases`, `mention_reference_ids`,
`display_reference_ids` 같은 추가 metadata가 들어갈 수 있다. 복구기는 이 배열들도
기여 순서대로 중복 제거해 병합한다.

근거와 원문 필드:

| 경로 | 의미와 빈 값 처리 |
| --- | --- |
| `evidence_units[].evidence_id` | 작업 사이에서 겹치지 않는 주장 id다. ingest에서는 `{operation_id}:` prefix가 붙는다. |
| `evidence_units[].claim` | 원문에서 추출하거나 lint가 추가한 주장 본문이다. |
| `evidence_units[].anchor_reference_ids` | 주장을 지지하는 `doc_id:block_id` 목록이다. 근거가 없으면 빈 배열이다. |
| `evidence_units[].related_concept_slugs` | 이 주장이 연결되는 Concept slug 목록이다. |
| `evidence_units[].source_document_id` | 주장의 출처 document id다. lint 자체 판단처럼 직접 출처가 없으면 빈 문자열일 수 있다. |
| `source_blocks[].document_id` | 원문 block이 속한 document id다. |
| `source_blocks[].block_id` | document 안의 block id다. 전역 ref는 두 값을 `document_id:block_id`로 조합한다. |
| `source_blocks[].text` | 해당 operation이 근거로 사용한 원문 block 본문이다. |
| `source_key_points[].text` | Concept Page에 반영할 핵심 포인트 본문이다. |
| `source_key_points[].anchor_reference_ids` | 핵심 포인트를 지지하는 `doc_id:block_id` 목록이다. |

`links[]`의 공통 필드:

| 필드 | 의미와 빈 값 처리 |
| --- | --- |
| `source` | 간선 출발 endpoint다. `source:{document_id}` 또는 `concept:{slug}` 형식이다. |
| `target` | 간선 도착 endpoint다. Concept 관계는 `concept:{slug}`를 사용한다. |
| `relation` | 두 endpoint 사이의 관계 type이다. |
| `evidence` | 관계를 지지하는 `doc_id:block_id` 목록이다. 관계 종류에 따라 없거나 빈 배열일 수 있다. |

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

| 필드 | 값·기본값 | 의미 |
| --- | --- |
| `artifact_type` | `lint` | ingest 기여와 구분해 link action 재생 규칙을 선택한다. |
| `document_id` | `lint:{operation_id}` | 원문 document가 없는 lint 작업의 합성 출처 id다. |
| `content_action` | `create` | lint promotion으로 새 Concept을 생성했음을 나타낸다. |
| `content_action` | `append_evidence` | 기존 Concept에 evidence를 추가했음을 나타낸다. |
| `content_action` | `none` | 본문 변경 없이 간선만 변경했음을 나타낸다. |
| `added_links` | 기본 빈 배열 | 이 operation이 새로 지지하는 간선이다. restore와 고아 간선 lint가 지원 집합에 추가한다. 각 원소 구조는 ingest의 `links[]`와 같다. |
| `removed_links` | 기본 빈 배열 | 이 operation이 지원 집합에서 제거하는 간선이다. 각 원소 구조는 ingest의 `links[]`와 같다. |

lint JSON의 나머지 `schema_version`, `operation_id`, `page_id`, `concept`,
`evidence_units`, `source_blocks`, `source_key_points`는 ingest 기여 JSON과 같은 의미다.
lint artifact는 본문 action 또는 link action 중 하나 이상이 있을 때만 저장한다.

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
