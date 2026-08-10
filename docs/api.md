# API 요약

`docs/backlog/spec/api/` 7개 스펙 문서의 압축본. 상세 계약(요청/응답 JSON, 흐름, 정합성 규칙)은 각 원문 참조.

## 공통

- 서비스 라우팅: `/api/auth/*`·`/api/workspaces` → access-svc(:8081), 그 외 → document-svc(:8080).
- 인증: `Authorization: Bearer <access JWT(HS256, 기본 900s)>`. refresh는 opaque 토큰(DB에 sha256 해시만 저장, rotation).
- 사용자 API는 authenticated다. health·OpenAPI만 permitAll이며 `/internal/**`는 `X-Internal-Token`을 별도로 검증한다.
- 에러 envelope: `{ "error": { "code", "message", "details" } }`. 검증 실패는 400 `INVALID_REQUEST` + field details. 예외→코드 전체 매핑은 원문 참조.
- ID 형식: `user_`/`doc_`/`session_`/`query_`/`agent_`/`op_` + UUID/난수.
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

Kafka command에는 `run_id`, workspace/user/document, `base_version`, `apply_operation_id`, instruction/editor snapshot을 포함한다. 동일 `run_id` 재전달은 전체 envelope hash가 같을 때만 기존 결과를 재사용한다. 생성된 편집안은 문서를 바꾸지 않으며, 성공 result event가 projection을 ready로 만든 뒤 사용자가 저장할 때 `apply_operation_id`를 operation/version audit와 같은 core 트랜잭션에서 한 번만 소비한다. 기존 `/skills/*`·`/agent/runs/*`의 `X-Agent-Service-Token` 계약과 Spring용 `/internal/agent/runs/{run_id}`의 `X-Internal-Token` 계약을 유지한다.

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
| POST | `.../chat/sessions/{id}/query` | 동기 질의(200). 요청 `question`. 응답: user/assistant 메시지, `related_pages`, `evidence_snippets`, `graph_context`, `traversal_paths`. 파이프라인 오류 502/503 |
| POST | `.../chat/sessions/{id}/query/runs` | 비동기 질의 시작(202). 응답 `request_id`, `status=pending` |
| GET | `/api/query/runs/{requestId}/events` | **SSE** 완료 구독. 이벤트 `query.completed`/`query.failed`, buffer 200건 재생 |
| GET | `/api/query/runs/{requestId}` | run 상태 **폴링**(`pending`/`running`/`completed`/`failed`, 완료 시 `result`) |

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
