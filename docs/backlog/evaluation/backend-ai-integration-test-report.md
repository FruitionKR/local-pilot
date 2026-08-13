# Backend–AI 통합 테스트 최종 보고서

## 1. 최종 결론

frozen HEAD `3fb4fc3c2817ec3a0c2acf798599f9d040ead83f`의 Wave 2+ 증거를
재판정한 결과, 현재 결정적 `PRODUCT DEFECT`는 **0건**이다. 기존 결정적 결함은
코드 수정과 집중 회귀 테스트로 해소되었고, 자연어 모델의 route/plan/action·답변
품질은 통합 PASS로 승격하지 않고 `LLM_QUALITY DEFERRED`로 남겼다.

이는 계획의 모든 case가 통과했다는 뜻이 아니다. 실행되어 계약을 입증한 정상·음성
경로, 모델 품질 때문에 보류한 실행 경로, 안전한 fault/callback seam이 없어
실행하지 않은 `HARNESS`/`MISSING` 경로를 구분했다.

## 2. 기준·실행 시각·런타임

- 기준 HEAD: `3fb4fc3c2817ec3a0c2acf798599f9d040ead83f`
- 검증 기간: 2026-08-13~2026-08-14, `Asia/Seoul` (KST)
  - Query context 재검증 및 targeted regression: 2026-08-14
  - Restore summary 재검증: 2026-08-13 14:23~14:30
  - Wave 4 provider smoke: 2026-08-13 22:45~22:49
  - 최종 runtime 배포 확인: 2026-08-14
- Java targeted regression: **65 tests, 0 failures/errors/skips**
- Python targeted regression: **83 tests + 13 subtests, 0 failures**
- 두 targeted 묶음은 모두 green이며 `git diff --check`도 통과했다.
- Java 21 exact 환경에서 compile 성공 후 fix worktree의 검증된 Java class를 기존
  document runtime에 반영했다. 기존 bootRun JVM은 유지했고 health는 HTTP 200이다.
- Pipeline API/task worker에는 검증된 agent schema source를 반영하고 두 대상만
  재시작했다. `AGENT_SKILLS_ENABLED=true`, API/worker health 및 PostgreSQL/Kafka
  연결은 정상이다. 컨테이너 생성·이미지 재빌드·환경/네트워크 변경은 없었다.
- 위 runtime 반영은 실행 증거를 위한 배포 상태이며, 모델 품질 case의 성공을
  의미하지 않는다.

## 3. 결정적 수정과 회귀 확인

| 수정 항목 | 확인된 결과 |
|---|---|
| Query completed-pair filtering | `completed`인 user/assistant 1쌍만 context에 포함하고 pending 또는 failed 쌍은 양쪽 모두 제외한다. 최신 6개·동일 timestamp의 pair/role 순서·session/workspace 격리까지 현재 runtime에서 PASS다. |
| Sync recent-message 4000자 cap | sync/async Query에 전달되는 각 recent message content를 4000자로 제한하는 경계를 고정했다. Query targeted regression은 green이며, 4000자 제한은 안전한 입력 크기 경계로 유지된다. |
| Restore summary count | 실제 `wiki_page` restored/rebuilt resource를 distinct count하고 `changed_resource_count`와 summary를 분리한다. 재검증 fixture는 복원/재구축 2건 + 삭제 1건, 실제 변경 수 3건으로 public detail·DB·focused test가 일치했다. |
| Content artifact persistence | `create_document` 계획 전에 Markdown artifact를 object storage와 AI DB에 기록하고 SHA-256을 검증하며, DB 실패 시 object를 정리한다. artifact id/hash/내용 resolve는 PASS이고 orphan은 0건이었다. 이후 실제 문서 생성이 안 된 원인은 artifact가 아니라 승인 후 모델 re-plan이다. |
| Skill propagation 및 `auto`/`skill_id` validation | Backend command JSON이 `skill_mode`/`skill_id`를 pipeline worker까지 전달한다. `auto`·`off`는 id를 거부하고 `explicit`은 nonblank id를 요구한다. Java/Python 경계 테스트와 worker 전달 테스트가 green이다. |

## 4. 기능별 판정

### Ingest·Chat·Reingest

- **입증 PASS:** 중복 처리 중 재요청의 `409 DOCUMENT_ALREADY_PROCESSING`, partial
  chat pair 연결과 반복 export skip, full 최초 export와 Wiki linkage, OLD→NEW
  reingest, A→B→C reingest, stale race 보호(`changed_resource_count=0`), 문서·워크스페이스·누락 문서·invalid model의 404/400/409 경계.
- **HARNESS NOT COUNTED:** full delta 재생성은 auth refresh 뒤 401로 중단되어
  delta 누적을 입증하지 못했다. 별도 alpha fixture 최초 ingest도
  `INVALID_IDEMPOTENCY_KEY`로 실패해 계획의 전체 I-DOC-01 증거로 세지 않았다.
- **MISSING:** duplicate callback/event와 worker/provider fault 실행 증거가 없다.

### Query·web-search·recent context

- **입증 PASS:** web OFF 내부 근거, web ON route/result 실행, 내부 근거가 없는
  web OFF의 evidence-limited 응답, concurrent ON/OFF session snapshot 격리,
  blank/non-boolean/partial model/unknown·foreign session의 sync/async 음성
  경계, completed-pair filtering·최신 6개·session/workspace 격리.
- **LLM_QUALITY DEFERRED:** Q-03은 내부+Tavily retrieval과 실행은 확인했지만
  최신 버전/install command 합성이 부족했다. Q-07의 context 계약은 PASS지만
  답변 문구·모델 품질은 별도 보류다.
- **HARNESS NOT COUNTED:** Tavily 실패(Q-05)는 안전한 public fault-injection seam이
  없어 실행하지 않았다. remediation은 provider 실패를 주입하되 실제 외부 호출과
  데이터 변경을 만들지 않는 승인된 test seam을 추가하는 것이다.

### Lint

- **입증 PASS:** clean no-op(L-03)에서 변경 없음 계약.
- **HARNESS NOT COUNTED:** L-01/L-02는 deterministic lint target이 없어
  `changed_resource_count=0`을 dry-run/apply 성공 또는 실패로 해석하지 않았다.
- **MISSING:** L-04 concurrent/provider/callback/partial-failure 실행을 위한 고정
  Wiki lint fixture와 안전한 callback/provider fault seam이 없어 완료하지 않았다.
  remediation은 해당 fixture와 fault seam을 준비하는 것이다.

### Tools·Agent approval

- **입증 PASS:** 13-tool schema/allowlist 정합성, `list_root_items`,
  `list_folder_children`, `search_hierarchy`, `get_breadcrumb`,
  `get_document_metadata`의 valid read, `create_folder`, `move_folder`, 한 건의
  read→plan→approval→execute 정상 흐름, stale/duplicate approval·revise·cancel,
  invalid-body rejection.
- **LLM_QUALITY DEFERRED:** `rename_folder`, `move_document`, `rename_document`,
  `create_document`, `apply_document_edit`의 자연어 route/plan/action 선택은
  명시 요청이 clarification 또는 re-plan으로 끝났다. `create_document`는 승인된
  artifact/hash와 계획까지 PASS했지만 `react_replan_plan_no_longer_safe`로 문서
  mutation 전 종료했다. remediation은 모델 재계획이 승인 계획을 덮어쓰지 않도록
  고정 action/plan 회귀 fixture로 재검증하는 것이다.
- **HARNESS NOT COUNTED:** `get_document_content`, invalid/cross-workspace
  read semantics, valid rejection readback/navigation은 auth/runtime seam에서
  중단되어 성공을 추정하지 않았다.

### Skills

- **입증 PASS:** unsafe/ambiguous 이름 등 S-ERR의 안전한 거부 경계, Backend→AI
  skill mode/id 전달 및 auto/explicit/off validation.
- **LLM_QUALITY DEFERRED:** `document-create`, `document-edit`,
  `folder-organize`, `template`, multi-turn Skill lifecycle은 author/route/action
  선택이 완전한 Skill→Tool→document 실행으로 이어지지 않았다. DB fixture E2E도
  auto 경로는 `markdown_edit`/`run_id=null`, explicit id 경로는
  `agent_result_unsupported_action`으로 종료했다. remediation은 published
  Skill/version을 선택한 고정 plan을 실행하는 모델 route 회귀를 추가하는 것이다.
- **HARNESS/MISSING:** S-DRAFT는 선택 가능한 completed run이 없었고, provider
  Skill 정상 실행 fixture도 없었다. 생성·승인 가능한 disposable Skill fixture를
  준비하되 실행 후 public/DB cleanup을 검증해야 한다.

### Markdown Agent·GFM·Restore

- **입증 PASS:** M-ERR의 no-document, invalid range, version conflict,
  foreign document 및 restore의 missing/foreign/unauthorized/tamper/
  restore-of-restore 음성 경계; R-INGEST-01/02, R-MULTI-01, R-STALE-01,
  duplicate restore request, restore summary/linkage/state. Restore summary는
  `페이지 변경 2건 · 삭제 1건 · 링크 제거 0건 · 링크 복원 0건 · 실패 0건`,
  `changed_resource_count=3`으로 일치했다.
- **LLM_QUALITY DEFERRED:** current-runtime R-DOC는 transport/auth/cleanup은
  통과했지만 Agent terminal이 `agent_turn_failed`로 끝나 `result.edit`/
  `markdown_edit`가 없어 저장·apply·restore를 만들지 않았다. GFM 보존과
  selection/section/whole-document/insert-after/multi-turn 정상 결과도 모델
  route/action 품질 때문에 통합 PASS로 세지 않았다.
- **HARNESS NOT COUNTED/MISSING:** lint restore, partial restore/rebuild,
  duplicate callback/event, provider/worker fault는 deterministic public seam이
  없거나 실행되지 않았다. 안전한 fault seam 없이는 실패 결과를 만들어내지 않는다.

### Wave 4 보조 Provider

- **입증 PASS:** Gemini와 Anthropic 모두 model-settings PUT/GET, Query transport와
  source evidence, ingest/index 완료 및 operation success, Markdown create/edit
  persistence, provider/model snapshot을 확인했다.
- **LLM_QUALITY DEFERRED:** 두 provider 모두 answer accuracy, Skill author model
  output, Agent action/Tool selection은 품질 판정으로 보류했다. Gemini Skill
  author 400은 proposal 미생성, Anthropic author는 proposal-ready였지만 어느
  쪽도 Skill publish/Tool 실행 PASS로 세지 않았다.
- **MISSING:** 두 provider의 normal approved Skill Tool/direct DB Skill lane은
  실행 가능한 published/approved fixture가 없어 포함하지 않았다.

## 5. 보류 및 제한의 remediation 원칙

- `LLM_QUALITY DEFERRED`는 transport·persistence 경계를 통과했지만 모델이
  요구된 action/plan/답변을 선택하지 못한 경우다. 이를 현재 제품 결함이나 통합
  PASS로 재분류하지 않고, deterministic plan/action fixture와 결과 저장 증거를
  추가한 뒤 재검증한다.
- `HARNESS NOT COUNTED`/`MISSING`은 auth, fixture, safe fault/callback seam,
  provider failure 제어가 없어 acceptance를 판정할 수 없는 경우다. 해당 경로를
  실행하지 않았다는 사실을 PASS로 세지 않으며, 승인된 격리 fixture와 비파괴
  fault seam이 준비될 때만 재실행한다.
- 따라서 현재 보고서에는 계획 case 전체 PASS, 미실행 fault의 성공, 모델 품질의
  제품 결함을 주장하지 않는다.

## 6. Cleanup·보안·변경 통제

- 모든 fresh public fixture는 해당 lane에서 workspace/document/session을
  public API로 삭제하고 idempotency 재시도로 404/zero active fixture를 확인했다.
  AI artifact/run/job/plan은 필요한 감사 보존 행만 남겼고 orphan 검사는 0건이었다.
- 비밀번호, access/refresh/verification token, provider secret, Authorization
  header, 개인정보 값은 evidence와 본 보고서에 기록하지 않았다. 로그·raw 파일은
  redacted evidence만 사용했다.
- 의도한 커밋 대상은 `docs/api.md`, Backend/AI production 및 test 변경, 본
  보고서 파일이다. 사용자 소유의 무관 변경으로 제외한 것은
  `docs/demo-script.md`, `services/ai/converter/Dockerfile`,
  `services/ai/pipeline/Dockerfile`뿐이다. 이번 보고서 수정은 본 보고서 파일에만
  적용했으며 runtime/data/git를 추가로 수정하지 않았고 commit, push, merge를
  하지 않았다.

## 부록 A. 권위 보고서 경로

아래 경로가 frozen HEAD `3fb4fc3c` Wave 2+ 최종 판정에 사용된 직접 보고서다.

- `/private/tmp/back-ai-e2e-clean2/natural-3fb4-v2/coverage-audit/REPORT.md`
- `/private/tmp/back-ai-e2e-clean2/natural-3fb4-v2/targeted-regression/REPORT.md`
- legacy `/private/tmp/back-ai-e2e-clean2/natural-3fb4-v2/revalidate-query-context/REPORT.md`
  는 `/private/tmp/back-ai-e2e-clean2/natural-3fb4-v2/revalidate-query-context-v2/REPORT.md`로
  대체되었다. 최신 v2 보고서와 `final-runtime-deploy/REPORT.md` 증거를 함께 확인한
  결과, legacy 보고서의 query pair leak blocker는 해소되었다.
- `/private/tmp/back-ai-e2e-clean2/natural-3fb4-v2/revalidate-query-context-v2/REPORT.md`
- `/private/tmp/back-ai-e2e-clean2/natural-3fb4-v2/revalidate-agent-create-artifact-v2/REPORT.md`
- `/private/tmp/back-ai-e2e-clean2/natural-3fb4-v2/skill-db-fixture-e2e/REPORT.md`
- `/private/tmp/back-ai-e2e-clean2/natural-3fb4-v2/revalidate-r-doc-current/REPORT.md`
- `/private/tmp/back-ai-e2e-clean2/natural-3fb4-v2/revalidate-restore-summary/REPORT.md`
- `/private/tmp/back-ai-e2e-clean2/natural-3fb4-v2/wave4-provider-smoke/REPORT.md`
- `/private/tmp/back-ai-e2e-clean2/natural-3fb4-v2/final-runtime-deploy/REPORT.md`

원시 HTTP/Kafka/DB 증거는 각 보고서가 가리키는 동일 디렉터리의 `raw/` 아래에
있으며, 이번 보고서에는 secret이 없는 redacted evidence만 반영했다.
