# Backend–AI 정밀 통합 테스트 최종 보고서

판정 기준일: 2026-08-12 (Asia/Seoul)
기준 계획: `docs/backlog/evaluation/backend-ai-integration-test-plan.md` §25
근거 우선순위: 각 lane의 최신 watcher/final artifact > 이전 중간 상태.
최종 결론: **자동 테스트는 통과했으나 E2E acceptance는 통과하지 못했다.**

## 1. 테스트 요약

이번 실행은 계획된 Backend–AI 기능·예외·provider smoke를 실행하고, API·worker·pipeline·저장 상태는 실행과 별도로 증거 조회했다. 자동 테스트의 authoritative 결과는 Backend fresh XML 379/379, AI·restoration Run evidence 전부 fail/skip 0이지만, 실제 E2E는 ingest callback 계약, public terminal projection, 승인 전 mutation, Tool dispatcher, Skill runtime, provider model availability, restore 선행 조건에서 실패 또는 차단됐다.

따라서 계획 §21에 따라 `PASS`는 기대 결과와 증거가 모두 있는 경우에만 사용했고, `BLOCKED`/`FLAKY`/`NOT RUN`을 성공으로 합산하지 않았다. 확인된 `FLAKY`는 없다. `X-2 expired approval/TTL`은 공개 TTL/강제 만료 계약 부재로 `BLOCKED`이며, 실행을 시도했으나 downstream이 막힌 행도 `BLOCKED`로 유지했다.

정리·보안 결론은 별도 주의가 필요하다. cleanup은 `DELETE 0`이며, 만료·누락 owner auth 때문에 정확한 fixture를 보존했다. 또한 transient worker transcript에서 credential metadata/session-cookie material이 노출된 사실을 확인했으므로, 값은 재현하지 않고 즉시 revocation/rotation과 transcript 접근 제한을 권고한다.

## 2. 기준 commit 및 환경

| 항목 | 값 |
|---|---|
| Git branch | frozen artifact/report evidence에 기록되지 않아 미관측 |
| base SHA | `0b798873821f45274e3aa106cce449302874fb44` |
| 실행 시각 | 2026-08-11 18:55Z 이후 ~ `2026-08-12T05:30:48+09:00` cleanup artifact 시각; lane별 artifact 시각 기준 |
| timezone | Asia/Seoul |
| Java / Gradle | Java `21.0.12`, Gradle `8.14.5` |
| Python | `3.12.13` |
| Backend | access-svc `8081`, document-svc `8080` |
| AI | isolated pipeline API `8001`; 기존/pre-existing `8000` 점유 상태도 관측 |
| DB/runtime | 격리 PostgreSQL `127.0.0.1:15432`, 기존 healthy MongoDB/Kafka/Redis/MinIO |
| tracing | 전체 tracing 활성 여부는 artifact에 기록되지 않음 |
| tree 상태 | 기존 `.codex/skills/review/SKILL.md` 변경과 계획 문서 미추적 상태가 있었음 |

Health는 pipeline/access/document 각각 HTTP 200, Mongo/Kafka/Redis healthy, MinIO live 200이었다. 필수 topic `ai.ingest.command`, `ai.query.command`, `ai.agent.command`, `ai.maintenance.command`, `ai.task.event`, `document.edit.event`가 존재했고 ingest/query/agent/maintenance worker 및 edit consumer liveness도 확인됐다. 본 실행에서는 product/test source, config, API/data schema를 수정하지 않았고 commit/merge/push를 하지 않았다.

## 3. Provider/model

| 경로 | 요청 model | 실제 결과 |
|---|---|---|
| OpenAI 대표 | `openai/gpt-5-nano` | direct structured checks 3개 PASS. 기능 matrix는 callback/projection 등으로 BLOCKED/FAIL 혼재 |
| Gemini 보조 | `gemini/gemini-2.5-flash-lite` | key correction 후 실제 provider HTTP 404; 정상 smoke FAIL |
| Anthropic 보조 | `claude/claude-3-5-haiku-20241022` | key correction 후 실제 provider HTTP 404/not_found; 정상 smoke FAIL |
| Tavily | web ON Q-02/Q-03/Q-06 true 경로 후보 | 실제 호출·credit·usage는 관측하지 못함; 비용은 sensitivity만 기록 |

모델은 임의로 바꾸지 않았다. Gemini/Claude 404는 provider까지 도달한 실제 응답이고, 해당 계정에서 사용 가능한 model/catalog와 권한을 확인한 뒤 양쪽 계약을 정합화해야 한다. 초기 provider direct shell의 missing-key 차단은 provider 미도달 `BLOCKED`이며, 이후 프로세스 메모리에만 key를 주입한 correction 결과가 실제 404를 확정한다.

## 4. 서비스 상태

| 구성요소 | 상태 및 증거 |
|---|---|
| access-svc | HTTP 200 UP, 8081 |
| document-svc | HTTP 200 UP, 8080 |
| pipeline API | HTTP 200, isolated 8001 |
| ingest/query/agent/maintenance worker | launchd 프로세스 liveness PASS |
| edit event consumer | `document.edit.event` liveness PASS |
| PostgreSQL | 격리 DB 3개·role 6개·SCRAM/own DB 연결 PASS; AI core DB 접근 거부 PASS |
| MongoDB | `fruition-mongodb-dev` healthy |
| Kafka | `fruition-kafka-dev` healthy, 필수 topic 존재 |
| Redis | `PONG` |
| MinIO | live 200 |
| 내부 경계 | wrong token 401, current token route reached/검증 응답 확인; AI→document current token accepted 후 fixture 없음 404 |

공통 런타임 차단은 health 자체가 아니라 callback/status 및 port alignment였다. Agent status는 일부 isolated pipeline `8001` 결과와 Backend가 조회하는 `8000` endpoint가 갈라져 internal `completed/failed`가 public `queued`로 남았다.

## 5. 자동 테스트 결과

자동 테스트는 E2E acceptance와 별도 집계한다. 아래는 최신 `automated-test-evidence.md` 및 coverage audit가 보존한 authoritative count다.

| 실행 | tests passed | subtests | warnings | failures/skips | raw output |
|---|---:|---:|---:|---:|---|
| Backend fresh XML | 379 | - | - | 0 / 0 | XML fresh artifact |
| Backend selector view | 271 | - | - | 0 / 0 | Run evidence; selector raw command 미보존 |
| Backend extra global-filter classes | 108 | - | - | 0 / 0 | Run evidence |
| AI-A | 314 | 3 | 0 | 0 / 0 | raw pytest 미보존 |
| AI-B | 117 | 12 | 1 | 0 / 0 | raw pytest 미보존 |
| AI-C | 234 | 32 | 1 | 0 / 0 | raw pytest 미보존 |
| AI-D | 101 | 33 | 0 | 0 / 0 | raw pytest 미보존 |
| AI-E | 51 | 8 | 1 | 0 / 0 | raw pytest 미보존 |
| AI-F authoritative 후속 실행 | 35 | 0 | 1 | 0 / 0 | 두 실행 중 후속 수치 사용 |
| restoration | 70 | 2 | 5 | 0 / 0 | raw pytest 미보존 |

Backend fresh XML 합계는 **379 tests / 81 classes-suites, 379 passed, 0 failed, 0 skipped**다. AI-A~F는 합산 852 passed/88 subtests, restoration은 70 passed/2 subtests이며, raw stdout·selector command·개별 failure list가 독립 artifact로 없다는 제한을 유지한다. Backend warning은 failure로 승격하지 않았다.

## 6. 전체 시나리오 결과표

상태는 계획의 `PASS/FAIL/FLAKY/BLOCKED/NOT RUN`만 사용했다. 괄호 안은 부분적으로 관측된 경계이며 전체 성공으로 승격하지 않은 이유다.

### Ingest

| ID | 판정 | 요약 |
|---|---|---|
| I-DOC-01 | FAIL | 일반 문서 요청은 접수됐으나 pipeline의 `result_callback_url` 접근 실패, Wiki 0 |
| I-DOC-02 | PASS (authoritative duplicate/dedup verdict) | 동일 document/revision dedup, snapshot·replay·scope validation 확인. Coverage audit의 workspace/model/cross-workspace evidence는 별도 provenance이며 이 verdict와 혼합하지 않음 |
| I-CHAT-P-01 | FAIL | partial preview/export는 생성됐으나 후속 pipeline 실패, membership/Wiki 미입증 |
| I-CHAT-F-01 | FAIL | partial과 동일 hash로 full export가 `status=skipped`, full state/run 없음 |
| I-CHAT-F-02 | BLOCKED | query가 6회 poll 후 pending, full export `400 EMPTY_CHAT_WIKI_EXPORT` |
| I-RE-01 | FAIL | OLD 저장·`needs_reingest=true`는 확인, pipeline 500 실패 |
| I-RE-02 | FAIL | stale 409와 NEW v3 저장은 확인, reingest pipeline 500 |
| I-RACE-01 | FAIL | A/B overlap는 실행됐으나 양쪽 persistence 전 실패, stale callback 미입증 |
| I-ERR validation/scope/model/dedup/deleted | PASS | malformed, missing/cross-workspace, bad model, stale/idempotency, deleted doc 계약 PASS |
| I-ERR worker/provider/malformed-command/callback/concurrent | BLOCKED | deterministic pipeline failure로 downstream event/idempotency 결과 관측 불가 |

I-DOC-02의 duplicate/dedup PASS는 `openai-ingest-matrix.md`의 해당 시나리오와 fixture evidence에 대한 authoritative verdict다. `coverage-gap-audit.md`의 workspace/model/cross-workspace coverage 기록은 matrix coverage provenance로만 보존하며, I-DOC-02의 dedup verdict 또는 그 실행 입력으로 혼합하지 않는다.

### Query/web search

| ID | 판정 | 요약 |
|---|---|---|
| Q-01 | BLOCKED | web OFF snapshot와 no web evidence는 확인했으나 marker ingest 실패로 내부 근거 답변 미검증 |
| Q-02 | FAIL | web ON 동일 질문이 empty-body 401, source/Tavily evidence 없음 |
| Q-03 | FAIL | internal+web 질문이 empty-body 401, 양쪽 source 없음 |
| Q-04 | PASS | web OFF·근거 없는 질문에 안전한 refusal, source 0, web 미호출 |
| Q-05 | BLOCKED | Tavily fault-injection contract가 없어 안전한 강제 실패 실행 불가 |
| Q-06 | BLOCKED | false/true boolean Redis snapshot isolation subcheck는 PASS. true run은 60초 초과 후에도 `PENDING`이며 source와 terminal이 없어 전체 acceptance는 BLOCKED |
| Q-07 | BLOCKED | same/new session isolation PASS이나 내부 six-message truncation 계약은 API 미노출 |
| Q-08 | PASS | blank/flag/model/session/workspace 예외 계약 PASS |

### Lint

| ID | 판정 | 요약 |
|---|---|---|
| L-01 | PASS | dry-run succeeded, changed 0, operation 없음, Wiki unchanged |
| L-02 | BLOCKED | AI run succeeded empty result이나 Backend operation은 processing, diff/Wiki 0 |
| L-03 | PASS | clean no-op changed 0, spurious operation/log 없음 |
| L-04 | FAIL | malformed `dry_run` coercion defect; auth/workspace/model/scope는 PASS, callback/partial는 BLOCKED |

### 13 Tool, approval, Skill, Markdown, Log/Restore

| 계획 ID/보조 ID | 판정 |
|---|---|
| Tool 13 전체 | 정상 runtime은 BLOCKED 또는 static FAIL; 상세는 §10 |
| A-1/A-2 정상 proposal/apply | BLOCKED |
| B fabricated approval | FAIL |
| C-1 replay/idempotency | BLOCKED (revision replay 자체는 PASS) |
| D-1 stale/base | PASS |
| D-2 reject/cancel | BLOCKED (no-apply는 PASS, terminal propagation은 BLOCKED) |
| D-3 injection | BLOCKED (pipeline safety는 PASS, public propagation은 BLOCKED) |
| X-1 wrong workspace | PASS |
| X-2 expired approval/TTL | BLOCKED (public TTL/force-expire contract 없음; 실행 가능한 공개 probe 부재) |
| X-3 correlation | BLOCKED |
| S-CREATE | BLOCKED (baseline Markdown proposal PASS, Skill auto-selection BLOCKED) |
| S-EDIT | FAIL (latest aligned worker output contract failure) |
| S-FOLDER | BLOCKED |
| S-TEMPLATE | BLOCKED (clarification policy PASS, Skill execution BLOCKED) |
| S-MULTITURN | FAIL (latest aligned safety-source validation) |
| S-DRAFT | FAIL (latest aligned classifier inconsistency) |
| S-ERR | PASS (fail-closed checks) |
| S-AUTO/S-EXPLICIT | BLOCKED (no published Skill; auto fell back to generic create, explicit `SKILL_NOT_FOUND`) |
| M-01 | BLOCKED |
| M-02 | BLOCKED (proposal-only; applied diff 없음) |
| M-03 | BLOCKED |
| M-04 | BLOCKED |
| M-05 | BLOCKED |
| M-06 | BLOCKED (agreement/isolation PASS, public status BLOCKED) |
| M-07 | BLOCKED (`AGENT_SKILLS_ENABLED=false` 당시) |
| GFM 보존 | BLOCKED (input/stored/proposal boundary만 확인; applied diff 없음) |
| M-ERR no-doc/cross/version/range | PASS |
| M-ERR omitted/injection/tool | BLOCKED (internal reject는 확인, public projection 미확인) |
| M-ERR fabricated approval | FAIL |
| R-DOC-01 | BLOCKED |
| R-INGEST-01 | BLOCKED |
| R-INGEST-02 | BLOCKED |
| R-LINT-01 | BLOCKED |
| R-MIXED-01 | BLOCKED |
| R-MULTI-01 | BLOCKED |
| R-PARTIAL-01 | BLOCKED |
| R-STALE-01 | BLOCKED (garbage-token taxonomy의 409만 확인; valid preview-state-change race는 미실행) |
| R-IDEMP-01 | BLOCKED |
| R-ERR | BLOCKED (negative checks 일부 PASS, full provider/worker path 미실행) |
| R-STATE-TIMELINE | FAIL |

### Provider smoke

| Provider path | 판정 |
|---|---|
| OpenAI direct structured 3 checks | PASS |
| OpenAI 전체 functional matrix | BLOCKED |
| Gemini direct/public | FAIL (key correction 후 exact model HTTP 404) |
| Claude direct/public | FAIL (key correction 후 exact model HTTP 404; 일부 public Agent는 queued) |

## 7. Ingest 결과

일반 문서 A/B 생성 자체, request snapshot, dedicated workspace, stale/idempotency/missing/cross-workspace 예외는 확인됐다. 그러나 실제 ingest의 핵심 성공 조건인 pipeline 처리 → Wiki page/link/embedding/contribution → callback → Backend terminal 상태는 달성되지 않았다.

관측된 공통 오류는 `500: 'PipelineRunCommand' object has no attribute 'result_callback_url'`다. artifact의 현재 command/schema는 `log_callback_url` 경계를 사용하고 runner가 `result_callback_url`을 읽는 것으로 기록됐다. A/B/OLD/NEW/chat export pipeline run은 failed 또는 downstream operation processing으로 남고 Wiki graph는 `nodes=0, edges=0`이었다. I-CHAT-P-01은 partial preview/export까지만 PASS이고 follow-up pipeline 실패, I-CHAT-F-01은 partial/full dedup key가 `selection_mode`를 구분하지 않아 skipped, I-CHAT-F-02는 query 미완료로 `EMPTY_CHAT_WIKI_EXPORT`다.

정리하면 일반 ingest 최초 성공, chat partial/full/delta, reingest 결과 현재성, race stale callback은 모두 acceptance FAIL/BLOCKED이며, 잘못된 입력·scope·model·dedup 경계만 PASS다. I-DOC-02 duplicate/dedup PASS는 coverage audit의 workspace/model/cross-workspace evidence와 별도로 판정했다.

## 8. Query 웹 검색 ON/OFF 결과

Q-01/Q-04/Q-07/Q-08에서 `allow_web_search=false` snapshot과 invalid contract가 보존됐다. Q-04는 근거 없는 질문에 `제공된 근거에서 질문에 직접 답할 내용을 찾지 못했습니다.`를 반환했고 source 0/web 미호출이었다. Q-01은 동일 구조를 보였지만 marker ingest 실패로 내부 근거 성공을 검증할 수 없어 BLOCKED다.

Q-02/Q-03은 `allow_web_search=true`가 DB/메시지에 보존됐지만 약 3초 후 empty-body HTTP 401, assistant `failed`, source 0이었다. Tavily 실제 호출·`web_search_started`·matching Kafka event는 관측되지 않았다. 따라서 web ON 정상 경로는 FAIL이고, 401의 구체적 downstream 구성요소는 artifact만으로 확정하지 않는다.

Q-06 동시 false/true 요청은 Redis에 각각 `false`/`true`, provider/model/session/workspace를 별도로 저장해 snapshot isolation subcheck는 PASS다. false run은 completed였지만 true run은 60초 초과 후에도 `PENDING`이고 source와 terminal이 없었다. 따라서 Q-06 overall은 BLOCKED이며, final web-enabled execution을 PASS로 승격하지 않는다. Q-07은 same/new session data leak 없음은 확인했지만 내부 최근 6-message payload/truncation은 public API가 노출하지 않아 BLOCKED다.

## 9. Lint 결과

L-01과 L-03은 empty Wiki의 dry/no-op 경로에서 expected zero change와 operation 부재를 확인해 PASS다. L-02 및 concurrent L-04 apply는 AI run이 succeeded/empty result였지만 Backend operation이 `processing`, changed 0, callback terminal 없음으로 남아 required mutation/audit를 입증하지 못해 BLOCKED다.

L-04의 auth, bad workspace, invalid model, operation scope mismatch는 PASS다. 다만 `dry_run:"invalid"`에서 empty 401, `dry_run:1`에서 202 accepted가 관찰된 입력 coercion defect는 FAIL이다. callback mismatch와 partial result는 public fault injection이 없어 BLOCKED다. 성공 Wiki fixture 확보 후 L-01→L-04와 operation terminal/diff/query 재검증이 필요하다.

## 10. Tool 존재·실행 결과

13개 frozen Tool의 purpose와 runtime 경계를 분리해 기록한다. AI allowlist는 13개 전체이고 AI worker dispatcher/Backend 등록은 9개(읽기 4 + mutation 5), 누락은 4개다. 등록 9개의 정상·invalid 호출은 downstream `X-Internal` callback auth 401로 막혔으므로 functional PASS가 아니다.

| Tool | purpose | AI allowlist | AI worker dispatcher | Backend registration/endpoint | normal/invalid/cross/auth runtime |
|---|---|---|---|---|---|
| `list_root_items` | workspace root 항목 조회 | PASS | 등록 | 등록; internal read endpoint | normal/invalid: downstream `X-Internal` callback auth 401; wrong `X-Agent` token 401, correct token+malformed body 400; regular unauth 401 |
| `list_folder_children` | 폴더 하위 항목 조회 | PASS | 등록 | 등록; internal read endpoint | normal/invalid: downstream `X-Internal` callback auth 401; cross regular folder read 200, unauth 401 |
| `search_hierarchy` | 계층 검색 | PASS | 미등록 | 미등록; endpoint route만 존재 | normal/invalid: 실행 불가; regular search 200, unauth 401 |
| `get_breadcrumb` | resource breadcrumb 조회 | PASS | 미등록 | 미등록; endpoint route만 존재 | normal/invalid: 실행 불가; regular breadcrumb 200, unauth 401 |
| `get_document_metadata` | 문서 metadata/version 조회 | PASS | 등록 | 등록; internal read endpoint | normal/invalid: downstream `X-Internal` callback auth 401; cross-workspace 404, unauth 401 |
| `get_document_content` | canonical Markdown 조회 | PASS | 등록 | 등록; internal read endpoint | normal/invalid: downstream `X-Internal` callback auth 401; cross-workspace 404; metadata `current_version`와 content `edit_revision` contract mismatch |
| `create_folder` | 폴더 생성 | PASS | 등록 | 등록; internal execute endpoint | normal/invalid: downstream `X-Internal` callback auth 401; wrong `X-Agent` token 401 vs correct malformed 400; cross/auth regular boundary PASS |
| `rename_folder` | 폴더 이름 변경 | PASS | 등록 | 등록; internal execute endpoint | normal/invalid: downstream `X-Internal` callback auth 401; cross/auth regular boundary PASS |
| `move_folder` | 폴더 이동 | PASS | 등록 | 등록; internal execute endpoint | normal/invalid: downstream `X-Internal` callback auth 401; cross/auth regular boundary PASS |
| `move_document` | 문서 이동 | PASS | 등록 | 등록; internal execute endpoint | normal/invalid: downstream `X-Internal` callback auth 401; cross/auth regular boundary PASS |
| `rename_document` | 문서 이름 변경 | PASS | 등록 | 등록; internal execute endpoint | normal/invalid: downstream `X-Internal` callback auth 401; cross-workspace 404, unauth 401 |
| `create_document` | Markdown 문서 생성 | PASS | 미등록 | 미등록; endpoint route만 존재 | normal/invalid: 실행 불가; regular API auth boundary PASS |
| `apply_document_edit` | 승인된 Markdown 편집 적용 | PASS | 미등록 | 미등록; endpoint route만 존재 | normal/invalid: 실행 불가; revision contract mismatch 포함, regular API auth boundary PASS |

`X-Agent` wrong token은 401, correct `X-Agent` token + malformed body는 400으로 filter/schema 경계가 구분됐다. 등록 handler까지 도달한 뒤 downstream `X-Internal` callback auth는 401이었다. frozen 13 밖의 `list_agent_run_artifacts` worker dependency는 Backend handler 없이 존재한다. 최소 수정은 callback token/endpoint 정렬, 누락 4 dispatcher와 `current_version` 대 `edit_revision` contract 정합화, extra dependency 제거 또는 계약 편입이며, 이후 13개 각각 정상·invalid·cross-workspace를 재실행해야 한다. (근거: `tool-contract-13.md` `Contract boundaries`/`Per-tool results`, §10.)

## 11. Skill 분류별 결과

초기 matrix에서는 API `AGENT_SKILLS_ENABLED=false`, worker만 동작하는 분리 상태였고 대부분 route가 blocked였다. 최신 aligned watcher에서는 isolated Pipeline API와 agent-task-worker 모두 agent flag가 true이고 Kafka group member 1개/12 partition/lag 0, route exposure가 확인됐다. 이는 **runtime alignment precondition PASS**이지 Skill 기능 acceptance PASS가 아니다. 아래의 pipeline 상태 조회는 실행이 아니라 evidence inspection이다.

최신 결과:

- `S-CREATE`: no-reference author가 `proposal_ready`와 Markdown proposal을 반환했지만 persisted Skill/version·auto-selection·실제 create는 없음. baseline proposal PASS, 전체는 BLOCKED.
- `S-EDIT`: worker가 OpenAI 출력 후 `MarkdownOutputContractError`로 failed, public status는 queued. FAIL.
- `S-FOLDER`: `folder_organize`와 nested run까지 갔으나 standalone `agent_worker.py`/nested dispatcher가 없어 plan·approval·tool·callback 미관측. BLOCKED.
- `S-TEMPLATE`: fixed reference를 일반 Markdown edit로 잘못 실행하지 않고 `clarify`한 정책은 PASS지만 authoring/approval 실행은 BLOCKED.
- `S-MULTITURN`: pending proposal 후속 publish가 `Skill authoring safety issue text must exist in its source`로 400. FAIL.
- `S-DRAFT`: corrected source-run correlation은 관측되지 않았고, `/skills/draft-from-runs/preview`의 400 `Skill request could not be classified consistently` response만 검증됐다. 정확한 corrected run correlation은 verified로 주장하지 않는다. FAIL.
- `S-AUTO`: published Skill이 없어 generic `markdown_create`, `selected_skill_id=null`, 후보 []로 처리. Skill 적용 acceptance는 BLOCKED.
- `S-EXPLICIT`: 선택 가능한 published Skill이 없어 `SKILL_NOT_FOUND` 422. 현재 fixture에서 예상되는 fail-closed 결과지만 정상 Skill 선택은 BLOCKED.
- `S-ERR`: ambiguous, unsafe, cross-workspace, missing planning read, unsupported tool, tampered approval 경계가 fail-closed로 동작해 PASS. 실제 Skill execute는 수행하지 않았다.

`create_document`/`apply_document_edit`는 policy preview에는 나타나지만 published Skill/approved plan/dispatcher가 없어서 실행 성공을 주장하지 않았다. 재검증은 API+worker flag, `/agent/runs/*`, nested worker, classifier/output contract와 public callback을 각각 확인하는 순서여야 한다.

## 12. Markdown Agent 결과

M-01~M-06은 direct AI DB의 internal proposal 결과와 Backend public status가 갈라졌다. M-01은 internal `completed`, generated Markdown 존재지만 Backend queued·stored document empty/version 1이라 mutation 미주장. M-03/M-04/M-05도 scope/whole-document/insert-after proposal은 completed이나 public queued·stored doc unchanged라 BLOCKED다. M-06은 같은 session follow-up이 합의된 checklist를 유지하고 fresh session이 합의를 유출하지 않아 isolation은 PASS지만 public propagation 때문에 전체 BLOCKED다.

M-02는 selection line 3, `scope_expanded=false`, `changed=false`, replacement가 정확히 선택 line에 한정된 proposal-only evidence다. 문서 apply와 applied diff는 없었으므로 M-02 overall은 BLOCKED다. M-07은 skills API가 []인 상태라 BLOCKED다. no-doc/cross-workspace/version/range 예외는 각각 404/404/409/400 PASS, omitted target/injection/disallowed tool은 internal reject는 확인됐지만 public status가 queued라 BLOCKED, fabricated approval은 실제 revision을 만들어 FAIL이다. GFM overall도 input/stored/proposal boundary만 확인됐고 applied diff가 없어 BLOCKED다.

## 13. 멀티턴 결과

Query Q-07에서 같은 session의 “capital of France?” → “its population?”과 새 session의 동일 후속 질문을 비교했다. same/new session 간 문맥 유출은 없었고 7개 recent-limit probe도 수락됐지만 내부 six-message payload와 정확한 truncation은 API에서 관측할 수 없었다. 따라서 session isolation은 확인됐고 recent-limit acceptance는 BLOCKED다.

Markdown M-06에서는 “이 부분을 체크리스트로 바꾸는 게 좋겠어” 후 “그렇게 해줘”가 같은 session의 agreement를 유지했으며 fresh session은 그 agreement를 받지 못했다. 다만 두 문서 모두 version 1, apply 없음이고 public run은 queued다.

Skill S-MULTITURN은 latest aligned runtime에서 pending proposal 후속 publish가 safety source validation 400으로 중단됐다. 이는 단순 queue 차단이 아니라 재현된 validation contract FAIL이며, source text propagation과 publish state machine을 수정 후 proposal→follow-up→publish→approval을 재검증해야 한다.

## 14. Log 및 Restore 결과

Log read API의 order/filter/page/size/detail/bad-query와 document create는 PASS다. 그러나 successful Wiki mutation fixture가 없어 계획의 restore 본체를 실행할 수 없었다.

| 범위 | 결과 |
|---|---|
| `R-DOC-01` | document_edit terminal log/valid preview 없음 → BLOCKED |
| `R-INGEST-01` | ingest Wiki contribution/page 없음, preview 400 → BLOCKED |
| `R-INGEST-02` | A→B→C 영향 범위 fixture 없음 → BLOCKED |
| `R-LINT-01` | lint preview 400 → BLOCKED |
| `R-MIXED-01` | ingest/lint 변경 0 → BLOCKED |
| `R-MULTI-01` | multi-page manifest 없음 → BLOCKED |
| `R-PARTIAL-01` | fault injection/성공 Wiki 없음 → BLOCKED |
| `R-STALE-01` | garbage-token taxonomy에서만 409 `RESTORE_PREVIEW_STALE` 확인 → BLOCKED; valid preview-state-change race는 실행하지 않음 |
| `R-IDEMP-01` | terminal restore/preview 없음 → BLOCKED |
| `R-ERR` | auth/notfound/malformed/stale-base negative는 PASS, provider/worker/manifest full path는 BLOCKED |
| `R-STATE-TIMELINE` | 승인 terminal 없이 v3 저장 및 audit 미기록 → FAIL |

관측된 공통 경계는 ingest failure event가 worker에서 만들어질 수 있어도 `ai.task.event` consumer의 FK retry/seek 상태와 callback 계약 때문에 Backend operation이 `processing`에 머문다는 점이다. R-STALE-01은 garbage-token taxonomy의 409만 확인했고 valid preview-state-change race는 실행하지 않았다. restore acceptance는 callback/approval-before-write를 수정하고 성공 Wiki fixture를 새로 만든 뒤 preview→execute→replay/idempotency 및 mixed/multi/partial/stale를 재실행해야 한다.

## 15. Provider별 smoke 결과

### OpenAI

`gpt-5-nano` direct structured checks 3개는 PASS다. 그러나 기능 matrix의 23개 이상 pipeline/AI completion 관찰과 42개 logical cost candidate는 HTTP count/usage가 아니다. ingest/lint/Agent callback 또는 public status가 terminal로 수렴하지 않아 OpenAI 전체 functional matrix는 BLOCKED다.

### Gemini

초기 frozen `provider_e2e.py`는 shell `GEMINI_API_KEY` 누락으로 BLOCKED였다. 이후 key를 process memory에만 주입한 correction 1회에서 `ingestion_json`, `agent_router`, `markdown_create` 세 probe 모두 실제 Gemini HTTP 404를 반환했다. public Query도 503으로 매핑됐고 public Agent structured request parsing은 202/JSON 수락까지 PASS지만 isolated AI run failed와 Backend queued mismatch로 전체 FAIL/BLOCKED다. exact frozen model을 silent substitution하지 않았다.

### Anthropic/Claude

초기 direct harness는 key 누락으로 BLOCKED였고, public query/direct query/Agent에서 exact model 404가 관측됐다. 이후 env source 파싱 오류 사전 시도는 provider 미도달로 count하지 않고, key 항목만 process memory에 주입한 correction 1회에서 세 probe 모두 HTTP 404 `not_found_error`를 확인했다. 공개 query는 503, pipeline Agent는 500, public Agent는 queued이며 structured 성공 parsing·usage·retry는 검증하지 못했다.

### Provider smoke 비용 민감도 (API 비용 가정 및 범위)

이 subsection은 §15 provider smoke의 보조 비용 민감도이며 실제 청구 검증이 아니다. provider usage/request ID/price tier가 응답·agent run·Redis snapshot에 없어 invoice가 아닌 sensitivity다. 원장에 사용한 표준 단가는 OpenAI `gpt-5-nano` 입력/출력 `$0.05/$0.40` per 1M token, Gemini `$0.10/$0.40`, Claude Haiku `$0.80/$4.00`, Tavily `$0.008/credit`이다. 5k input+1k output과 20k+4k를 가정하면 각각 OpenAI `$0.00065–$0.00260`, Gemini `$0.00090–$0.00360`, Claude `$0.00800–$0.03200` per billable completion이다.

확인된 OpenAI pipeline/AI completion 23개를 billable completion으로 가정한 likely sensitivity는 **$0.01495–$0.05980**다. 이는 exact HTTP count가 아니며 hidden retry와 Q-07 probe를 포함하지 않는다. 보수적 logical-candidate gross bound는 OpenAI 42개, Gemini provider-reached 5개, Claude provider-reached/queued 7개, Tavily 잠재 3 credits를 1건씩 과대 가정해 **$0.11180–$0.37520**이다. Tavily 1,000 free credits가 남았다는 별도 가정이면 `$0.08780–$0.35120`이지만, advanced search(2 credits), retry, 실제 token이 더 큰 경우 초과할 수 있다. 404가 무료라고 단정하지 않았고, 실제 비용은 provider usage export/invoice로만 확정할 수 있다.

## 16. Markdown 입력·출력·저장본

아래는 artifact에 실제로 보존된 Markdown이다. 보존되지 않은 output은 만들거나 추정하지 않았다.

### M-02 선택 영역

실제 요청의 full `editorSnapshot` (`/tmp/ai-e2e-34068aa/M-02-request.json`, seed가 아님):

```markdown
# GFM Agent Fixture <redacted token-like literal>

Read the [official guide](https://example.com/guide).

| Item | Owner |
| --- | --- |
| API | Mina |

~~~bash
printf '<redacted token-like literal>\n'
~~~

- [x] Existing task
- [ ] Pending task

Footnote reference[^1].

> Keep this blockquote unchanged.

Use `<redacted token-like literal>` inline.

[^1]: Footnote detail for <redacted token-like literal>.
```

`editorSnapshot.target`는 `{ "type": "selection", "startLine": 3, "endLine": 3 }`이다. literal token-like fixture 문자열은 이 보고서에서 `<redacted token-like literal>`로 치환했으므로 해당 값에 대한 byte-level fidelity는 제한되지만, heading/link/table/fence/task list/footnote/blockquote/inline-code 구조와 줄 위치는 보존했다.

AI 반환 fragment (`M-02-final.json`):

```markdown
Read the [official guide](https://example.com/guide).
```

이는 whole-document output이 아닌 selection line 3 replacement proposal이다. `changed=false`, `scope_expanded=false`였다.

Backend 저장본 (`doc_6e43026278c346c1885301f83ba377c6`, version 1):

```markdown
# GFM Agent Fixture <redacted token-like literal>

Read the [official guide](https://example.com/guide).

| Item | Owner |
| --- | --- |
| API | Mina |

~~~bash
printf '<redacted token-like literal>\n'
~~~

- [x] Existing task
- [ ] Pending task

Footnote reference[^1].

> Keep this blockquote unchanged.

Use `<redacted token-like literal>` inline.

[^1]: Footnote detail for <redacted token-like literal>.
```

M-02 저장 diff:

```diff
 Read the [official guide](https://example.com/guide).
```

leading space는 unchanged line을 뜻하며 실제 apply diff는 없다. M-02 response의 `changed=false`와 no-applied-diff가 일치하므로 M-02 overall은 proposal-only BLOCKED다. 따라서 link/table/fence/task list/footnote/blockquote/inline code가 모두 보존된 GFM context는 확인됐지만, GFM overall은 applied diff가 없어 BLOCKED이며 mutation 후 GFM 보존은 미실행이다.

### GFM fixture

입력 (`/tmp/ai-e2e-34068aa/gfm-input.md`)은 위와 동일한 heading/link/table/fenced code/task list/footnote/blockquote/inline code 구조다. 생성 response는 `doc_dd2f1b7231ab4951b637bfaa203445c8`, HTTP 201, version 1을 기록했다. 저장 조회 (`gfm-stored*.json`)는 다음 원문과 version 1을 반복 기록했다.

```markdown
# GFM Agent Fixture <redacted token-like literal>

Read the [official guide](https://example.com/guide).

| Item | Owner |
| --- | --- |
| API | Mina |

~~~bash
printf '<redacted token-like literal>\n'
~~~

- [x] Existing task
- [ ] Pending task

Footnote reference[^1].

> Keep this blockquote unchanged.

Use `<redacted token-like literal>` inline.

[^1]: Footnote detail for <redacted token-like literal>.
```

`gfm-save-response.txt`는 별도로 HTTP 200, `current_version=2`, `changed=true`, `markdown=null`을 반환하지만 subsequent `gfm-stored*.json`는 version 1 원문을 읽었다. 이 저장 version 불일치는 artifact 간 관측 차이로 남기며, 적용 성공으로 해석하지 않는다.

### 기타 Markdown input/output 경계

M-01 input file은 byte 1로 사실상 empty다. M-03/M-04/M-05 입력은 `# Guide`와 `## First/Second`, M-06과 fresh 입력은 `# Notes`/`## Tasks` 구조다. M-01의 generated Markdown은 AI DB에 존재했다는 evidence만 있고 exact body는 보존 artifact에 없어 인용하지 않는다. M-03~M-06의 proposal/result는 internal DB에 completed였으나 public stored document는 unchanged/version 1이어서 저장본/diff를 성공 output으로 쓰지 않는다. MERR input은 prompt injection 문구를 포함했으나 internal reject와 no side effect만 기록한다.

## 17. 실패·불안정·차단 항목

`FLAKY`는 없음. 아래는 `FAIL` 항목에 대한 요구된 분석이다. 최초 시각이 artifact에 없는 경우 명시적으로 “미기록”으로 두었다.

| 그룹 | 상태 | 증상 | 재현 | 최초 시각 | 상관관계 | 기대 | 실제 | 계층 | 직접 원인 | source artifact / line·section reference | 최소 수정·영향·재실행 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| I-DOC-01 / I-CHAT-P-01 / I-RE-01 / I-RE-02 / I-RACE-01 | FAIL | ingest 202/processing 뒤 failed, Wiki 0 또는 최신성 미입증 | 전용 문서/채팅 생성 후 ingest/reingest/race 요청 | 미기록 (matrix 실행 창만 2026-08-11 18:55:07Z–19:07:14Z) | 미관측 (run↔operation↔callback) | pipeline 완료, callback, Wiki/link/embedding/contribution 및 Backend terminal | `500: 'PipelineRunCommand' object has no attribute 'result_callback_url'`, operation processing/failed, graph 0/0 | AI routing/pipeline runner → task event/callback → Backend apply | runner가 command/schema에 없는 `result_callback_url`을 읽음 | `openai-ingest-matrix.md` Scenario matrix/Runtime evidence; `coverage-gap-audit.md:94-105`; report §7 | callback field를 schema→command→runner→event→Backend로 정렬하고 failed terminal apply; 모든 I-*, L-02, R-* 및 Q-01 재실행 |
| I-CHAT-F-01 | FAIL | full export가 same hash로 skipped | partial 후 동일 session/document에 full export | 미기록 | 미관측 | `selection_mode=full` full state/run 및 전체 원문 | new full state/run 없음 | Backend chat state/dedup | partial/full이 동일 dedup 경로를 재사용 | `openai-ingest-matrix.md` I-CHAT-F-01; `coverage-gap-audit.md:97`; report §7 | dedup key와 operation identity에서 `selection_mode` 분리; partial→full→delta 재실행 |
| Q-02 / Q-03 | FAIL | web ON query empty-body 401 | `allow_web_search=true` 동일 내부/외부 질문 | 미기록 | 미관측 (source/event/provider request) | Tavily 호출, web events/source/reference, 답변 | 약 3초 401, source 0, assistant failed | authorization/AI routing/HTTP transport | downstream 401은 확인했으나 provider 단독 원인은 미확정 | `openai-query-matrix.md` Verdict matrix; `coverage-gap-audit.md:112-120`; report §8 | credential/endpoint와 진단 가능한 5xx mapping; Q-01~Q-07 재실행 |
| L-04 malformed dry_run | FAIL | `dry_run:"invalid"` empty 401, `dry_run:1` 202 accepted | lint negative input | 미기록 (lane 2026-08-12 04:05 KST) | 미관측 | boolean 아닌 값 400 validation | coercion/empty auth-like response | Backend validation/HTTP | integer truthy coercion 및 invalid string 401 경로 | `openai-lint-matrix.md` L-04 negative/concurrency; `coverage-gap-audit.md:129`; report §9 | strict boolean schema와 일관된 400; malformed 후 L-01~L-04 재실행 |
| `search_hierarchy`, `get_breadcrumb`, `create_document`, `apply_document_edit` | FAIL | AI allowlist는 PASS지만 valid dispatcher 없음 | frozen 13 static inspection 및 malformed boundary probe | 미기록 | 미관측 | schema/Backend/worker/endpoint 및 normal-invalid-cross-workspace 동작 | 4개 unregistered, normal invocation 불가 | AI routing/Backend gateway | Backend/AI worker dispatcher 등록 누락 | `tool-contract-13.md:35-58`; `coverage-gap-audit.md:135-150`; report §10 | 4개 contract/dispatcher와 revision mapping 정합화; 13개 전부 재실행 |
| S-EDIT | FAIL | OpenAI Markdown output contract failure, public queued | aligned API+worker true, valid document-edit request | 미기록 | 미관측 (internal failed↔public queued) | valid proposal/plan/approval 및 terminal failed/result projection | `MarkdownOutputContractError`, internal failed, Backend queued | LLM provider/structured parsing/callback | provider output heading contract 위반 | `openai-skill-flag-correction.md` 시나리오 결과; report §11 | output contract/prompt와 failed callback 정렬; S-EDIT 및 M-02~M-05 재실행 |
| S-MULTITURN | FAIL | pending proposal publish 400 safety source 오류 | pending proposal 후속 `publish it` | 미기록 | 미관측 (pending↔publish) | follow-up 연결, safety 재검토 후 publish | source safety text validation 중단, Skill 없음 | AI routing/structured parsing/Backend validation | 후속 payload required source text 누락 | `openai-skill-flag-correction.md` 시나리오 결과; report §11/§13 | source reference 보존과 schema validation; S-MULTITURN/S-AUTO/S-EXPLICIT/S-DRAFT 재실행 |
| S-DRAFT | FAIL | draft preview 400 classifier inconsistency | corrected response 400 확인; corrected source-run correlation은 관측하지 못함 | 미기록 | 미관측 (exact corrected run correlation) | 성공 operation만 일반화한 draft | `Skill request could not be classified consistently`, persisted Skill 없음 | provider/structured parsing/AI routing | classifier/verifier contract inconsistency | `openai-s-draft-direct-corrected.txt:1-2`; `s-direct-draft-body.json`; report §11 | exact correlation을 주장하지 않고 classifier/verifier contract 정렬; S-DRAFT 재실행 |
| M-ERR fabricated approval / B | FAIL | fake apply ID로 실제 document revision 생성, AI operation log 404 | `source=agent`와 fabricated ID로 save | 미기록 | 미관측 (approval↔revision↔audit) | 승인 전 4xx, Mongo/version write 없음 | HTTP 200, revision 증가, 본문 변경, audit 없음 | Backend authorization/result apply/AI persistence | Mongo/version write 후 consume 호출 | `openai-markdown-agent-matrix.md` Fake approval side effect; report §12/§16 | approval consume을 write 전에 원자 검증; M-ERR/B와 valid M-02 apply 재실행 |
| R-STATE-TIMELINE | FAIL | 승인 terminal 없이 public save v3와 operation 미기록 | create v1 → save v2 → apply-id save v3 → ingest | 미기록 (lane 2026-08-12 04:40~04:47 KST) | 미관측 (approval↔operation↔restore log) | 승인 operation만 적용, ingest/operation terminal 및 restore log | v3 저장, ingest failed, Wiki 0, operation processing/log 없음 | Backend apply/callback/restore | approval/cross-store write 원자 경계와 callback poison | `log-restore-matrix.md` R-STATE-TIMELINE; report §14 | write 전 approval consume 및 callback consumer 정렬; 전체 R 본체 재실행 |
| Gemini exact model | FAIL | direct 3 probes/public Query 404/503, Agent internal failed/public queued | key correction 후 frozen `gemini-2.5-flash-lite` | 미기록 | 미관측 (provider request↔public status) | provider structured success | 세 probe 404, Query 503, Agent status split | provider/Backend result apply | model availability/catalog 및 8000/8001 endpoint 불일치 추정 | `gemini-live-compat.md` Direct provider correction/Public Query; report §3/§15 | model catalog/endpoint 정합화; direct/public Agent 재실행 |
| Claude exact model | FAIL | direct/public Query/Agent 404/503/500, 일부 public queued | key correction 후 exact `claude-3-5-haiku-20241022` | 미기록 | 미관측 (provider request↔public status) | provider structured success | Anthropic 404 `not_found_error`, query 503, pipeline Agent 500 | provider/structured parsing/callback | model entitlement 및 error/status wiring 불일치 추정 | `claude-live-compat.md` exact smoke/public results; report §3/§15 | model availability/catalog, error mapping, callback 정합화; direct/public 재실행 |

차단 항목 보강: M-02는 `changed=false`인 proposal-only 결과이며 실제 문서 apply 또는 applied diff가 없다. GFM은 full `editorSnapshot`/stored context와 proposal boundary만 확인됐고 applied diff가 없어 overall BLOCKED다. 근거는 `/tmp/ai-e2e-34068aa/openai-markdown-agent-matrix.md` `M-02 exact Markdown evidence` 및 `coverage-gap-audit.md:195-202`이며, 이 report의 §6/§12/§16 판정과 일치한다.

### 계층·직접 원인·추정·경계·최소 수정·영향·정확한 재실행

- **Ingest 묶음:** 계층은 AI routing/pipeline runner, task event/callback, Backend result apply다. 확인된 직접 원인은 runner가 command/schema에 없는 `result_callback_url`을 읽은 것이다. **ROOT-CAUSE INFERENCE:** `log_callback_url`과 `result_callback_url`의 계약 drift 및 consumer retry/poison이 failed event 적용을 막았을 가능성. 경계는 `services/ai/pipeline/run_lab.py:1314`, `PipelineRunCommand`/HTTP schema, `ingest_worker.py`, `AiTaskResultApplier`다. 최소 수정은 callback field를 schema→command→runner→event→Backend 한 계약으로 정렬하고 failed도 terminal apply하는 것. 영향은 모든 ingest/chat/reingest와 L-02/R-* fixture다. 재실행은 I-DOC-01, I-CHAT-P-01, I-CHAT-F-01/02, I-RE-01/02, I-RACE-01, worker/provider/callback I-ERR, L-01~L-04, R-INGEST-01/02/R-MIXED/R-MULTI/R-PARTIAL/R-IDEMP 및 Q-01 marker다.
- **I-CHAT-F-01:** 계층은 Backend state/dedup, 직접 원인은 partial/full이 동일 content hash/dedup 경로를 재사용한 것. **ROOT-CAUSE INFERENCE:** dedup key가 `selection_mode`를 포함하지 않는 것으로 추정. 경계는 chat export request→dedup/state. 최소 수정은 partial/full mode를 dedup key와 operation identity에서 분리하는 것. 영향은 chat export 중복/누락. 재실행은 I-CHAT-P-01→I-CHAT-F-01→I-CHAT-F-02다.
- **Q-02/Q-03:** 계층은 authorization/AI routing/HTTP transport, 확인된 직접 원인은 web-enabled request가 downstream에서 401로 종료된 사실이다. **ROOT-CAUSE INFERENCE:** Tavily/OpenAI credential 또는 내부 auth boundary mismatch 가능성이나 provider 단독 원인은 미확정. 경계는 Backend Query→pipeline/web adapter→provider/Tavily. 최소 수정은 credential/endpoint 확인과 진단 가능한 5xx mapping. 영향은 Q-02/Q-03/Q-06 true. 재실행은 Q-01~Q-07, 특히 source/event/stop reason 포함 Q-02/Q-03/Q-06이다.
- **L-04:** 계층은 Backend validation/HTTP, 직접 원인은 integer truthy coercion과 invalid string의 401 경로. **ROOT-CAUSE INFERENCE:** request binder/validation과 auth/error mapping 불일치 추정. 경계는 lint controller request→boolean binding. 최소 수정은 strict boolean schema와 일관된 400. 영향은 malformed lint 계약. 재실행은 malformed cases 후 L-01~L-04다.
- **4개 Tool:** 계층은 AI routing/Backend gateway, 직접 원인은 Backend/AI worker dispatcher 등록 누락. **ROOT-CAUSE INFERENCE:** frozen allowlist와 Backend contract가 다른 진화 경로를 탔다고 추정. 경계는 `AgentToolService`/worker dispatcher/Tool gateway. 최소 수정은 4개 contract/dispatcher 추가와 canonical revision 정렬. 영향은 Skill/Agent read/mutation. 재실행은 13개 전부와 A-1/A-2/D-3, S-CREATE/S-EDIT/S-FOLDER, M-07이다.
- **S-EDIT:** 계층은 LLM Provider/structured parsing 및 callback, 직접 원인은 provider output의 Markdown heading contract 위반. **ROOT-CAUSE INFERENCE:** prompt/output validator/retry 형식 불일치 추정. 경계는 OpenAI response→Markdown parser→public projection. 최소 수정은 output contract/prompt와 failed callback을 분리 정렬. 영향은 document-edit/Skill selection. 재실행은 S-EDIT, M-02~M-05, valid approval apply다.
- **S-MULTITURN:** 계층은 AI routing/structured parsing/Backend API validation, 직접 원인은 후속 publish payload의 required source text 누락. **ROOT-CAUSE INFERENCE:** pending state가 safety source를 다음 turn에 전달하지 않는 것으로 추정. 경계는 pending `/agent/turn`→Skill author/publish. 최소 수정은 source reference 보존과 명시 schema validation. 영향은 Skill publish/approval. 재실행은 S-MULTITURN, S-AUTO/S-EXPLICIT, S-DRAFT, S-ERR다.
- **S-DRAFT:** 계층은 provider/structured parsing/AI routing, 직접 원인은 classifier inconsistency. **ROOT-CAUSE INFERENCE:** intent classifier/verifier category/decision contract drift 추정. 경계는 completed AgentRun→draft classifier/verifier. 최소 수정은 classifier/verifier contract 및 successful operation filtering 정렬. 영향은 S-DRAFT/publish. 재실행은 S-DRAFT와 S-CREATE/S-EDIT/S-FOLDER/S-TEMPLATE/S-ERR다.
- **M-ERR/B 및 R-STATE:** 계층은 Backend authorization/result apply/AI persistence/callback, 직접 원인은 `DocumentService.saveContent`가 Mongo/version write 후 `AgentApplyOperationStore.consume`를 호출하는 순서다. **ROOT-CAUSE INFERENCE:** 승인 검증과 cross-store write가 원자 경계 없이 구현됐다는 판단은 `DocumentService.java:1145-1189` source inspection으로 지지된다. 경계는 public content save→Mongo/version→consume/operation log와 `AiTaskResultApplier`. 최소 수정은 write 전에 ready approval을 원자 consume하고 false는 4xx, callback consumer poison/FK 처리를 정렬하는 것. 영향은 승인 우회, 감사 누락, restore/log 신뢰성. 재실행은 M-ERR fabricated, B, valid M-02, C-1, D-1/D-2/D-3, R-STATE 및 전체 R 본체다.
- **Gemini:** 계층은 provider와 Backend result apply, 직접 원인은 Gemini가 frozen model을 unavailable/new users 404로 거부한 것과 8000/8001 status split. **ROOT-CAUSE INFERENCE:** model catalog/key 권한 및 environment endpoint 불일치. 경계는 provider client→pipeline 8001→Backend status 8000. 최소 수정은 사용 가능 model/catalog 정합화와 동일 runtime status 연결. 재실행은 direct 3 checks, public Query, Agent/status다.
- **Claude:** 계층은 provider/structured parsing/callback, 직접 원인은 exact model unavailable/not found. **ROOT-CAUSE INFERENCE:** provider entitlement와 Backend error mapping/status wiring 불일치. 경계는 Anthropic client→pipeline→document query/Agent. 최소 수정은 model availability/catalog, 오류 mapping, terminal callback 정합화. 재실행은 direct 3 checks, public/direct query/Agent/status다.

## 18. 원인과 수정 제안

1. **P0 callback 계약:** `result_callback_url` 대 `log_callback_url`를 하나의 schema/command/runner/event 계약으로 결정하고 failed event도 Backend operation을 terminal로 전이시킨다. `ai.task.event` consumer의 FK retry/poison 처리와 callback correlation을 함께 관측한다.
2. **P0 approval-before-write:** `DocumentService.saveContent`에서 agent source/apply ID를 Mongo/version write 전에 ready approval과 원자적으로 검증·consume한다. false consume이면 4xx이며 본문/version/audit side effect가 없어야 한다.
3. **P0 Tool contract:** 4개 dispatcher/endpoint/response를 추가하고 `list_agent_run_artifacts` extra dependency를 제거하거나 frozen contract에 명시한다. `edit_revision`과 `current_version`을 혼용하지 않는다.
4. **P0 Markdown/GFM acceptance:** M-02 proposal-only `changed=false`와 GFM no-applied-diff 상태를 유지하고, approval projection이 ready가 된 뒤에만 valid apply 및 applied diff/GFM 보존을 재검증한다.
5. **P0 Agent status:** Pipeline 8001과 Backend public status endpoint를 동일 runtime/callback으로 정렬해 internal completed/failed를 queued로 숨기지 않는다.
6. **P0 chat dedup:** partial/full `selection_mode`를 dedup key와 operation identity에서 분리한다.
7. **P1 Skill:** API/worker flag를 함께 고정하고 `/agent/runs/*`, draft/approval proxy 및 nested dispatcher를 제공한다. S-EDIT output contract, S-MULTITURN safety source, S-DRAFT classifier를 각각 별도 수정한다. S-DRAFT는 corrected response 400은 확인하되 corrected source-run correlation을 verified로 기록하지 않는다.
8. **P1 provider:** Gemini/Claude account availability 확인 후 catalog와 deployment를 함께 갱신한다. 모델을 조용히 바꾸지 않는다. web ON auth/Tavily 오류는 source/event/stop reason을 보존하는 진단 가능한 오류로 매핑한다.
9. **P1 lint/restore:** strict boolean validation, operation callback terminal, 성공 Wiki fixture를 확보한 뒤 restore preview/execute/replay를 재실행한다. R-STALE-01은 garbage-token 409 taxonomy와 별도로 valid preview-state-change race를 실행한다.
10. **보안:** transient worker transcript의 credential metadata/session-cookie material 노출을 incident로 취급해 관련 credential revocation/rotation, transcript 접근 제한·보존 정책, secret redaction 검증을 시행한다. 이 보고서에는 값이나 재현 문자열을 포함하지 않는다.

## 19. 재검증 목록

수정 후 정확히 다음 순서로 fresh fixture/run ID를 사용한다.

1. **Runtime/preflight:** branch/SHA, env key presence만, 8080/8081/8001 health, 6 topic, worker group/member/lag, callback token/endpoint와 status port를 기록한다.
2. **Automated raw evidence:** Backend selector command/output, AI pytest command/output, failure list를 별도 보존한다.
3. **Ingest:** I-DOC-01/02, I-CHAT-P-01→I-CHAT-F-01→I-CHAT-F-02, I-RE-01→I-RE-02, I-RACE-01, I-ERR worker/provider/callback/concurrent.
4. **Query:** marker ingest 완료 후 Q-01~Q-08; Q-02/Q-03/Q-05는 Tavily call/event/source/stop reason, Q-06은 true terminal, Q-07은 recent-limit 계약을 확인한다.
5. **Lint:** L-01~L-04, strict boolean, apply diff, operation terminal, concurrent/partial callback.
6. **Tool/approval:** frozen 13 정상·invalid·cross-workspace, A-1/A-2/B/C-1/D-1/D-2/D-3/X-1/X-2/X-3. valid approval은 한 번만 consume, fake/replay/stale는 no write.
7. **Skill:** aligned runtime에서 S-CREATE/S-EDIT/S-FOLDER/S-TEMPLATE/S-MULTITURN/S-DRAFT/S-AUTO/S-EXPLICIT/S-ERR. published Skill fixture를 만들고 selection→plan→approval→tool→result를 독립 확인한다.
8. **Markdown:** M-01~M-07/M-ERR, GFM input/output/stored/version/diff를 applied case에서도 보존한다. M-02 valid apply와 M-ERR fabricated approval을 포함한다.
9. **Log/Restore:** R-DOC-01, R-INGEST-01/02, R-LINT-01, R-MIXED-01, R-MULTI-01, R-PARTIAL-01, R-STALE-01(garbage-token 409 taxonomy와 valid preview-state-change race를 별도 확인), R-IDEMP-01, R-ERR, R-STATE-TIMELINE.
10. **Providers/cost:** OpenAI direct+functional, Gemini/Claude exact smoke, provider request ID/usage/retry/price tier를 secret 없이 보존한다.
11. **Cleanup:** owner credential로 `/me`와 workspace/navigation/detail/version을 DELETE 직전 fresh read하고 documents→empty folders→sessions→workspace 순서로 exact fixture만 정리한다. public delete가 없는 user/run/operation/skill/provider/Kafka/evidence는 보존한다.

## 20. 남은 fixture 및 정리 상태

### Cleanup audit

- cleanup artifact 기준 시각: `2026-08-12T05:30:48+09:00` (Asia/Seoul); 이후 관측은 이 campaign evidence에 포함하지 않는다.
- **DELETE 요청: 0건.** 문서/폴더/session/workspace 모두 attempted 0, HTTP status N/A.
- 원인: 보존된 owner credential bundle의 fresh `/api/auth/me`와 `/api/workspaces`가 모두 401이었고, Tool/Approval/Skill owner는 matching credential file 자체가 없었다. 보존 JWT는 expired였으며 재로그인·추측·로그 scraping·credential recovery는 하지 않았다.
- **이미 부재한 문서 1건:** `doc_c453241099774ea984eaedf50ebe0dda`는 이전 public DELETE와 404가 manifest에 기록되어 있어 재삭제하지 않았다.
- **정확히 확인됐지만 retained인 fixture 요약:** Tool user/workspace와 2 folders 및 `doc_c32f4951ab8d4220b1946b763d27495b`; Ingest 2 workspaces·4 documents/chatdoc; Query 2 workspaces·marker document·3 sessions; Lint 2 workspaces·2 target documents; Approval 1 workspace·4 documents; Restore 2 workspaces·3 documents; Skill 1 workspace·reference folder/document; Markdown/GFM 2 workspaces·M-01~M-ERR/GFM documents. exact IDs는 `fixture-cleanup-result.md`의 retained table과 각 matrix를 따른다. Gemini/Claude exact IDs는 artifact에 없어 추정하지 않았다.
- **정확한 retained workspace:** `ws_4bde5230dc104809969d99c6ae6ac086`, `ws_0ce4e55c0f0a4016af8882e0174180d`, `ws_cc3db93466054236bb2370e889a32e7e`, `ws_fa3089afb61a4301bfcf3f4f7fb41f80`, `ws_111b0fe4a75e4c7ca3bcdf5c09a86e22`, `ws_195743e0b0b846a1a80e51ba33c79f15`, `ws_cd23583e53874f04aeb26cfacdbdd989`, `ws_3bb1edf0483d4435b57c9bbd58eceb04`, `ws_3945f699f0ca4e3b8832933ad16ec669`, `ws_b8c8c7297be640c7b5db6a423fdb3058`, `ws_147ff1ab23c142eca3fff47573760c6f`, `ws_fdb972609d474ebb862fc103dc4067c6`, `ws_bff28d86c1d544e299139cc2f27c36a1`, `ws_844dbcedf1be45c8b48c0d6853c65d6d`.
- **정확한 retained document:** `doc_c32f4951ab8d4220b1946b763d27495b`; `doc_3ac4114c90bb4b13891debca0d46dbb0`, `doc_aca6987e05254d82bee5135bab360b1b`, `doc_b163508c25624982b5751bc3dd6f0c04`, `chatdoc_8a5ece94f0c4453a9a2a92235939b058`; `doc_d34d77cac5364c39a29578e5da8f0818`; `doc_33801441730a4bf1b7a5dde93cf476c4`, `doc_6c9f4db141094372a83de93751efe4d3`; `doc_30d686a72e954b5c9d88484485aac4af`, `doc_05f4ed9d7c404833be5825e984657fe3`, `doc_877721b0aea741dda74d55c58078aaf0`, `doc_13e974cbd091417fb0bcd4c17a9a1922`; `doc_2654efd5050e4159a464111fcbdf1df0`, `doc_ed5cdf47db3142789151933147c3b91c`, `doc_abbb0653e4e44799ae22fc49ee027e61`; `doc_6f3e77a2ddff4e1b814bc5ac1226266c`, `doc_fb69836e53dc42608e4b1c0b162df909`; `doc_6023d322416a479ebeffcd7db2d60611`, `doc_6e43026278c346c1885301f83ba377c6`, `doc_aa00c4d272f44fa2a593c6814846359b`, `doc_bfd52a010d9d4ba793eacb0908bdb962`, `doc_860cfd1fbd54409e88d2c983fffebb7e`, `doc_b857233b552842b38ec1a66665dffc29`, `doc_dde83294f0554ad98bcc3756a07f28ec`, `doc_cbf6f8fdb1e044d18be8d5fdbe04f081`, `doc_dd2f1b7231ab4951b637bfaa203445c8`, `doc_a3d4349fff014664bf7644e5f31a9a33`.
- **폴더:** Tool `20a86ece-2229-4f3e-8a1c-9f96baef59ef`, `796e73df-b847-44ad-9683-bf63e3b4feda`; Skill `da9c41a1-af40-4c17-abcc-2cf261545041` retained.
- **세션:** Ingest `session_de2f02313593421ea061905dda9f743b`, `session_195239bacf0748c6806bc1d6af242f6b`; Query `session_5085081f6a7d47d5bec705e0702d19f9`, `session_52a275ef3e3b4615a4c3b24e1eaebea2`, `session_f811d12194a54e92a5cc5088c16c3274` retained. Gemini/Claude session ID는 미보존이라 추정하지 않았다.
- users, agent/pipeline/query runs, operations, approvals, skills/proposals/versions, provider/Kafka records, evidence files, volumes, source/config/runtime processes는 삭제하지 않았다. Wiki graph는 relevant ingest/lint에서 empty였으므로 page ID를 발명하지 않았다.

### 보안 transcript audit

reviewed Markdown artifact에는 token/secret assignment 값이 없다는 문구가 있었지만, 별도의 transient worker transcript에서 credential metadata와 session-cookie material이 노출된 사실을 확인했다. 값·cookie·header·token은 이 보고서에 재현하지 않는다. 즉시 관련 credential revocation/rotation, transcript 접근 권한 축소 및 보존/삭제 정책 검토, 향후 worker transcript secret redaction 검사를 권고한다.

### 변경 범위 및 최종 상태

이 최종 repository report 파일 `docs/backlog/evaluation/backend-ai-integration-test-report.md`을 작성했다. repository/runtime/API/data와 product/test code는 수정하지 않았고, pre-existing repository changes를 보존했으며 commit/merge/PR/push는 하지 않았다. E2E acceptance는 미달이므로 수정 및 재검증 전 release/sign-off로 사용하지 않는다.

PUBLISHABLE_WITH_LIMITATIONS
