# Markdown 페이지·원본 자료 계층

## 1. 문서 정보

- 상태: Draft
- 작성일: 2026-07-23
- 구현 계획: [`markdown-document-hierarchy-tasks.md`](./tasks/markdown-document-hierarchy-tasks.md)
- 선행 SDD: [`markdown-document-core.md`](./markdown-document-core.md)
- 목표 ERD: [`markdown-document-erd.md`](./markdown-document-erd.md)
- 관련 PR:

상태 흐름: `Draft → Approved → In Progress → Verified`

## 2. 배경

사용자는 Markdown을 Notion의 페이지처럼 계층화하고, PDF·DOCX·PPTX 등 불변 원본은 폴더로 정리하려 한다. 두 자료는 수명주기와 조작 방식이 다르므로 UI 탐색 영역을 `페이지`와 `원본 자료`로 분리한다.

기존 `documents` 통합 식별자는 유지한다. Markdown과 원본을 별도 테이블로 분리하지 않고, Markdown은 부모 문서 관계를, 원본은 원본 폴더 관계를 사용한다.

## 3. 목표

- Markdown 페이지가 본문과 하위 페이지를 함께 가질 수 있는 Notion식 계층을 제공한다.
- 업로드 원본을 별도의 폴더 트리에서 정리한다.
- 페이지와 원본 자료의 이동·정렬·삭제·복구 규칙을 명확히 한다.
- 지연 조회와 breadcrumb로 깊은 계층을 탐색한다.
- 순환 관계, 오래된 이동 요청, 요청 재전송을 안전하게 처리한다.

## 4. 범위

### 포함

- Markdown 페이지 부모·자식 계층
- 원본 자료 폴더와 하위 폴더
- 페이지·폴더·원본의 생성 위치, 이동, 순서 변경
- 두 탐색 영역의 지연 조회
- breadcrumb와 파일명 검색
- 계층 단위 소프트 삭제·복구
- 계층 쓰기 API의 낙관적 잠금과 멱등성

### 제외

- 두 탐색 영역 사이의 드래그 이동
- `.md` 파일을 페이지 위에 드롭하여 업로드하는 기능
- 실시간 공동편집
- 개인별 정렬
- 원본 폴더 계층을 Markdown 페이지 계층으로 자동 복제
- 페이지 링크·backlink·database
- 계층 cursor 페이지네이션

## 5. 용어

| 용어 | 정의 |
|---|---|
| 페이지 | 편집 가능한 Markdown 문서. 본문과 하위 페이지를 함께 가질 수 있다. |
| 원본 | PDF·DOCX·PPTX 등 변환 전 불변 파일 |
| 원본 폴더 | 원본만 포함하는 파일 탐색기식 컨테이너 |
| 형제 항목 | 같은 부모를 가진 페이지 또는 같은 폴더를 가진 원본 자료 항목 |
| 최상위 | 부모 페이지 또는 부모 폴더가 없는 위치 |

## 6. 요구사항

### 6.1 탐색 영역

#### REQ-H001 영역 분리

- 탐색 UI와 API는 `페이지`와 `원본 자료` 영역을 구분한다.
- `페이지`에는 직접 생성 Markdown, 업로드한 `.md`, 원본에서 변환된 Markdown 편집본을 표시한다.
- `원본 자료`에는 PDF·DOCX·PPTX 등 변환 전 원본과 원본 폴더를 표시한다.
- 페이지를 원본 자료 영역으로, 원본이나 원본 폴더를 페이지 영역으로 이동할 수 없다.

#### REQ-H002 Markdown 업로드

- 업로드한 `.md`는 불변 원본을 별도로 노출하거나 보관하지 않고 즉시 편집 가능한 페이지로 생성한다.
- 업로드 요청에서 생성할 부모 페이지를 선택할 수 있다.
- 부모를 선택하지 않으면 페이지 최상위의 가장 뒤에 생성한다.
- `.md` 파일을 기존 페이지 위로 끌어 놓아 업로드하는 기능은 제공하지 않는다.

#### REQ-H003 변환 편집본 위치

- 원본 업로드 시 변환 편집본의 생성 위치를 선택할 수 있다.
- 위치를 선택하지 않으면 페이지 최상위의 가장 뒤에 생성한다.
- 원본 폴더 계층을 페이지 계층으로 자동 복제하지 않는다.
- 원본과 편집본은 계층이 아닌 기존 원본 참조 관계로 연결한다.

### 6.2 페이지 계층

#### REQ-H004 하위 페이지

- 페이지는 제한 없는 깊이로 다른 페이지를 포함할 수 있다.
- 각 페이지는 최대 하나의 `parent_document_id`를 가진다.
- 최상위 페이지의 `parent_document_id`는 `null`이다.
- 같은 부모 아래에 동일한 제목과 동일한 본문의 페이지를 허용한다.
- 페이지는 제목이 아니라 `document_id`로 식별한다.

#### REQ-H005 페이지 생성 위치와 순서

- 직접 생성·업로드·복제·AI 생성 시 부모 페이지를 선택할 수 있다.
- 새 페이지는 선택한 부모의 자식 목록 가장 뒤에 추가한다.
- 부모가 없으면 최상위 목록 가장 뒤에 추가한다.
- AI 생성 위치의 상세 규칙은 [`markdown-document-ai-editing.md`](./markdown-document-ai-editing.md)를 따른다.

#### REQ-H006 페이지 이동

- 모든 워크스페이스 멤버는 페이지를 다른 페이지의 하위 또는 최상위로 이동할 수 있다.
- 이동 요청은 대상 부모, 대상 형제 위치, 페이지의 `base_version`, `Idempotency-Key`를 포함한다.
- 자기 자신 또는 자신의 하위 페이지 아래로 이동할 수 없다.
- 페이지 이동은 본문을 변경하지 않고 `current_version`을 증가시킨다.
- 이동할 페이지와 대상 부모 페이지는 같은 workspace에 속하고 요청자에게 읽기 가능해야 한다.

#### REQ-H007 페이지 정렬

- 같은 부모 아래의 페이지 순서는 워크스페이스 전체에 공유한다.
- 드래그 결과는 대상 형제 앞 또는 뒤로 표현한다.
- 검색 결과에서는 이동과 정렬을 수행할 수 없다.
- 펼침·접힘 상태는 사용자 UI 상태이며 공용 정렬을 변경하지 않는다.

### 6.3 원본 자료 계층

#### REQ-H008 원본 폴더

- 모든 워크스페이스 멤버는 원본 자료 최상위 또는 다른 원본 폴더 아래에 폴더를 생성할 수 있다.
- 원본 폴더는 제한 없는 깊이로 중첩할 수 있다.
- 동일한 부모 아래에서도 동일한 폴더 이름을 허용한다.
- 폴더와 원본은 ID와 breadcrumb로 구분한다.
- 같은 생성 요청의 재실행만 `Idempotency-Key`로 방지한다.

#### REQ-H009 원본 이동과 정렬

- 모든 워크스페이스 멤버는 원본을 다른 원본 폴더 또는 최상위로 이동할 수 있다.
- 모든 워크스페이스 멤버는 원본 폴더를 다른 원본 폴더 또는 최상위로 이동할 수 있다.
- 폴더를 자기 자신이나 자신의 하위 폴더로 이동할 수 없다.
- 원본은 자식 항목을 가질 수 없다.
- 폴더와 원본은 같은 부모 안에서 하나의 혼합 순서로 정렬한다.
- 이동 요청은 대상 부모, 대상 형제 위치, `Idempotency-Key`를 포함한다.

### 6.4 조회와 검색

#### REQ-H010 지연 조회

- 최초 조회는 두 영역의 최상위 항목만 반환한다.
- 페이지나 원본 폴더를 펼칠 때 해당 항목의 직계 자식만 조회한다.
- 각 항목은 `has_children`을 포함한다.
- 페이지 자식 응답에는 페이지만 포함한다.
- 원본 폴더 자식 응답에는 폴더와 원본을 공용 순서로 섞어 반환한다.

#### REQ-H011 breadcrumb

- 페이지 상세과 원본 상세는 최상위부터 현재 항목까지의 breadcrumb를 반환한다.
- 검색 결과에도 영역, 항목 종류, breadcrumb를 포함한다.
- 동일한 이름의 결과는 breadcrumb와 ID로 구분한다.

#### REQ-H012 검색

- 검색은 페이지 제목, 원본 파일명, 원본 폴더 이름을 대상으로 한다.
- 본문은 검색하지 않는다.
- 검색 결과는 평면 결과로 반환하고 계층 이동 기능을 제공하지 않는다.
- 소프트 삭제 항목과 그 하위 항목은 일반 검색에서 제외한다.

### 6.5 삭제와 복구

#### REQ-H013 페이지 트리 삭제

- 부모 페이지를 삭제하면 모든 하위 페이지를 같은 삭제 작업 ID로 소프트 삭제한다.
- 삭제 전 전체 트리의 부모와 순서 정보를 보관한다.
- 트리 복구는 원래 부모와 순서를 복원한다.
- 하위 페이지를 개별 복구하면 페이지 최상위의 가장 뒤에 배치한다.
- 복구 대상의 원래 부모가 삭제 상태이면 해당 부모 아래로 자동 복구하지 않는다.

#### REQ-H014 원본 폴더 트리 삭제

- 원본 폴더를 삭제하면 모든 하위 폴더와 원본을 같은 삭제 작업 ID로 소프트 삭제한다.
- 트리 복구는 원래 부모와 순서를 복원한다.
- 하위 폴더나 원본을 개별 복구하면 원본 자료 최상위의 가장 뒤에 배치한다.
- 원본 삭제는 연결된 Markdown 편집본을 삭제하지 않는다.

#### REQ-H015 원본·편집본 독립 수명주기

- 변환 편집본을 삭제해도 원본은 유지한다.
- 원본을 삭제해도 변환 편집본은 유지한다.
- 휴지통에서는 삭제된 상대 항목과의 연결을 표시할 수 있다.
- 복구하면 기존 연결 관계를 다시 조회할 수 있다.
- 어느 한쪽을 영구 삭제할 때만 해당 연결을 제거한다.

### 6.6 권한과 일관성

#### REQ-H016 권한

- 문서 생성자는 해당 문서의 소유자가 되며 문서 내용 CRUD는 소유자만 수행한다.
- 모든 워크스페이스 멤버는 읽기 가능한 문서와 원본을 이동하고 같은 부모 안에서 순서를 변경할 수 있다.
- 모든 워크스페이스 멤버는 원본 폴더를 생성·이름 변경·이동할 수 있다.
- 모든 워크스페이스 멤버는 빈 원본 폴더를 삭제할 수 있다.
- 내용이 있는 원본 폴더는 워크스페이스 소유자만 삭제할 수 있다.
- 원본 파일은 해당 문서 소유자만 삭제·복구할 수 있다.
- 이동과 정렬은 문서 본문·소유자를 변경하지 않는다.
- Spring backend가 workspace 멤버십, 항목 읽기 권한, 문서 소유권을 작업별로 검증한다.

#### REQ-H017 낙관적 잠금과 멱등성

- 페이지 이동·삭제·복구는 `documents.current_version`으로 충돌을 검사한다.
- 폴더는 별도의 `current_version`으로 이동·삭제·복구 충돌을 검사한다.
- 생성·이동·정렬·삭제·복구 요청은 `Idempotency-Key`를 사용한다.
- 같은 사용자·endpoint·키의 24시간 내 재요청은 최초 결과를 반환한다.
- 오래된 버전의 이동·삭제·복구는 `409 Conflict`를 반환한다.

## 7. 설계

### 7.1 데이터 모델

기존 `documents`에 다음 필드를 추가한다.

| 필드 | 설명 |
|---|---|
| `parent_document_id` | Markdown 페이지의 부모 페이지. 최상위면 `null` |
| `source_folder_id` | 업로드 원본의 원본 폴더. 최상위면 `null` |
| `sort_order` | 현재 부모 범위 안의 공용 순서 |
| `document_role` | 문서 역할. 편집 가능한 Markdown은 `EDITABLE`, 불변 원본은 `ORIGINAL` |
| `delete_operation_id` | 트리 삭제·복구 작업 식별자 |

`origin`은 문서의 생성 경로를 나타내고 `document_role`은 탐색과 수명주기에서의 역할을 나타낸다. 같은 `origin=upload`이어도 업로드 Markdown은 `EDITABLE`, 업로드 PDF는 `ORIGINAL`이다.

`parent_document_id`와 `source_folder_id`는 동시에 값을 가질 수 없다. `EDITABLE`은 `parent_document_id`만 사용하고 `ORIGINAL`은 `source_folder_id`만 사용한다. 역할에 맞지 않는 부모 사용은 DB check constraint로 차단한다.

기존 Markdown은 `EDITABLE`, 나머지 업로드 원본은 `ORIGINAL`로 backfill한다. 기존 문서는 각 역할 영역의 최상위에 두고, workspace와 역할별 `uploaded_at`, `id` 순서로 `sort_order`를 부여한다.

`source_folders`를 추가한다.

```text
id                 UUID PK
workspace_id       UUID NOT NULL
parent_folder_id   UUID NULL
name               VARCHAR(255) NOT NULL
sort_order         BIGINT NOT NULL
current_version    BIGINT NOT NULL DEFAULT 1
deleted_at         TIMESTAMPTZ NULL
deleted_by         UUID NULL
delete_operation_id UUID NULL
created_at         TIMESTAMPTZ NOT NULL
updated_at         TIMESTAMPTZ NOT NULL
```

이름에는 unique 제약을 두지 않는다. 부모 FK는 같은 workspace에 속하는지 서비스와 repository 쿼리에서 검증한다.

### 7.2 순환 방지

이동 트랜잭션은 recursive CTE로 대상 부모의 조상 경로를 조회한다. 이동 항목 자신이 조상 경로에 있으면 `409 HIERARCHY_CYCLE`로 거절한다. 프론트엔드의 드롭 차단은 보조 검증이며 서버 검증을 대체하지 않는다.

### 7.3 정렬

정렬은 부모 범위별 `sort_order`로 관리한다. 이동 시 영향받는 출발·도착 부모 범위만 트랜잭션에서 재정렬한다. 페이지와 원본 자료는 서로 다른 정렬 범위를 사용한다.

### 7.4 삭제 작업

트리 삭제마다 `delete_operation_id`를 발급한다. 전체 복구는 같은 작업 ID에 포함된 항목을 복구하고, 개별 복구는 선택 항목의 활성 하위 트리만 최상위로 복구한다. 영구 삭제 정책은 core 후속 SDD에서 정의한다.

## 8. API

| Method | Endpoint | 역할 |
|---|---|---|
| `GET` | `/api/workspaces/{workspace_id}/navigation` | 두 영역의 최상위 항목 조회 |
| `GET` | `/api/workspaces/{workspace_id}/pages/{page_id}/children` | 페이지의 직계 하위 페이지 조회 |
| `PATCH` | `/api/workspaces/{workspace_id}/pages/{page_id}/position` | 페이지 부모·순서 변경 |
| `POST` | `/api/workspaces/{workspace_id}/source-folders` | 원본 폴더 생성 |
| `GET` | `/api/workspaces/{workspace_id}/source-folders/{folder_id}/children` | 직계 폴더·원본 조회 |
| `PATCH` | `/api/workspaces/{workspace_id}/source-folders/{folder_id}` | 원본 폴더 이름 변경 |
| `PATCH` | `/api/workspaces/{workspace_id}/source-folders/{folder_id}/position` | 원본 폴더 이동·정렬 |
| `PATCH` | `/api/workspaces/{workspace_id}/source-files/{document_id}/position` | 원본 이동·정렬 |
| `DELETE` | `/api/workspaces/{workspace_id}/source-folders/{folder_id}` | 원본 폴더 트리 소프트 삭제 |
| `POST` | `/api/workspaces/{workspace_id}/source-folders/{folder_id}/restore` | 원본 폴더 트리 복구 |
| `GET` | `/api/workspaces/{workspace_id}/navigation/search` | 두 영역 이름 검색 |

페이지 생성·업로드·복제·삭제·복구 API는 core 계약을 유지하고 선택적 `parent_document_id`를 받는다. 원본 업로드 API는 선택적 `source_folder_id`와 `converted_page_parent_id`를 받는다.

## 9. 오류

| HTTP | 코드 | 조건 |
|---:|---|---|
| 400 | `INVALID_HIERARCHY_REQUEST` | 잘못된 부모·형제 위치 |
| 403 | `HIERARCHY_WRITE_FORBIDDEN` | 멤버가 아닌 사용자 또는 권한 없는 내용 수정·삭제 |
| 404 | `HIERARCHY_ITEM_NOT_FOUND` | 다른 workspace 또는 없는 항목 |
| 409 | `HIERARCHY_VERSION_CONFLICT` | 오래된 버전 |
| 409 | `HIERARCHY_CYCLE` | 자기 자신·하위 항목으로 이동 |
| 409 | `HIERARCHY_ITEM_TYPE_MISMATCH` | 두 영역 사이 이동 |

## 10. 인수 조건

- 페이지를 다른 페이지 아래로 이동하면 새 부모의 마지막 위치에서 조회된다.
- 깊은 페이지를 자신의 하위 페이지 아래로 이동하려 하면 전체 상태가 유지되고 `409`가 반환된다.
- 원본 폴더 아래에서 폴더와 원본이 하나의 순서로 조회된다.
- 동일 이름 페이지와 폴더를 같은 부모 아래에 각각 생성할 수 있다.
- 부모 페이지 삭제·복구 시 전체 하위 트리와 순서가 복원된다.
- 원본 폴더 삭제가 연결된 Markdown 편집본을 삭제하지 않는다.
- 워크스페이스 멤버는 다른 멤버 소유 문서를 이동할 수 있지만 본문·이름·소유자는 변경할 수 없다.
- 내용이 있는 원본 폴더를 일반 멤버가 삭제하면 `403`이다.
- 같은 멱등성 키의 이동 재요청이 버전이나 순서를 두 번 변경하지 않는다.

## 11. 미결정 사항

- 계층 cursor 페이지네이션과 한 폴더의 최대 직계 자식 수
- 영구 삭제 보존 기간과 실행 주체

## 12. 결과

- 검증일:
- 최종 상태: Pending
- 남은 문제: 위 미결정 사항
