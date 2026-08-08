# API 요약

`docs/backlog/spec/api/` 7개 스펙 문서의 압축본. 상세 계약(요청/응답 JSON, 흐름, 정합성 규칙)은 각 원문 참조.

## 공통

- 서비스 라우팅: `/api/auth/*`·`/api/workspaces` → access-svc(:8081), 그 외 → document-svc(:8080).
- 인증: `Authorization: Bearer <access JWT(HS256, 기본 900s)>`. refresh는 opaque 토큰(DB에 sha256 해시만 저장, rotation).
- `/api/auth/me`·`/api/workspaces/**`는 authenticated, 나머지는 permitAll(파이프라인 콜백 경로 포함). `/api/ai-operations/**`는 `X-Internal-Token` 헤더 검증.
- 에러 envelope: `{ "error": { "code", "message", "details" } }`. 검증 실패는 400 `INVALID_REQUEST` + field details. 예외→코드 전체 매핑은 원문 참조.
- ID 형식: `user_`/`doc_`/`session_`/`query_`/`op_` + UUID/난수.
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
| GET | `/documents?query=` | 활성 문서 평면 목록. filename/display_name 부분 검색(본문 미검색) |
| GET | `/documents/{id}` | 상세 + 최신 `markdown` + `wiki_pages`. `current_version`이 이후 `base_version` |
| GET | `/documents/{id}/original` | 업로드 원본 스트리밍(MinIO). 직접 생성 문서는 404 |
| GET | `/documents/{id}/blocks` | 원본 block 목록(`block_id`, `text`) |
| PUT | `/documents/{id}/content` | 본문 수동 저장(multipart: `markdown`, `base_revision`, `revision_write_id`). 동일 본문 `changed=false`, 5MB 초과 413. 이미지 포함 저장은 `metadata`(JSON: `markdown`+`base_version`) + `attachment_*` file part — 본문 placeholder `attachment://{uuid}`가 asset content 경로로 치환되고 응답 `attachments`에 매핑 반환. 이미지 개당 50MB·합계 100MB 초과 413, 미지원 형식 415 |
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

파이프라인 콜백(내부, 베이스 `/api/documents`):

| Method | Path | 설명 |
|---|---|---|
| PATCH | `/api/documents/{id}/status` | 처리 상태 갱신(`status` 필수) |
| POST | `/api/documents/{id}/pipeline-events` | heartbeat. 현재 `pipeline_run_id` 불일치 이벤트는 무시 |

원문: docs/backlog/spec/api/document.md

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

질의 생성은 세션 하위(인증+소유권), run 조작(`/api/query/runs/**`)은 현재 permitAll — `request_id`를 아는 누구나 접근 가능(알려진 이슈). run은 in-memory(TTL: 종료 후 10분, 재시작 시 소실).

| Method | Path | 설명 |
|---|---|---|
| POST | `.../chat/sessions/{id}/query` | 동기 질의(200). 요청 `question`. 응답: user/assistant 메시지, `related_pages`, `evidence_snippets`, `graph_context`, `traversal_paths`. 파이프라인 오류 502/503 |
| POST | `.../chat/sessions/{id}/query/runs` | 비동기 질의 시작(202). 응답 `request_id`, `status=pending` |
| GET | `/api/query/runs/{requestId}/events` | **SSE** 진행 로그 구독. 이벤트 `query.log`/`query.completed`/`query.failed`, buffer 200건 재생, heartbeat 미가동 |
| POST | `/api/query/runs/{requestId}/events/callback` | 파이프라인 진행 이벤트 수신(내부 콜백) → SSE 발행 |
| GET | `/api/query/runs/{requestId}` | run 상태 **폴링**(`pending`/`running`/`completed`/`failed`, 완료 시 `result`) |

원문: docs/backlog/spec/api/query.md

## 위키

베이스 `/api/workspaces/{workspace_id}/wiki`. `wiki_pages` 쓰기는 llmPipeline 소유 — Backend는 revision 적재(rename 제외 본문 쓰기 없음). 현재 본문·diff는 `wiki_page_versions` 최신 revision 기준. 삭제 판정은 활성 기여 유무(기여 전부 비활성 = 삭제, 조회 제외).

| Method | Path | 설명 |
|---|---|---|
| GET | `/wiki/graph` | 지식 그래프. `nodes`(source 노드는 `source_document` 포함) + `edges`. 양 끝점이 워크스페이스 내인 링크만 |
| GET | `/wiki/pages/{id}` | 페이지 상세. `markdown`(최신 revision, 실패 시 null), `source_documents`, `related_pages`(아웃링크) |
| GET | `/wiki/pages/{id}/diff?from=&to=` | 두 revision 간 diff(요청 시 계산). 과대 시 422 `MARKDOWN_DIFF_TOO_LARGE`. 삭제된 페이지도 조회 가능 |
| PATCH | `/wiki/pages/{id}/rename` | 제목 변경. `update_slug=true`면 slug 재생성(충돌 409 `WIKI_PAGE_SLUG_CONFLICT`) |

원문: docs/backlog/spec/api/wiki.md

## AI 작업 로그

베이스 `/api/workspaces/{workspace_id}/ai-operation-logs`(인증+멤버십). 작업 유형 `document_edit`/`ingest`/`lint`/`restore`. 복구는 기여 원장(`wiki_page_contributions`) 기반 — 행을 지우지 않고 `active=false`, revision은 항상 append(단조 증가).

| Method | Path | 설명 |
|---|---|---|
| POST | `.../wiki/maintenance/lint` | Wiki lint 실행. `dryRun` true/생략은 미리보기(로그 없음), false면 lint 작업 등록 후 동기 반영 |
| GET | `.../ai-operation-logs` | 작업 목록. `type`/`status`/`cursor`(ISO-8601)/`size`(기본 20, 최대 100) 커서 페이징 |
| GET | `.../ai-operation-logs/{op}` | 상세 + `changes[]`(hunks는 조회 시 계산, 항목별 `diff_too_large`) |
| GET | `.../ai-operation-logs/{op}/restore-preview` | 복구 미리보기. 페이지별 `delete`/`restore`/`rebuild` 판정 + `preview_token` |
| POST | `.../ai-operation-logs/{op}/restore` | 되돌리기 실행. `preview_token` 필수, 대상 변경 시 409 `RESTORE_PREVIEW_STALE`. ingest는 지목 작업 이후 같은 문서 ingest 전부 취소, 재조립은 llmPipeline에 위임(`rebuilding`) |
| POST | `/api/ai-operations/{op}/result` | llmPipeline 결과 콜백(내부, `X-Internal-Token`). ingest/재조립 공용, `payload_hash` 멱등, key·hash 검증 후 반영 |

원문: docs/backlog/spec/api/ai-operation-log.md
