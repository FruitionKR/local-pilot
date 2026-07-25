# Markdown 페이지·원본 자료 계층 작업 계획

## 1. 문서 정보

- 상태: Draft
- 작성일: 2026-07-23
- 기능 SDD: [`markdown-document-hierarchy.md`](../markdown-document-hierarchy.md)

## 2. 실행 원칙

- 각 TASK의 실패 테스트를 구현보다 먼저 작성한다.
- 페이지 계층과 원본 폴더 계층의 규칙을 섞지 않는다.
- 기존 `documents`와 업로드·변환 계약을 유지한다.
- 각 완료 조건을 통과한 뒤 다음 TASK로 이동한다.

## 3. 작업 계획

### TASK-H001 계층 데이터 모델과 migration

- 관련 요구사항: `REQ-H001`~`REQ-H009`, `REQ-H017`
- 작업:
  - core TASK-001에서 추가한 `document_role`, 두 부모 필드, `sort_order`, `source_folders` schema 재검증
  - `document_role=EDITABLE`과 `ORIGINAL`별 repository 조회 기반 추가
  - 기존 Markdown의 `EDITABLE`, 나머지 업로드 원본의 `ORIGINAL` backfill 결과 검증
  - 부모 범위별 `sort_order` 조회·잠금 쿼리 추가
  - 역할과 부모 필드 check constraint에 맞춘 서비스 검증 추가
- 완료 조건:
  - [ ] core migration·entity mapping 검증 통과
  - [ ] 기존 문서의 탐색 영역 backfill 검증
  - [ ] 동일 이름과 동일 내용에 unique 제약이 없음
  - [ ] `EDITABLE`과 `ORIGINAL` 최상위 항목을 `document_role`로 구분
  - [ ] 다른 workspace 부모 지정 거절

### TASK-H002 페이지 계층 repository·서비스

- 관련 요구사항: `REQ-H004`~`REQ-H007`, `REQ-H017`
- 작업:
  - 직계 자식 조회와 `has_children`
  - 부모 변경·형제 정렬
  - recursive CTE 순환 검사
  - `current_version`과 멱등성 처리
- 완료 조건:
  - [ ] 최상위↔하위 이동 테스트
  - [ ] 깊이 제한 없는 30단계 계층 조회 테스트
  - [ ] 자기 자신·자손 아래 이동 `409`
  - [ ] 같은 키 재요청 no-op
  - [ ] 오래된 버전 이동 `409`

### TASK-H003 원본 폴더 repository·서비스

- 관련 요구사항: `REQ-H008`, `REQ-H009`, `REQ-H017`
- 작업:
  - 원본 폴더 생성·이름 변경
  - 폴더·원본 혼합 직계 자식 조회
  - 폴더·원본 이동과 정렬
  - 폴더 순환 검사
- 완료 조건:
  - [ ] 같은 이름 폴더 생성 허용
  - [ ] 폴더와 원본의 혼합 순서 유지
  - [ ] 원본이 자식을 갖거나 페이지 영역으로 이동할 수 없음
  - [ ] 폴더 순환 이동 `409`

### TASK-H004 생성·업로드·변환 위치 연동

- 관련 요구사항: `REQ-H002`, `REQ-H003`, `REQ-H005`
- 작업:
  - Markdown 생성·업로드·복제에 `parent_document_id` 추가
  - 원본 업로드에 `source_folder_id`, `converted_page_parent_id` 추가
  - `.md` 업로드를 `document_role=EDITABLE`로 직접 생성
  - 변환 편집본을 선택한 페이지 위치 또는 최상위에 생성
- 완료 조건:
  - [ ] `.md` 업로드가 원본 자료에 중복 표시되지 않음
  - [ ] 선택한 부모의 마지막에 페이지 생성
  - [ ] 변환 원본과 편집본의 참조 유지
  - [ ] 원본 폴더가 페이지 계층으로 복제되지 않음

### TASK-H005 탐색·breadcrumb·검색 API

- 관련 요구사항: `REQ-H010`~`REQ-H012`
- 작업:
  - 두 영역 최상위 탐색 API
  - 직계 자식 지연 조회 API
  - breadcrumb와 평면 검색 결과
- 완료 조건:
  - [ ] 최초 응답에 하위 트리 본문이 포함되지 않음
  - [ ] 동일 제목 결과가 breadcrumb로 구분됨
  - [ ] 본문 검색 제외
  - [ ] 삭제 트리 검색 제외

### TASK-H006 계층 삭제·복구

- 관련 요구사항: `REQ-H013`~`REQ-H015`
- 작업:
  - `delete_operation_id` 기반 트리 소프트 삭제
  - 전체·개별 복구와 원래 위치 복원
  - 원본·편집본 독립 삭제
- 완료 조건:
  - [ ] 페이지 트리 전체 삭제·복구
  - [ ] 원본 폴더 트리 전체 삭제·복구
  - [ ] 개별 복구가 최상위 마지막에 배치됨
  - [ ] 원본 삭제가 편집본을 삭제하지 않음

### TASK-H007 frontend 탐색 트리

- 관련 요구사항: `REQ-H001`, `REQ-H006`~`REQ-H012`
- 작업:
  - `페이지`·`원본 자료` 영역 분리
  - 펼침 시 직계 자식 조회
  - 드래그 preview와 서버 이동 요청
  - breadcrumb·검색 결과 표시
- 완료 조건:
  - [ ] 두 영역 사이 드롭 차단
  - [ ] 페이지·폴더 순환 대상 드롭 차단
  - [ ] 서버 실패 시 기존 UI 순서 복구
  - [ ] 모든 멤버에게 이동 UI 제공, 문서 내용 CRUD는 소유자에게만 노출
  - [ ] 내용 있는 원본 폴더 삭제는 워크스페이스 소유자에게만 노출

### TASK-H008 계약·회귀 검증

- 관련 요구사항: 전체 계층 요구사항
- 작업:
  - Core TASK-010에서 이관된 비소유 workspace 멤버의 페이지·원본 이동 허용 검증
  - API 문서와 오류 계약 갱신
  - Testcontainers 동시 이동·삭제 테스트
  - 기존 업로드·변환·편집 회귀 테스트
- 완료 조건:
  - [ ] 요구사항–테스트 추적 완료
  - [ ] 전체 backend·frontend 테스트 통과
  - [ ] `git diff --check` 통과

## 4. 실행 순서

```text
TASK-H001 → TASK-H002 ─┬→ TASK-H004 → TASK-H005 → TASK-H007
                       ├→ TASK-H006
            TASK-H003 ─┘
전체 완료 → TASK-H008
```

## 5. 검증 명령

```sh
cd backend
./gradlew test
./gradlew flywayValidate

cd ../frontend
npm test
```
