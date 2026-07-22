# Markdown 문서 편집 Core

## 1. 문서 정보

- 상태: Draft
- 작성일: 2026-07-23
- 구현 계획: [`markdown-document-core-tasks.md`](./markdown-document-core-tasks.md)
- 관련 PR:

상태 흐름: `Draft → Approved → In Progress → Verified`

## 2. 배경

현재 `Document`는 PDF와 Markdown 업로드 파일, 초기 Markdown 노트를 하나의 목록에서 관리한다. Markdown 편집 기능도 이 통합 목록과 문서 ID를 유지해야 한다.

업로드 원본은 재처리와 원본 조회의 기준이므로 변경하지 않는다. 사용자는 직접 만든 Markdown, 업로드한 Markdown, PDF 등에서 변환된 Markdown을 같은 편집기로 수정한다.

## 3. 목표

- 기존 `Document`를 업로드 파일과 Markdown 편집 문서의 통합 식별자로 유지한다.
- Markdown 문서를 직접 생성하고 전체 본문을 자동·수동 저장한다.
- 변환이 필요한 원본은 파서가 Markdown을 생성한 뒤 편집 가능하게 한다.
- 이름 변경, 복제, 소프트 삭제·복구, 검색·정렬, Markdown 내보내기를 제공한다.
- 버전 번호로 오래된 편집이 최신 내용을 덮어쓰지 못하게 한다.
- 요구사항과 인수 조건을 자동·통합 테스트에 추적한다.

## 4. 범위

### 포함

- 통합 문서 목록·상세 조회
- Markdown 직접 생성 및 Markdown 파일 업로드 즉시 편집
- PDF 등 원본의 Markdown 변환 완료 후 편집
- 전체 본문 저장, 이름 변경, 복제
- 워크스페이스 공용 수동 정렬과 파일명 검색
- 소프트 삭제, 휴지통, 복구
- Markdown 및 이미지 bundle 내보내기
- 워크스페이스 멤버 권한과 낙관적 잠금

### 제외

- 버전 이력 조회·과거 버전 복원
- 이미지 업로드·접근 권한·정리 정책의 상세 설계
- 공유 링크의 생성·만료·해제
- AI 편집, diff, 적용
- PDF·HTML 내보내기
- 파이프라인 재처리 결과와 사용자 편집본의 병합
- 영구 삭제 정책

## 5. 요구사항

### 5.1 생성과 변환

#### REQ-001 Markdown 직접 생성

워크스페이스 멤버는 표시 이름과 5MB 이하의 Markdown 본문으로 문서를 생성할 수 있어야 한다.

- Given: 인증된 사용자가 워크스페이스 멤버이다.
- When: `display_name`과 `markdown`으로 생성을 요청한다.
- Then: `text/markdown`, `completed`, version `1`인 `Document`와 편집 상태를 생성한다.
- Then: `source_uri`와 `source_document_id`는 `null`이다.
- Then: 빈 문자열 본문은 허용하고 `null`은 거절한다.
- Then: 새 문서는 공용 목록의 가장 뒤에 배치한다.
- Then: 신규 워크스페이스 초기 노트도 이 경로로 생성한다(기본 Markdown, `source_uri=null`, edit_state version `1`, MinIO 저장 없음). 기존 `createInitialNote`의 MinIO 저장 방식은 이 경로로 재배선한다.

#### REQ-002 Markdown 업로드 즉시 편집

- Given: 유효한 Markdown 파일을 업로드한다.
- When: 원본을 MinIO에 불변 저장한다.
- Then: 업로드 원문으로 version `1`의 편집 상태를 함께 생성한다.
- Then: 파이프라인 처리 완료를 기다리지 않고 `editable=true`를 반환한다.

#### REQ-003 변환 원본 편집 활성화

- Given: PDF 등 변환이 필요한 파일이 업로드되어 있다.
- When: 파서가 Markdown을 생성하고 `converted_markdown_uri`와 checksum을 callback한다.
- Then: 백엔드는 checksum과 UTF-8 5MB 제한을 검증하고 같은 `Document.id`에 최초 편집 상태를 생성한다.
- Then: 새 목록 항목을 만들지 않고 기존 문서를 편집 가능하게 한다.
- Then: 변환 실패 시 편집 상태를 만들지 않고 `failed` 상태를 표시한다.

#### REQ-004 중복 검사

- 활성 문서의 중복 기준은 `workspace_id + normalized_filename + current_content_hash`이다.
- 파일명과 내용이 모두 같으면 `409 Conflict`를 반환한다.
- 둘 중 하나 이상이 다르면 생성을 허용한다.
- 소프트 삭제 문서는 일반 생성 중복 검사에서 제외한다.
- **DB 제약**: 기존 V5의 UNIQUE `(workspace_id, content_hash)`(제약명 `uq_documents_workspace_content_hash`)를 제거하고, `(workspace_id, normalized_filename, current_content_hash) WHERE deleted_at IS NULL` partial unique index로 교체한다. 소프트 삭제 문서는 index 대상에서 빠지며 복구·재생성 충돌 검사(REQ-018)만 별도 적용한다.
- **업로드 원본 해시(`content_hash`)는 더 이상 UNIQUE 대상이 아니다.** 업로드 중복 판정도 `normalized_filename + current_content_hash` 기준으로 통일한다.

### 5.2 조회·검색·정렬

#### REQ-005 통합 목록 조회

- 업로드 파일과 Markdown 문서를 기존 `/documents` 목록에서 함께 반환한다.
- 목록에는 `id`, `filename`, `display_name`, `file_type`, `mime_type`, 처리 상태, `editable`, `current_version`, 원본 참조, 생성·수정 시각을 포함한다.
- 본문과 소프트 삭제 문서는 일반 목록에서 제외한다.
- 채팅 Wiki page화 export 문서(`origin='chat_export'`)는 기존과 동일하게 목록에서 제외한다(회귀 방지, 현재 `findVisibleByWorkspaceId` 규칙 유지).
- 변환 중·실패 문서도 처리 상태와 함께 표시한다.
- 20개 단위 cursor 페이지네이션을 제공한다.

> **호환성**: 현재 `GET /documents`는 `{ "documents": [...] }` 전체 목록을 반환한다. cursor 페이지네이션 도입은 응답 형태를 바꾸는 breaking change이므로, 프론트 목록 소비 코드(`frontend/app/_lib/api.ts`, 문서 사이드바)를 같은 범위에서 함께 전환한다. 전환 전까지는 20개 초과 문서가 있는 워크스페이스에서 목록 누락이 발생할 수 있다.

#### REQ-006 상세 조회

- 활성 문서 상세에 현재 전체 Markdown과 `current_version`을 반환한다.
- 편집 상태가 없는 변환 중·실패 문서는 원본 및 처리 메타데이터만 반환한다.
- 다른 워크스페이스 또는 삭제 문서는 일반 상세 API에서 `404 Not Found`로 처리한다.

#### REQ-006a 레거시 원본 API의 직접 생성 문서 처리

- `/original`: `source_uri`가 있으면 기존대로 MinIO 원본을 스트리밍한다. `source_uri`가 `null`인 직접 생성 문서는 편집 상태의 현재 Markdown을 `text/markdown`으로 반환한다. 편집 상태도 없으면 `404`.
- `/blocks`: 파이프라인 source block이 없는 직접 생성 문서는 `200`에 빈 목록(`blocks: []`)을 반환한다.
- 두 API는 기존 업로드·파이프라인 문서에서 회귀 없이 동작해야 한다.

#### REQ-007 파일명 검색

- `display_name`과 실제 `filename`의 일부 문자열을 대소문자 구분 없이 검색한다.
- 본문은 검색하지 않는다.
- 검색 결과에도 공용 순서와 cursor 페이지네이션을 적용한다.

#### REQ-008 공용 수동 정렬

- 모든 워크스페이스 멤버는 전체 목록에서 문서를 앞이나 뒤로 이동할 수 있다.
- 순서는 워크스페이스 전체가 공유한다.
- 검색 결과에서는 순서를 변경할 수 없다.
- 생성·복제·복구 문서는 가장 뒤에 배치한다.

### 5.3 본문 저장

#### REQ-009 전체 본문 저장

- 자동 저장과 `Cmd/Ctrl+S` 수동 저장은 같은 API를 사용한다.
- 요청은 전체 `markdown`과 `base_version`을 전달한다.
- 현재 본문, 해시, 버전, 수정 시각을 하나의 DB 트랜잭션에서 갱신한다.
- 업로드 원본과 원본 해시는 변경하지 않는다.

#### REQ-010 변경 없는 저장

- 요청 본문이 현재 본문과 같으면 성공하되 `changed=false`를 반환한다.
- 버전, 수정 시각, 버전 이력을 변경하지 않는다.

#### REQ-011 저장 충돌

- `base_version`과 서버 `current_version`이 다르면 `409 Conflict`를 반환한다.
- 충돌 시 기존 본문, 파일명, 버전을 변경하지 않는다.

#### REQ-012 본문 검증

- 빈 문자열은 허용하고 `null` 또는 필드 누락은 `400 Bad Request`로 처리한다.
- UTF-8 byte 기준 5MB 초과는 `413 Payload Too Large`로 처리한다.

### 5.4 이름 변경

#### REQ-013 표시 이름 변경

- API는 확장자를 제외한 `display_name`과 `base_version`을 받는다.
- PDF는 `.pdf`, Markdown은 `.md` 등 기존 문서 형식의 확장자를 유지한다.
- 본문, 첫 heading, 업로드 원본은 변경하지 않는다.
- 실제 이름이 바뀌면 버전과 수정 시각을 갱신한다.
- 정규화 결과가 현재 이름과 같으면 `changed=false`로 성공한다.

#### REQ-014 파일명 검증

- 앞뒤 공백을 제거한다.
- 빈 이름, `/`, `\\`, 제어문자, null 문자, `.`, `..`을 허용하지 않는다.
- 확장자를 포함해 최대 255자까지 허용한다.
- 변경 결과가 활성 문서와 같은 파일명·내용 조합이면 `409 Conflict`를 반환한다.

### 5.5 복제

#### REQ-015 최신 편집본 복제

- 복제는 같은 워크스페이스에서 요청 시점의 최신 Markdown을 사용한다.
- `base_version`을 요구하지 않는다.
- 새 문서는 `text/markdown`, `completed`, version `1`로 시작한다.
- 업로드 문서 복제본은 원본 `Document.id`를 `source_document_id`로 참조한다.
- 원본 파일, 버전 이력, 공유 설정은 복제하지 않고 이미지 URL은 재사용한다.

#### REQ-016 복제본 이름

```text
보고서.pdf → 보고서 복사본.md
보고서 복사본.md → 보고서 복사본 (2).md
```

- 서버는 사용 가능한 다음 번호를 원자적으로 선택한다.
- 접미사로 255자를 초과하면 파일명 본체를 줄인다.
- 복제본은 목록의 가장 뒤에 배치한다.

### 5.6 삭제와 복구

#### REQ-017 소프트 삭제

- 모든 워크스페이스 멤버가 현재 `base_version`으로 삭제할 수 있다.
- `deleted_at`, `deleted_by`를 기록하고 버전을 증가시킨다.
- 처리 상태, 원본, 편집 본문, 버전 및 이미지는 제거하지 않는다.
- 삭제 문서는 일반 목록·상세·저장·복제·내보내기에서 `404`로 처리한다.

#### REQ-018 휴지통과 복구

- 삭제 문서는 휴지통 API에서 조회한다.
- 복구는 삭제 문서의 `base_version`을 검증한다.
- 복구 시 삭제 정보를 제거하고 버전을 증가시키며 목록 마지막에 배치한다.
- 동일 파일명·내용의 활성 문서가 생겼으면 `409 Conflict`로 복구를 거절한다.

### 5.7 내보내기

#### REQ-019 Markdown 내보내기

- 활성 문서의 요청 시점 최신 Markdown을 UTF-8로 내보낸다.
- 이미지가 없으면 현재 표시 이름을 사용하는 `.md`를 반환한다.
- `base_version`을 요구하지 않고 문서 상태를 변경하지 않는다.

#### REQ-020 이미지 bundle

- Fruition 관리 이미지가 있으면 `.md`와 `assets/`를 포함한 ZIP을 반환한다.
- 관리 이미지 URL을 ZIP 내부 상대 경로로 바꾼다.
- 같은 이미지는 한 번만 포함한다.
- 외부 이미지 URL은 fetch하지 않고 그대로 유지한다.
- 관리 이미지 하나라도 읽지 못하면 불완전한 ZIP 대신 전체 요청을 실패 처리한다.

## 6. 설계

### 데이터 모델

`documents`에 다음 필드를 추가한다.

| 필드 | 설명 |
|---|---|
| `normalized_filename` | 검색·중복 검사용 파일명 |
| `source_document_id` | 복제본의 원본 문서 self-reference |
| `current_content_hash` | 현재 편집 내용 또는 업로드 내용 해시 |
| `current_version` | 문서 수명주기 낙관적 잠금 버전. 생성 시 `1`, rename·본문저장·삭제·복구 시 증가 |
| `sort_order` | 워크스페이스 공용 순서 |
| `updated_at` | 마지막 변경 시각 |
| `deleted_at`, `deleted_by` | 소프트 삭제 정보 |

직접 생성 Markdown을 위해 기존 `source_uri`와 원본 `content_hash`는 nullable로 변경한다. 기존 `content_hash`는 업로드 원본의 불변 해시로 유지한다.

`document_edit_states`를 추가한다. 버전은 `documents.current_version`으로 단일화하므로 이 테이블에는 두지 않는다.

```text
document_id       PK, FK → documents.id
markdown          TEXT NOT NULL
content_hash      VARCHAR(64) NOT NULL
created_at        TIMESTAMPTZ NOT NULL
updated_at        TIMESTAMPTZ NOT NULL
```

편집 가능 여부는 `deleted_at == null && edit_state 존재 && (Markdown 또는 status == completed)`로 판단하고 API가 `editable`을 명시한다.

### 편집 상태 생성(lazy)

편집 상태는 Flyway로 일괄 backfill하지 않는다(본문이 MinIO에 있어 SQL로 채울 수 없음). 편집 상태가 없는 markdown·변환 완료 문서는 최초 상세 조회 또는 최초 저장 시 원본 Markdown에서 편집 상태를 lazy 생성한다(별도 트랜잭션, version `1`). Flyway backfill은 순수 DB 컬럼(`normalized_filename`, `sort_order`, `current_version=1`)만 채운다.

### 저장과 낙관적 잠금

본문 저장·rename·삭제·복구는 모두 `documents.current_version`을 단일 낙관적 잠금 버전으로 사용한다. `document_id`와 `base_version`을 조건으로 `documents`를 갱신하고, 영향받은 행이 없으면 문서 부재와 버전 충돌을 구분한다. 본문 저장은 `documents`(버전·해시·시각)와 `document_edit_states`(본문)를 같은 트랜잭션에서 갱신한다.

`current_version`은 파이프라인이 raw SQL로 쓰는 컬럼(status/processing 계열)과 겹치지 않으므로, `@DynamicUpdate` 하에서 backend가 파이프라인 컬럼을 덮어쓰지 않고 안전하게 증가시킬 수 있다.

### 정렬

`sort_order` 연속 정수를 사용한다. 이동 시 영향 범위를 트랜잭션에서 조정하고 목록 cursor는 `(sort_order, document_id)`를 opaque 값으로 인코딩한다.

### 변환 계약

파이프라인은 Markdown 본문을 callback JSON에 넣지 않고 MinIO의 `converted_markdown_uri`와 checksum을 전달한다. 백엔드는 이를 읽고 검증한 뒤 편집 상태를 생성한다. Wiki 생성 성공 여부는 편집 활성화 조건이 아니다.

### 주요 결정

- `DEC-001`: 기존 `Document`를 통합 식별자로 유지한다.
- `DEC-002`: 현재 편집 Markdown과 버전을 PostgreSQL에 저장한다.
- `DEC-003`: MinIO 업로드 원본은 불변으로 유지한다.
- `DEC-004`: 파서 결과를 최초 편집본으로 직접 사용한다.
- `DEC-005`: 파이프라인 상태와 소프트 삭제 상태를 분리한다.
- `DEC-006`: 낙관적 잠금 버전은 `documents.current_version` 하나로 통일한다. 편집 상태 유무와 무관하게 모든 문서 수준 연산(rename·본문저장·삭제·복구)이 같은 버전을 쓴다.

## 7. API

| Method | Endpoint | 역할 |
|---|---|---|
| `GET` | `/api/workspaces/{workspace_id}/documents` | 통합 목록·검색·cursor 조회 |
| `GET` | `/api/workspaces/{workspace_id}/documents/{document_id}` | 상세와 현재 편집 상태 조회 |
| `POST` | `/api/workspaces/{workspace_id}/documents/markdown` | Markdown 직접 생성 |
| `PUT` | `/api/workspaces/{workspace_id}/documents/{document_id}/content` | 전체 Markdown 저장 |
| `PATCH` | `/api/workspaces/{workspace_id}/documents/{document_id}/rename` | 표시 이름 변경 |
| `PATCH` | `/api/workspaces/{workspace_id}/documents/{document_id}/position` | 공용 순서 변경 |
| `POST` | `/api/workspaces/{workspace_id}/documents/{document_id}/duplicate` | 최신 편집본 복제 |
| `DELETE` | `/api/workspaces/{workspace_id}/documents/{document_id}` | 소프트 삭제 |
| `GET` | `/api/workspaces/{workspace_id}/documents/trash` | 삭제 문서 목록 |
| `POST` | `/api/workspaces/{workspace_id}/documents/{document_id}/restore` | 삭제 문서 복구 |
| `GET` | `/api/workspaces/{workspace_id}/documents/{document_id}/export` | Markdown 또는 bundle 내보내기 |

기존 `original`, `blocks`, pipeline callback API는 유지한다.

## 8. 검증

| 영역 | 검증 방법 | 결과 |
|---|---|---|
| DB migration과 제약 | Testcontainers Repository 통합 테스트 | Pending |
| 파일명·본문 규칙 | 도메인/서비스 단위 테스트 | Pending |
| 생성·조회·저장 API | Controller 통합 테스트 | Pending |
| 낙관적 잠금 | 동시 저장 Repository 통합 테스트 | Pending |
| 변환 결과 등록 | MinIO·callback 통합 테스트 | Pending |
| 삭제·복구 | 서비스·API 통합 테스트 | Pending |
| Markdown bundle | 스토리지 통합 테스트 | Pending |

```sh
cd backend
./gradlew test
./gradlew flywayValidate
```

## 9. 미결정 사항

- 파이프라인 재처리 결과와 사용자 편집본의 병합·교체 정책
- 정렬 중 변경된 cursor 페이지의 snapshot 보장 여부
- Unicode 파일명 정규화 수준
- 영구 삭제 보존 기간과 실행 주체
- 대용량 Markdown bundle의 동기 생성 상한

## 10. 결과

- 검증일:
- 최종 상태: Pending
- 남은 문제: 위 미결정 사항 및 후속 SDD
- 후속 작업:
  - `markdown-document-versioning.md`
  - `markdown-document-assets.md`
  - `markdown-document-sharing.md`
  - `markdown-document-ai-editing.md`
