# API 요약

`docs/backlog/spec/api/` 7개 스펙 문서의 압축본. 상세 계약(요청/응답 JSON, 흐름, 정합성 규칙)은 각 원문 참조.

## 공통

- 서비스 라우팅: `/api/auth/*`·`/api/workspaces` → access-svc(:8081), 그 외 → document-svc(:8080).
- 인증: `Authorization: Bearer <access JWT(HS256, 기본 900s)>`. refresh는 opaque 토큰(DB에 sha256 해시만 저장, rotation).
- 사용자 API는 authenticated다. health·OpenAPI만 permitAll이다. `/internal/**`는 원칙적으로 `X-Internal-Token`을 검증하고, Agent worker가 document-svc의 Tool을 호출하는 `/internal/agent/tools/**`와 Skill 참조 read는 `X-Agent-Service-Token`을 검증한다.
- 에러 envelope: `{ "error": { "code", "message", "details" } }`. 검증 실패는 400 `INVALID_REQUEST` + field details. 예외→코드 전체 매핑은 원문 참조.
- `Idempotency-Key`가 적용된 API는 1~255자 키를 사용한다. 실행 선점 lease는 15분이고, 완료 응답은 완료 시점부터 24시간 유지한다. 같은 사용자·endpoint·키의 같은 요청이 완료되면 저장된 응답을 재생하고, 다른 payload는 409 `IDEMPOTENCY_CONFLICT`, lease 내 처리 중인 동시 요청은 409 `IDEMPOTENCY_IN_PROGRESS`로 거절한다. 실행이 실패하거나 lease가 만료되면 같은 키로 재시도할 수 있다.
- ID 형식: `user_`/`doc_`/`session_`/`query_`/`agent_`/`op_` + UUID/난수.
- Query·ingest·lint Kafka command는 backend DB에서 실행 시 선택한 `model`을 전달하며 ai-svc는 이를 해당 실행에만 적용한다. Provider·API key·base URL은 ai-svc env 설정을 사용한다. Query command의 필수 boolean `allow_web_search`가 `true`일 때만 Tavily adapter를 구성하지만, 실제 검색 여부는 기존 evaluator·내부 관련도 fallback 정책이 결정한다. `LLM_API_KEY`·`LLM_BASE_URL`·`TAVILY_API_KEY`는 command에 넣지 않고 ai-svc secret env에서 읽는다.
- 원문: docs/backlog/spec/api/00-common.md

## 인증

베이스 `/api/auth`. 아래 중 `/me`만 인증 필요.

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/auth/email-verifications` | 인증번호 발급(202). 응답 `verification_id`, cooldown·일일 상한 초과 시 429 |
| POST | `/api/auth/email-verifications/{id}/confirm` | 코드 검증 → 1회용 `verification_token` 발급 |
| POST | `/api/auth/signup` | 회원가입(201). `verification_token` 필수, 기본 워크스페이스 자동 생성 |
| POST | `/api/auth/password-reset` | 비밀번호 재설정(204). 성공 시 해당 사용자 refresh 전체 폐기 |
| POST | `/api/auth/login` | 로그인 → `access_token`, `refresh_token`. 실패는 사유 무관 401 |
| POST | `/api/auth/refresh` | 토큰 재발급(rotation, 기존 refresh revoke) |
| POST | `/api/auth/logout` | refresh revoke(204) |
| POST | `/api/auth/oauth/exchange` | OAuth 성공 후 1회용 code(TTL 60s) → 토큰 쌍 |
| GET | `/api/auth/me` | 내 정보 조회(인증 필요) |

원문: docs/backlog/spec/api/auth.md

## 워크스페이스

- 워크스페이스 CRUD 자체는 별도 스펙 문서 없음. 회원가입/OAuth 최초 가입 시 `WorkspaceService.createDefault`로 기본 워크스페이스 생성.
- `/api/workspaces/{workspace_id}/**` 하위 모든 도메인 API는 인증 + 활성 멤버십(`workspace_member`) 검증. 미소유는 존재를 감추고 404 `WORKSPACE_NOT_FOUND`.
- `GET /api/workspaces/{workspace_id}/ai-model-settings`: OWNER·MEMBER가 workspace AI 모델 설정을 조회.
- `PUT /api/workspaces/{workspace_id}/ai-model-settings`: OWNER만 활성 model catalog 내 `provider`+`model` 조합으로 변경. 기본값은 `openai`+`gpt-4.1-mini`.

## AI 모델

- `GET /api/ai-models`: `AI_ENABLED_PROVIDERS`에 포함된 provider의 선택 가능 model catalog를 반환한다. API key는 노출하지 않는다.

## Query

- 동기 `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query`와 비동기 `POST .../query/runs` 요청은 `question`, 선택적 `provider`·`model`, 필수 boolean `allow_web_search`를 받는다.
- `allow_web_search`는 해당 질의에만 적용되며 실행 당시 값은 채팅 메시지와 Query run에 snapshot으로 저장된다.

## 문서

베이스 `/api/workspaces/{workspace_id}/documents`. 생성·업로드·복제·삭제·복구는 `Idempotency-Key` 헤더 필수, 저장·이름변경·삭제·복구는 `base_version` 낙관적 잠금(불일치 409 `DOCUMENT_VERSION_CONFLICT`). 변경 계열은 문서 소유자 전용(403 `DOCUMENT_WRITE_FORBIDDEN`).

| Method | Path | 설명 |
|---|---|---|
| POST | `/documents` | 파일 업로드(multipart, PDF·Markdown만, 415). MD는 EDITABLE, PDF는 ORIGINAL |
| POST | `/documents/markdown` | Markdown 직접 생성. `display_name`+`markdown`(빈 문자열 허용, null 불가) |
| GET | `/documents?query=` | 활성 문서 평면 목록. filename/display_name 부분 검색(본문 미검색). 항목별 `needs_reingest` — 마지막 ingest 스냅샷(content_hash)과 현재 편집본(current_content_hash)이 다르면 true |
| GET | `/documents/{id}` | 상세 + 최신 `markdown` + `wiki_pages`. `current_version`이 이후 `base_version` |
| GET | `/documents/{id}/original` | 원본 스트리밍(MinIO). 직접 생성·복제·변환 문서도 생성 시점에 원본을 저장하므로 조회된다 |
| GET | `/documents/{id}/blocks` | 원본 block 목록(`block_id`, `text`) |
| PUT | `/documents/{id}/content` | 본문 수동 저장(multipart: `markdown`, `base_revision`, `revision_write_id`). AI 편집 저장은 `source=agent`와 `apply_operation_id`를 함께 전달한다. 동일 본문 `changed=false`, 5MB 초과 413. 이미지 포함 저장은 `metadata`(JSON: `markdown`+`base_version`) + `attachment_*` file part — 본문 placeholder `attachment://{uuid}`가 asset content 경로로 치환되고 응답 `attachments`에 매핑 반환. 이미지 개당 50MB·합계 100MB 초과 413, 미지원 형식 415 |
| POST | `/documents/{id}/ingest` | 최신 Markdown 재분석 요청(202). 응답 `id`, Spring이 생성한 `run_id`, `status=processing`. 문서 상태·operation·command outbox를 한 트랜잭션에 저장 |
| PATCH | `/documents/{id}/rename` | `display_name` 변경(확장자 보존) |
| POST | `/documents/{id}/duplicate` | EDITABLE 복제(201). 이름 `복사본 (N)` 서버 결정 |
| DELETE | `/documents/{id}` | 소프트 삭제 |
| GET | `/documents/trash` | 휴지통(멤버 전체 조회 가능, `deleted_at` 내림차순) |
| POST | `/documents/{id}/restore` | 복구(역할별 최상위 마지막 위치) |
| GET | `/documents/{id}/export` | 최신 편집 Markdown 다운로드(text/markdown attachment). 관리 이미지를 참조하는 문서는 `.md`+`assets/`를 담은 ZIP(application/zip)으로 반환(이미지 100개·합계 100MB 초과 시 오류) |

문서 폴더·탐색 API:

| Method | Path | 설명 |
|---|---|---|
| PATCH | `/{id}/position` | 문서를 대상 폴더와 정렬 위치로 이동(`folder_id`, `position`, `base_version`). `Idempotency-Key`로 멱등 처리 |

베이스 `/api/workspaces/{workspace_id}/folders`:

| Method | Path | 설명 |
|---|---|---|
| POST | `/` | 최상위 또는 지정한 상위 폴더 아래에 폴더 생성(`name`, 선택적 `parent_folder_id`) |
| PATCH | `/{folder_id}` | 폴더 이름 변경(`name`, `base_version`) |
| PATCH | `/{folder_id}/position` | 폴더를 대상 상위 폴더와 정렬 위치로 이동. 자기 자신·하위 폴더로는 이동 불가 |
| GET | `/{folder_id}/children` | 폴더 바로 아래 하위 폴더·문서를 정렬 순서로 조회 |
| DELETE | `/{folder_id}` | 폴더와 하위 항목을 휴지통 상태로 전환(`base_version`) |
| POST | `/{folder_id}/restore` | 삭제된 폴더와 하위 항목을 복구해 유효한 탐색 위치에 배치(`base_version`) |

베이스 `/api/workspaces/{workspace_id}/navigation`:

| Method | Path | 설명 |
|---|---|---|
| GET | `/` | 워크스페이스 최상위 폴더·문서를 정렬 순서로 조회 |
| GET | `/breadcrumb?folder_id=...` 또는 `?document_id=...` | 폴더 또는 문서까지의 상위 폴더 경로 조회. 두 파라미터는 상호 배타적 |
| GET | `/search?query=` | 폴더 이름·문서 파일명을 검색하고 계층 경로를 반환 |

문서 이미지 asset(베이스 `/api/workspaces/{workspace_id}/assets`):

| Method | Path | 설명 |
|---|---|---|
| GET | `/assets/{asset_id}/content` | 이미지 바이너리 스트리밍. ETag(content hash) 조건부 요청 304 지원. 멤버 전용, 타 워크스페이스 asset은 404 |

최종 상태는 `ai.task.event`를 우선 반영하고, ingest event 유실 시 document-svc가 AI의 `GET /pipeline/runs/{run_id}`를 폴링해 복구한다. AI는 `GET /internal/documents/{id}/pipeline-source`로 원본 위치와 source revision/hash를 조회하며 `documents.status`를 직접 쓰지 않는다.

원문: docs/backlog/spec/api/document.md

## Agent

베이스 `/api/workspaces/{workspace_id}/agent`. document-svc가 Markdown·편집 lock·base version을 검증하고 core 적용 예약 projection·outbox를 원자 저장한다. AI worker가 공급된 `run_id`로 ai_db의 `agent_runs`·결정적 Markdown job을 멱등 생성한다.

| Method | Path | 설명 |
|---|---|---|
| POST | `/agent/turn` | Markdown Agent turn 등록(202). 응답 `requestId`, `apply_operation_id`, `status=queued` |
| GET | `/agent/turn/{run_id}` | 조회 직전에 현재 workspace 멤버십을 확인한 뒤 scope가 포함된 AI 내부 상태 API를 호출해 `queued`/`executing`/`completed`/`failed` 반환. AI run 생성 전만 core queued projection을 반환한다. 비멤버·unknown run은 404, 잘못된 run ID 형식은 400 |

Agent Tool P0 내부 계약:

| Method | Path | 설명 |
|---|---|---|
| POST | `/internal/agent/tools/read/{tool_name}` | ai-svc worker가 `X-Agent-Service-Token`으로 document-svc의 `list_root_items`, `list_folder_children`, `get_document_metadata`, `get_document_content`를 호출. document-svc가 workspace 멤버십·문서 scope와 MongoDB canonical 본문을 확인 |
| POST | `/internal/agent/tools/execute/{tool_name}` | `create_folder`, `rename_folder`, `move_folder`, `move_document`, `rename_document`만 허용. ai_db의 승인된 현재 operation·인자와 정확히 일치해야 document-svc가 멱등 실행 |
| POST | `/internal/agent/runs/tool-authorizations/read` | document-svc가 `X-Internal-Token`으로 ai-svc에 run/workspace/user scope 조회 인가 요청 |
| POST | `/internal/agent/runs/tool-authorizations/execute` | document-svc가 ai-svc에 plan version·operation hash·tool·선행 operation 결과를 포함한 승인 인자 일치 인가 요청 |

Kafka command에는 `run_id`, workspace/user/document, `base_version`, `apply_operation_id`, instruction/editor snapshot을 포함한다. 동일 `run_id` 재전달은 전체 envelope hash가 같을 때만 기존 결과를 재사용한다. 생성된 편집안은 문서를 바꾸지 않으며, 성공 result event가 projection을 ready로 만든 뒤 사용자가 저장할 때 `apply_operation_id`를 operation/version audit와 같은 core 트랜잭션에서 한 번만 소비한다. 기존 `/skills/*`·`/agent/runs/*`의 `X-Agent-Service-Token` 계약과 Spring용 `/internal/agent/runs/**`의 `X-Internal-Token` 계약을 유지한다.

## Skill

베이스 `/api/workspaces/{workspace_id}/skills`. document-svc는 JWT principal과 path workspace를 신뢰 경계로 삼아 멤버십을 fail-closed 확인한 뒤, `X-Agent-Service-Token`으로 보호된 ai-svc Skill API에 두 scope 값만 추가해 중계한다. Skill·version 저장은 ai_db 소유이며 document-svc는 Skill 엔티티를 저장하지 않는다.

| Method | Path | 설명 |
|---|---|---|
| POST | `/skills/author` | 자연어와 선택적 참조 문서 ID로 미저장 Skill 제안 생성 |
| POST | `/skills/author/publish` | 검토한 Skill Markdown을 재검증하고 게시 |
| GET | `/skills` | 개인·현재 Workspace 팀 Skill 목록 |
| GET | `/skills/{skill_id}` | 접근 가능한 Skill 상세 |
| PATCH | `/skills/{skill_id}` | 사용자 Markdown을 재검증해 새 게시 version으로 갱신 |
| POST | `/skills/{skill_id}/enable` | Skill 자동 라우팅 활성화 |
| POST | `/skills/{skill_id}/disable` | Skill 자동 라우팅 비활성화 |

참조 문서는 ai-svc가 `POST /internal/agent/skill-authoring/references/read`를 호출해 scope와 role을 확인한다. document-svc는 service token, workspace 멤버십과 활성 문서를 검증하고, EDITABLE은 workspace가 일치하는 MongoDB canonical Markdown을 반환한다. ORIGINAL은 role만 반환하며 ai-svc가 소유한 ai_db `source_blocks`를 `block_id` 순으로 조립한다.

ai-svc의 Skill 관리·작성 API는 `SKILL_API_ENABLED`(기본 `true`), `/skills/draft-from-runs/preview`와 `/agent/runs/*`는 `AGENT_SKILLS_ENABLED`(기본 `false`)로 독립 제어한다. EDITABLE 참조 Markdown은 문서당 30,000자까지 허용하며 초과 시 413 `REFERENCE_DOCUMENT_TOO_LARGE`를 반환한다. ai-svc가 거부한 일반 Skill 4xx는 상태를 유지하고 `{ "error": { "code": "SKILL_REQUEST_REJECTED", "message": "..." } }` envelope로 정규화한다.

## 채팅

베이스 `/api/workspaces/{workspace_id}/chat/sessions`. 세션은 멤버당 최대 10개. 메시지 생성은 쿼리 도메인이 담당(아래 절).

| Method | Path | 설명 |
|---|---|---|
| POST | `/chat/sessions` | 세션 생성(201). 초과 시 409 `CHAT_SESSION_LIMIT_EXCEEDED` |
| GET | `/chat/sessions` | 본인 세션 목록(최근 메시지 순) |
| DELETE | `/chat/sessions/{id}` | 세션 삭제(204). 메시지·참조는 FK CASCADE |
| GET | `/chat/sessions/{id}/messages` | 메시지 기록. references·related_pages·wiki 편입 정보 포함 |
| POST | `/chat/sessions/{id}/wiki` | wiki export(202). `selection_mode`=`full`\|`partial`(+`pair_ids`), 응답 `status`=`processing`\|`skipped`(content_hash 중복). 완료는 3초 주기 폴링 reconciler가 반영 |
| POST | `/chat/sessions/{id}/wiki/preview` | 직렬화·마스킹된 Markdown 미리보기(text/plain, 저장 없음) |

원문: docs/backlog/spec/api/chat.md

## 쿼리

질의 생성과 run 조회·SSE는 인증 후 세션/workspace 소유권을 확인한다. run과 SSE replay buffer는 Redis에 저장하며 종료 후 TTL은 10분이다. 비동기 API는 pending chat pair와 command outbox를 원자 저장하고 Kafka worker 결과를 받아 assistant·참조·관련 페이지를 반영한다.

| Method | Path | 설명 |
|---|---|---|
| POST | `.../chat/sessions/{id}/query` | 동기 질의(200). 요청 `question`, 선택 `provider`+`model`(함께 생략 시 `openai`+`gpt-4.1-mini`). 응답: user/assistant 메시지, `related_pages`, `evidence_snippets`, `graph_context`, `traversal_paths`. 파이프라인 오류 502/503 |
| POST | `.../chat/sessions/{id}/query/runs` | 비동기 질의 시작(202). 동일한 모델 선택 규칙 적용. 응답 `request_id`, `status=pending` |
| GET | `/api/query/runs/{requestId}/events` | **SSE** 완료 구독. 이벤트 `query.completed`/`query.failed`, buffer 200건 재생 |
| GET | `/api/query/runs/{requestId}` | run 상태 **폴링**(`pending`/`running`/`completed`/`failed`, `provider`, `model`, 완료 시 `result`) |

원문: docs/backlog/spec/api/query.md

## 위키

베이스 `/api/workspaces/{workspace_id}/wiki`. `wiki_pages`와 현재 본문은 ai-svc의 ai_db 소유이고 Backend는 workspace 범위를 포함한 내부 API로 조회한다. `wiki_page_versions`는 diff·복구 이력용으로 core_db에 유지한다.

| Method | Path | 설명 |
|---|---|---|
| GET | `/wiki/graph` | 지식 그래프. `nodes`(source 노드는 `source_document` 포함) + `edges`. 양 끝점이 워크스페이스 내인 링크만 |
| GET | `/wiki/pages/{id}` | 페이지 상세. AI 현재 `markdown`, `source_documents`, `related_pages`(아웃링크) |
| GET | `/wiki/pages/{id}/diff?from=&to=` | 두 revision 간 diff(요청 시 계산). 과대 시 422 `MARKDOWN_DIFF_TOO_LARGE`. 삭제된 페이지도 조회 가능 |
| PATCH | `/wiki/pages/{id}/rename` | 제목 변경. `update_slug=true`면 slug 재생성(충돌 409 `WIKI_PAGE_SLUG_CONFLICT`) |

원문: docs/backlog/spec/api/wiki.md

## Wiki Schema

베이스 `/api/workspaces/{workspace_id}/wiki-schema`. Wiki 생성 규칙을 저장 전 미리보고 초안으로 저장하며, 활성화 요청과 활성 Schema 조회를 제공한다.

| Method | Path | 설명 |
|---|---|---|
| POST | `/preview` | Schema 규칙을 저장하지 않고 적용해 예상 Wiki 구조를 조회(`rawMarkdown`) |
| POST | `/drafts` | Wiki 생성 규칙 초안 저장(`rawMarkdown`, 선택적 `name`) |
| POST | `/{schema_id}/activate` | 선택한 Schema ID의 활성화를 요청 |
| GET | `/active` | 활성 Wiki Schema 조회(없으면 `null`을 포함한 200 응답) |

## AI 작업 로그

베이스 `/api/workspaces/{workspace_id}/ai-operation-logs`(인증+멤버십). 작업 유형 `document_edit`/`ingest`/`lint`/`restore`. 복구는 기여 원장(`wiki_page_contributions`) 기반 — 행을 지우지 않고 `active=false`, revision은 항상 append(단조 증가).

| Method | Path | 설명 |
|---|---|---|
| POST | `.../wiki/maintenance/lint` | Wiki lint 등록(202). dry-run/write 모두 `run_id` 반환, write만 `operation_id` 생성 |
| GET | `.../wiki/maintenance/runs/{run_id}` | lint run 상태와 `manifest.task_result` 조회 |
| GET | `.../wiki/maintenance/status` | `needs_lint`(마지막 lint 이후 위키 페이지 변경 여부), `last_lint_at`, `last_wiki_change_at` |
| GET | `.../ai-operation-logs` | 작업 목록. `type`/`status`/`cursor`(ISO-8601)/`size`(기본 20, 최대 100) 커서 페이징 |
| GET | `.../ai-operation-logs/{op}` | 상세 + `changes[]`(hunks는 조회 시 계산, 항목별 `diff_too_large`) |
| GET | `.../ai-operation-logs/{op}/restore-preview` | 복구 미리보기. 페이지별 `delete`/`restore`/`rebuild` 판정 + `preview_token` |
| POST | `.../ai-operation-logs/{op}/restore` | 되돌리기 실행(202). `preview_token` 필수, 대상 변경 시 409 `RESTORE_PREVIEW_STALE`; 승인한 contribution manifest와 command outbox를 먼저 저장하고 AI가 현재 본문·링크·embedding을 갱신한 뒤 core 감사 상태를 원자 반영 |

원문: docs/backlog/spec/api/ai-operation-log.md
