# API 요약

`docs/backlog/spec/api/` 7개 스펙 문서의 압축본. 상세 계약(요청/응답 JSON, 흐름, 정합성 규칙)은 각 원문 참조.

## 공통

- 서비스 라우팅: `/api/auth/*`·`/api/workspaces` → access-svc(:8081), 그 외 → document-svc(:8080).
- 인증: `Authorization: Bearer <access JWT(HS256, 기본 900s)>`. refresh는 opaque 토큰(DB에 sha256 해시만 저장, rotation).
- 사용자 API는 authenticated다. health·OpenAPI만 permitAll이다. `/internal/**`는 원칙적으로 `X-Internal-Token`을 검증하고, Agent worker가 document-svc의 Tool을 호출하는 `/internal/agent/tools/**`와 Skill 참조 read는 `X-Agent-Service-Token`을 검증한다.
- 에러 envelope: `{ "error": { "code", "message", "details" } }`. 검증 실패는 400 `INVALID_REQUEST` + field details. 예외→코드 전체 매핑은 원문 참조.
- `Idempotency-Key`가 적용된 API는 1~255자 키를 사용한다. 실행 선점 lease는 15분이고, 완료 응답은 완료 시점부터 24시간 유지한다. 같은 사용자·endpoint·키의 같은 요청이 완료되면 저장된 응답을 재생하고, 다른 payload는 409 `IDEMPOTENCY_CONFLICT`, lease 내 처리 중인 동시 요청은 409 `IDEMPOTENCY_IN_PROGRESS`로 거절한다. 실행이 실패하거나 lease가 만료되면 같은 키로 재시도할 수 있다.
- ID 형식: `user_`/`doc_`/`session_`/`query_`/`agent_`/`op_` + UUID/난수.
- LLM은 다음 세 조합만 지원한다. 기본값은 `openai`/`gpt-5-nano`이며 reasoning effort는 `minimal`, `gemini`/`gemini-3.1-flash-lite`는 `low`, `claude`/`claude-3-5-haiku-20241022`는 extended thinking을 사용하지 않는다. `provider`와 `model`은 항상 함께 생략하거나 함께 전달해야 하며, 다른 조합은 요청 검증 오류다.
- provider별 base URL은 `openai=https://api.openai.com/v1`, `gemini=https://generativelanguage.googleapis.com/v1beta/openai`, `claude=https://api.anthropic.com/v1`로 고정한다.
- Ingest·Lint·Skill author/publish/update는 workspace AI 모델 설정의 `provider`·`model` snapshot, Query·Markdown Agent·Agent 경로는 사용자/API 요청 또는 chat/request 설정의 snapshot을 사용한다. provider/model은 사용자 설정·API·DB·Kafka payload에서 전달하며 env override는 없다. API key는 ai-svc secret env의 `OPENAI_API_KEY`·`GEMINI_API_KEY`·`ANTHROPIC_API_KEY`에서만 읽고 provider별 고정 base URL을 사용한다.
- API key는 backend 요청·Kafka command/event·application log에 포함하지 않는다. 기존 AI 작업 로그의 조회/결과 API는 LLM 설정을 받지 않는다. 실제 provider 호출 전에는 선택 provider key가 필요하지만 mock 통합 테스트에는 key가 필요 없다.
- `allow_web_search`가 `true`일 때만 Tavily adapter를 구성하고 web route를 허용한다. `false`이면 내부 문서가 뒷받침하는 범위만 답하고 부족한 범위를 명시하며, 내부 근거가 전혀 없을 때만 unsupported로 처리한다.
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
- `PUT /api/workspaces/{workspace_id}/ai-model-settings`: OWNER만 활성 model catalog 내 `provider`+`model` 조합으로 변경. 요청/응답 필드는 `ingest_lint: { provider, model }`이며 기본값은 `openai`+`gpt-5-nano`.

## AI 모델

- `GET /api/ai-models`: backend의 활성 provider 설정에 포함된 선택 가능 model catalog를 반환한다. API key는 노출하지 않는다.

## Query

- 동기 `POST /api/workspaces/{workspace_id}/chat/sessions/{session_id}/query`와 비동기 `POST .../query/runs` 요청은 `question`(blank 불가), 함께 생략하거나 함께 전달하는 선택적 `provider`·`model`, 필수 boolean `allow_web_search`를 받는다. 누락/blank/잘못된 모델 조합은 400 `INVALID_REQUEST`다.
- `allow_web_search`는 해당 질의에만 적용되며 실행 당시 값은 채팅 메시지와 Query run에 snapshot으로 저장된다. 동기 응답과 비동기 run의 `result`에는 `web_search_requested`, `web_search_executed`, `result_count`, `error_code`가 포함된다. `error_code`는 `web_search_unavailable` 또는 `web_search_failed` 중 하나이며, 웹 검색 결과 URL·인증 토큰은 포함하지 않는다.

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
| PATCH | `/documents/{document_id}/position` | 문서를 대상 폴더와 정렬 위치로 이동(`folder_id`, `position`, `base_version`). `Idempotency-Key`로 멱등 처리 |

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
| GET | `/agent/turn/{run_id}` | 조회 직전에 현재 workspace 멤버십을 확인한 뒤 scope가 포함된 AI 내부 상태 API를 호출해 `queued`/`executing`/`completed`/`failed` 반환. AI 내부 상태 API가 404이면 core projection의 `queued`/`ready`/`failed` 상태와 `result`/`error`를 그대로 반환한다. 비멤버·unknown run은 404, 잘못된 run ID 형식은 400 |
| GET | `/agent/runs/{run_id}` | 현재 workspace 멤버십을 확인한 뒤 자율 AgentRun의 계획·상태를 조회한다. AI AgentRun API에는 Backend가 service token과 path의 workspace·JWT 사용자 scope를 주입한다 |
| POST | `/agent/runs/{run_id}/approve` | `plan_version`, `operation_hash`로 현재 계획을 승인한다. workspace·user scope는 요청 본문에 받지 않고 Backend가 주입한다 |
| POST | `/agent/runs/{run_id}/reject` | 현재 계획을 거절한다. workspace·user scope는 Backend가 주입한다 |
| POST | `/agent/runs/{run_id}/cancel` | AgentRun을 취소한다. workspace·user scope는 Backend가 주입한다 |
| POST | `/agent/runs/{run_id}/revise` | `instruction`으로 새 계획을 요청한다. workspace·user scope는 요청 본문에 받지 않고 Backend가 주입한다 |

AgentRun lifecycle API가 AI에서 404를 받으면 `AGENT_RUN_NOT_FOUND`(404)를, 그 외 4xx를 받으면 원문을 노출하지 않고 `AGENT_REQUEST_REJECTED`(AI 응답 status 유지)를 반환한다. AI 5xx·timeout·unavailable은 `AGENT_PIPELINE_UNAVAILABLE`(503)로 반환한다.

`POST /agent/turn` 요청은 `documentId`, `baseVersion`(0 이상), `message`, `editorSnapshot`(필수 Markdown 및 선택 target)을 받으며 `provider`·`model`은 함께 생략하거나 함께 전달한다. `conversationContext`는 선택이며, `conversationContext.pendingSkillProposal`은 `scope_type`, `name`, `description`, `instructions_markdown` 전체 필드로 구성된 미게시 제안이다(`published` 필드/상태는 포함하지 않음). 같은 제안의 승인·보안 재검토·재생성·제목/범위 변경 같은 후속 turn에도 이 제안을 전달해 다회차로 처리한다. `skill_draft_sources`는 `{run_id}` selector만 받으며, document-svc가 같은 workspace/user의 완료된 autonomous AgentRun과 성공 operation을 ai-svc에서 다시 읽어 canonical 요약을 Kafka command에 넣는다. 잘못된 문서 형식/target은 400, version 충돌은 409, 편집 lock은 423이다.

AI 편집 적용 저장은 Backend가 발급한 `apply_operation_id`와 요청의 `revision_write_id`를 정확한 pair로 claim한다. 최초 claim은 projection의 `base_version`과 canonical `ready_markdown`이 요청과 일치할 때만 한 번 소비되고, 동일 pair 재시도는 기존 소비를 재사용하며 다른 pair는 거절한다. 결과가 유효하지 않거나 canonical Markdown을 만들 수 없으면 projection은 `failed`가 된다.

`folder_organize`·`workspace_workflow` 결과는 canonical Markdown 없이도 AgentRun projection에 반영한다. 이 결과는 Markdown 적용 대상이 아니므로 `ready_markdown`을 만들지 않으며, 기존 `markdown_create`·`markdown_edit` 결과의 canonical Markdown 검증은 그대로 적용한다.

Agent Tool P0 내부 계약:

| Method | Path | 설명 |
|---|---|---|
| POST | `/internal/agent/tools/read/{tool_name}` | ai-svc worker가 `X-Agent-Service-Token`으로 document-svc의 read Tool을 호출. 필수 arguments는 `list_root_items`=없음, `list_folder_children`=`folder_id`, `search_hierarchy`=`query`, `get_document_metadata`=`document_id`, `get_document_content`=`document_id`, `get_breadcrumb`=`folder_id`와 `document_id`(두 키 필수, 정확히 하나만 non-null)다. `list_agent_run_artifacts`는 worker planning만 사용하는 내부 helper다. document-svc가 workspace 멤버십·문서 scope를 확인하고 canonical 본문은 MongoDB에서 읽는다 |
| POST | `/internal/agent/tools/execute/{tool_name}` | `create_folder`, `rename_folder`, `move_folder`, `move_document`, `rename_document`, `create_document`, `apply_document_edit`를 허용한다. ai_db의 승인된 현재 operation·인자와 정확히 일치해야 document-svc가 멱등 실행하며, 문서 생성·편집은 승인된 artifact의 hash·목적·문서/버전/target을 ai-svc에서 다시 검증한 Markdown만 사용한다 |
| POST | `/internal/agent/runs/artifacts/register` | ai-svc가 run/workspace/user에 결합된 Markdown artifact를 기존 object storage에 저장하고 hash·purpose·target 메타데이터를 ai_db에 기록한다. `X-Internal-Token` 전용이며 입력 artifact는 이 등록을 선행해야 한다 |
| POST | `/internal/agent/runs/artifacts/list` | worker planning이 같은 run actor scope의 artifact 메타데이터만 조회한다. 본문·object key는 반환하지 않는다 |
| POST | `/internal/agent/runs/artifacts/resolve` | document-svc가 승인 operation과 일치하는 artifact id/hash/purpose/document/base/target을 전달하면 ai-svc가 scope·metadata·object storage hash를 재검증하고 Markdown을 반환한다. 본문은 `X-Internal-Token` 전용 응답이다 |
| POST | `/internal/agent/runs/tool-authorizations/read` | document-svc가 `X-Internal-Token`으로 ai-svc에 run/workspace/user scope 조회 인가 요청 |
| POST | `/internal/agent/runs/tool-authorizations/execute` | document-svc가 ai-svc에 plan version·operation hash·tool·선행 operation 결과를 포함한 승인 인자 일치 인가 요청 |

Kafka command에는 `run_id`, workspace/user/document, `base_version`, `apply_operation_id`, instruction/editor snapshot을 포함한다. 동일 `run_id` 재전달은 전체 envelope hash가 같을 때만 기존 결과를 재사용한다. 생성된 편집안은 문서를 바꾸지 않으며, 유효한 성공 result event가 projection을 ready로 만든 뒤 사용자가 저장할 때 PostgreSQL apply operation row를 projection의 `base_version`·canonical `ready_markdown`과 대조해 `operation_id`+`revision_write_id` exact pair로 claim하고 `document_edit/applying` pending 감사 상태를 먼저 기록한다. 이후 MongoDB 본문 receipt를 저장하고 PostgreSQL version link/audit를 확정하며, 중간 실패 시 같은 `revision_write_id` 재시도가 receipt를 재생해 pending 감사를 완료한다. 유효하지 않은 result event는 `failed`로 기록한다. 기존 `/skills/*`·`/agent/runs/*`의 `X-Agent-Service-Token` 계약과 Spring용 `/internal/agent/runs/**`의 `X-Internal-Token` 계약을 유지한다.

## Skill

베이스 `/api/workspaces/{workspace_id}/skills`. document-svc는 JWT principal과 path workspace를 신뢰 경계로 삼아 멤버십을 fail-closed 확인한 뒤, `X-Agent-Service-Token`으로 보호된 ai-svc Skill API에 두 scope 값만 추가해 중계한다. Skill·version 저장은 ai_db 소유이며 document-svc는 Skill 엔티티를 저장하지 않는다.

| Method | Path | 설명 |
|---|---|---|
| POST | `/skills/author` | `scope_type`, `name`, `description`, `instruction`, 선택적 `authoring_mode`·`reference_document_ids`(최대 3)로 미저장 Skill 제안 생성. provider/model은 workspace AI 설정 snapshot 사용 |
| POST | `/skills/author/publish` | `scope_type`, `name`, `description`, `instructions_markdown`(최대 30,000자)로 게시. provider/model은 workspace AI 설정 snapshot 사용 |
| GET | `/skills` | 개인·현재 Workspace 팀 Skill 목록 |
| GET | `/skills/{skill_id}` | 접근 가능한 Skill 상세 |
| PATCH | `/skills/{skill_id}` | `name`, `description`, `instructions_markdown`(최대 30,000자)로 새 게시 version으로 갱신. provider/model은 workspace AI 설정 snapshot 사용 |
| POST | `/skills/{skill_id}/enable` | Skill 자동 라우팅 활성화 |
| POST | `/skills/{skill_id}/disable` | Skill 자동 라우팅 비활성화 |

참조 문서는 ai-svc가 `POST /internal/agent/skill-authoring/references/read`를 호출해 scope와 role을 확인한다. document-svc는 service token, workspace 멤버십과 활성 문서를 검증하고, EDITABLE은 workspace가 일치하는 MongoDB canonical Markdown을 반환한다. ORIGINAL은 role만 반환하며 ai-svc가 소유한 ai_db `source_blocks`를 `block_id` 순으로 조립한다. Skill author/publish/update public API body는 `provider`, `model`을 받지 않으며, backend가 workspace AI 설정을 snapshot해 ai-svc 내부 payload에 `provider`·`model`만 전달한다(key·base URL은 전달하지 않는다). Agent 경유 Skill LLM 호출은 Agent의 chat/request snapshot을 사용한다.

ai-svc의 Skill 관리·작성 API는 `SKILL_API_ENABLED`(기본 `true`), `/skills/draft-from-runs/preview`와 `/agent/runs/*`는 `AGENT_SKILLS_ENABLED`(기본 `false`)로 독립 제어한다. 배포 환경 설정은 Agent와 Skill route를 함께 활성화하도록 `AGENT_SKILLS_ENABLED=true`, `SKILL_API_ENABLED=true`를 주입한다. EDITABLE 참조 Markdown은 문서당 30,000자까지 허용하며 초과 시 413 `REFERENCE_DOCUMENT_TOO_LARGE`를 반환한다. ai-svc가 거부한 일반 Skill 4xx는 상태를 유지하고 `{ "error": { "code": "SKILL_REQUEST_REJECTED", "message": "..." } }` envelope로 정규화한다.

## 채팅

베이스 `/api/workspaces/{workspace_id}/chat/sessions`. 세션은 멤버당 최대 10개. 메시지 생성은 쿼리 도메인이 담당(아래 절).

| Method | Path | 설명 |
|---|---|---|
| POST | `/chat/sessions` | 세션 생성(201). 초과 시 409 `CHAT_SESSION_LIMIT_EXCEEDED` |
| GET | `/chat/sessions` | 본인 세션 목록(최근 메시지 순) |
| DELETE | `/chat/sessions/{id}` | 세션 삭제(204). 메시지·참조는 FK CASCADE |
| GET | `/chat/sessions/{id}/messages` | 메시지 기록. references·related_pages·wiki 편입 정보 포함 |
| POST | `/chat/sessions/{id}/wiki` | wiki export(202). `selection_mode`=`full`\|`partial`(+`pair_ids`), 응답 `status`=`processing`\|`skipped`. 같은 workspace의 `chat_export`는 `(workspace_id, content_hash, selection_mode)` 조합으로 dedup하며, 완료는 3초 주기 폴링 reconciler가 반영 |
| POST | `/chat/sessions/{id}/wiki/preview` | 직렬화·마스킹된 Markdown 미리보기(text/plain, 저장 없음) |

원문: docs/backlog/spec/api/chat.md

## 쿼리

질의 생성과 run 조회·SSE는 인증 후 세션/workspace 소유권을 확인한다. run과 SSE replay buffer는 Redis에 저장하며 종료 후 TTL은 10분이다. 비동기 API는 pending chat pair와 command outbox를 원자 저장하고 Kafka worker 결과를 받아 assistant·참조·관련 페이지를 반영한다.

| Method | Path | 설명 |
|---|---|---|
| POST | `.../chat/sessions/{id}/query` | 동기 질의(200). 요청 `question`, 선택 `provider`+`model`(함께 생략 시 `openai`+`gpt-5-nano`). 응답: user/assistant 메시지, `related_pages`, `evidence_snippets`, `graph_context`, `traversal_paths`. 파이프라인 오류 502/503 |
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
| POST | `.../wiki/maintenance/lint` | `materialize_promotions`·`dry_run` 선택 필드로 Wiki lint 등록(202). workspace 설정 snapshot을 사용하며 dry-run/write 모두 `run_id` 반환, write만 `operation_id` 생성 |
| GET | `.../wiki/maintenance/runs/{run_id}` | lint run 상태와 `manifest.task_result` 조회 |
| GET | `.../wiki/maintenance/status` | `needs_lint`(마지막 lint 이후 위키 페이지 변경 여부), `last_lint_at`, `last_wiki_change_at` |
| GET | `.../ai-operation-logs` | 작업 목록. `type`/`status`/`cursor`(ISO-8601)/`size`(기본 20, 최대 100) 커서 페이징 |
| GET | `.../ai-operation-logs/{op}` | 상세 + `changes[]`(hunks는 조회 시 계산, 항목별 `diff_too_large`). restore 작업에는 검증 가능한 계획·결과 요약을 `restore`로 추가 |
| GET | `.../ai-operation-logs/{op}/restore-preview` | 복구 미리보기. 페이지별 `delete`/`restore`/`rebuild` 판정 + `preview_token` |
| POST | `.../ai-operation-logs/{op}/restore` | 되돌리기 실행(202). `preview_token` 필수, 대상 변경 시 409 `RESTORE_PREVIEW_STALE`, 같은 토큰 재실행은 기존 요청을 만들지 않고 400 `INVALID_RESTORE_REQUEST`; 승인한 contribution manifest와 command outbox를 먼저 저장하고 AI가 현재 본문·링크·embedding을 갱신한 뒤 core 감사 상태를 원자 반영 |

복구 상세의 `restore`는 복구 작업에만 포함되며, `plan`에는 고정된 페이지별 `delete`/`restore`/`rebuild` 조치와 조치별 개수가, `result`에는 `changes[]`에서 집계한 페이지·링크·실패 효과 개수가 담긴다. `failed_actions`는 `changes[]`에 `resource_type=action`, `change_type=action_failed`로 저장하며 안정적인 action/resource ID와 공개된 실패 코드만 노출한다. 실제 page/resource ID와 효과는 기존 `changes[]`가 기준이므로 결과에 다시 복제하지 않는다. 비복구 작업은 기존 응답과 같이 `restore` 필드를 생략하며, 내부 callback URL·preview token·provider payload/error·기여 object key와 원본 `restore_manifest`는 반환하지 않는다.

```json
{
  "restore": {
    "plan": {
      "delete_count": 1,
      "restore_count": 1,
      "rebuild_count": 1,
      "pages": [{"page_id": "wp_C3", "action": "rebuild", "contribution_count": 2}]
    },
    "result": {
      "deleted_count": 1,
      "restored_count": 1,
      "rebuilt_count": 0,
      "failed_count": 1,
      "removed_link_count": 2,
      "restored_link_count": 1
    }
  }
}
```

AI 내부 FastAPI 요청은 Pydantic schema 검증을 따른다. Query는 `workspace_id`, `question`, `provider`, `model`, `allow_web_search`가 필수이고 `recent_messages`는 최대 6개, `output_language`는 `ko|en|document`, `response_length`는 `concise|balanced|detailed`다. Pipeline ingest/reingest/chat-wiki는 `document_id`, `provider`, `model`이 필수이며 `selection_mode`는 `full|partial`, Lint는 `provider`, `model`이 필수이고 write(`dry_run=false`)에는 `operation_id`가 필수다. Agent·Skill 내부 요청도 provider/model pair를 필수로 검증하며 schema 오류는 422다.

원문: docs/backlog/spec/api/ai-operation-log.md
