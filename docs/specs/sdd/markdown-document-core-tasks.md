# Markdown 문서 편집 Core 작업 계획

## 1. 문서 정보

- 상태: Draft
- 작성일: 2026-07-23
- 기능 SDD: [`markdown-document-core.md`](./markdown-document-core.md)

## 2. 실행 원칙

- 각 TASK의 실패 테스트 또는 재현 절차를 구현보다 먼저 작성한다.
- 각 완료 조건을 통과한 뒤 다음 TASK로 이동한다.
- 기존 업로드·파이프라인·Wiki 동작의 회귀를 함께 검증한다.
- 요청과 무관한 리팩터링은 포함하지 않는다.

## 3. 작업 계획

### TASK-001 편집 문서 데이터 모델 추가

- 관련 요구사항: `REQ-001`~`REQ-004`, `REQ-005`~`REQ-008`
- 변경 대상:
  - `backend/src/main/resources/db/migration/V8__add_document_editing.sql`
  - `document/domain/Document.java`
  - 신규 `DocumentEditState`, repository
- 작업:
  - `documents`에 정규화 파일명, 원본 참조, 현재 해시, `current_version`, 정렬, 수정·삭제 필드 추가
  - `source_uri`, 원본 `content_hash` nullable 전환
  - V5 제약 `uq_documents_workspace_content_hash` DROP
  - `(workspace_id, normalized_filename, current_content_hash) WHERE deleted_at IS NULL` partial unique index 신설
  - `Document.java`의 `@UniqueConstraint(uq_documents_workspace_content_hash)` 제거(ddl-auto=validate 정합성)
  - `document_edit_states`(버전 없이 본문·해시·시각) 추가
  - Flyway backfill: `normalized_filename`, `sort_order`, `current_version=1`, 기존 문서의 `current_content_hash=content_hash` (편집 상태는 최초 조회·저장 시 lazy 생성)
- 완료 조건:
  - [ ] Flyway migration 검증 통과
  - [ ] 같은 내용·다른 파일명 문서 2건 생성 허용, 파일명·내용 동일 시 409
  - [ ] 소프트 삭제 문서는 partial index에서 제외되어 동일 조합 재생성 허용
  - [ ] 기존 데이터 손실 없음
  - [ ] 기존 markdown 업로드 문서가 마이그레이션 후 최초 조회 시 편집 가능해짐(lazy edit_state)
  - [ ] 기존 문서와 파일명·내용이 같은 신규 요청을 partial unique index가 차단함
  - [ ] 편집 상태 1:1과 self-reference 제약 테스트 통과
  - [ ] 기존 업로드·조회 테스트 통과

### TASK-002 파일명·본문 규칙 구현

- 관련 요구사항: `REQ-004`, `REQ-012`, `REQ-014`
- 변경 대상: `DocumentService`의 단일 책임 함수 또는 재사용이 확인될 때만 별도 value object
- 작업:
  - 표시 이름·확장자 정규화
  - 금지 문자·255자 검증
  - UTF-8 5MB 검증
  - SHA-256과 동일 본문 판정
- 완료 조건:
  - [ ] PDF·Markdown 확장자 유지 테스트 통과
  - [ ] 한글 UTF-8 경계값 테스트 통과
  - [ ] 빈 본문 허용, `null` 거절 테스트 통과
  - [ ] 파일명+내용 중복 테스트 통과

### TASK-003 통합 목록·상세·검색·정렬 구현

- 관련 요구사항: `REQ-005`~`REQ-008`
- 변경 대상: `DocumentController`, `DocumentService`, `DocumentRepository`, 목록·상세 DTO
- 작업:
  - 기존 목록 응답에 표시 이름, 형식, 편집 가능 여부, 버전 추가
  - 삭제 문서 제외와 변환 상태 표시
  - 파일명 검색(전체 목록 반환, cursor 페이지네이션은 후속 SDD)
  - 워크스페이스 공용 위치 변경 API
- 완료 조건:
  - [ ] 업로드와 Markdown 문서가 한 목록에 표시됨
  - [ ] 모든 멤버에게 같은 수동 순서가 반환됨
  - [ ] 검색이 본문을 대상으로 하지 않음
  - [ ] 외부 워크스페이스와 삭제 문서가 노출되지 않음
  - [ ] chat_export 문서가 통합 목록에 노출되지 않음(회귀 검증)

### TASK-004 Markdown 직접 생성·업로드 구현

- 관련 요구사항: `REQ-001`, `REQ-002`, `REQ-004`
- 변경 대상: `DocumentController`, `DocumentService`, 생성 DTO, 업로드 처리
- 작업:
  - `POST /documents/markdown`
  - 직접 생성 문서와 version `1` 편집 상태 저장
  - Markdown 업로드 원문으로 편집 상태 즉시 생성
  - 생성 중복 검사와 목록 마지막 배치
  - `DocumentService.createInitialNote`를 직접 생성 경로로 재배선(워크스페이스 생성 호출부 포함)
- 완료 조건:
  - [ ] 빈 Markdown 직접 생성 가능
  - [ ] 5MB 초과 요청 거절
  - [ ] Markdown 업로드 직후 `editable=true`
  - [ ] 직접 생성 문서의 `source_uri`가 `null`
  - [ ] 신규 워크스페이스 초기 노트가 직접 생성 문서(source_uri=null)로 만들어짐
  - [ ] DB 또는 MinIO 실패 시 불완전한 상태가 남지 않음

### TASK-005 PDF 변환 결과 편집본 등록

- 관련 요구사항: `REQ-003`
- 변경 대상: pipeline callback DTO·controller·service와 변환 결과 계약
- 작업:
  - `converted_markdown_uri`, checksum 계약 추가
  - `X-Pipeline-Token`, `run_id`, bucket·document prefix 검증과 멱등 처리
  - 변환 Markdown 조회·checksum·5MB 검증
  - 같은 문서 ID에 최초 편집 상태 생성
  - Wiki 생성과 편집 활성화 분리
- 완료 조건:
  - [ ] 처리 중에는 목록에 표시되지만 편집 불가
  - [ ] 완료 후 새 목록 항목 없이 편집 가능
  - [ ] checksum 불일치·크기 초과 시 편집 상태 미생성
  - [ ] 인증 실패·run 불일치·허용 prefix 밖 URI 요청 거절
  - [ ] 원본 PDF 불변

### TASK-006 본문 저장·이름 변경 구현

- 관련 요구사항: `REQ-009`~`REQ-014`
- 변경 대상: `DocumentController`, `DocumentService`, 저장·이름 DTO, 충돌 예외
- 작업:
  - 저장 버튼·`Cmd/Ctrl+S` 기반 multipart 전체 Markdown 수동 저장
  - 신규 이미지 attachment 처리는 assets SDD와 연동
  - `base_version` 조건부 갱신
  - 동일 본문 no-op
  - 형식별 확장자를 유지하는 이름 변경
- 완료 조건:
  - [ ] 정상 변경 시 버전 1 증가
  - [ ] 동일 본문·이름은 버전과 시각 유지
  - [ ] 오래된 버전은 `409 Conflict`
  - [ ] 저장·이름 변경 unique 제약 위반은 `409 Conflict`
  - [ ] 본문 heading과 원본 파일 불변

### TASK-007 최신 편집본 복제 구현

- 관련 요구사항: `REQ-015`, `REQ-016`
- 변경 대상: `DocumentController`, `DocumentService`, 복제 DTO
- 작업:
  - 최신 Markdown으로 새 `Document`와 편집 상태 생성
  - `복사본 (N)` 이름 선택
  - 원본 참조와 목록 마지막 배치
- 완료 조건:
  - [ ] PDF 복제본이 `.md` 문서임
  - [ ] 새 ID와 version `1`
  - [ ] 원본 파일·이력·공유 설정 미복제
  - [ ] 동시 복제 시 이름 제약 위반 없음

### TASK-008 소프트 삭제·휴지통·복구 구현

- 관련 요구사항: `REQ-017`, `REQ-018`
- 변경 대상: `DocumentController`, `DocumentService`, `DocumentRepository`, 삭제·복구 DTO
- 작업:
  - 기존 사용자 삭제를 소프트 삭제로 변경
  - 휴지통과 복구 API
  - 복구 중복 검사와 마지막 순서 배치
  - 워크스페이스 전체 삭제용 내부 물리 삭제는 유지
- 완료 조건:
  - [ ] 삭제 문서가 일반 API에서 제외됨
  - [ ] 원본·본문·버전·이미지 유지
  - [ ] 복구 후 기존 본문 유지
  - [ ] 충돌 문서가 있으면 복구 거절

### TASK-009 Markdown 원문 내보내기

- 관련 요구사항: `REQ-019`
- 변경 대상: `DocumentController`, 신규 `DocumentExportService`
- 작업:
  - 이미지 없는 문서의 UTF-8 `.md` 스트리밍
  - 이미지 bundle은 assets SDD TASK-008로 위임
- 완료 조건:
  - [ ] UTF-8 Markdown과 한글 파일명 다운로드
  - [ ] 내보내기가 문서를 변경하지 않음

### TASK-010 API 계약·회귀 검증

- 관련 요구사항: 전체 core 요구사항
- 변경 대상: `docs/spec/api/document.md`, OpenAPI annotation, API 통합 테스트
- 작업:
  - API·오류 계약 갱신
  - 요구사항–인수 조건–테스트 추적표 완성
  - 업로드·파이프라인·Wiki 회귀 테스트
- 완료 조건:
  - [ ] 모든 요구사항이 테스트와 연결됨
  - [ ] API 문서와 DTO 일치
  - [ ] 전체 백엔드 테스트 통과
  - [ ] `git diff --check` 통과

## 4. 실행 순서

```text
TASK-001 → TASK-002 → TASK-003 → TASK-004
                            └──→ TASK-005
TASK-004/005 → TASK-006 → TASK-007 → TASK-008 → TASK-009
전체 완료 → TASK-010
```

신규 이미지를 포함한 multipart 저장과 ZIP 내보내기는 [`markdown-document-assets-tasks.md`](./markdown-document-assets-tasks.md)의 선행 TASK를 완료한 뒤 통합한다.

## 5. 검증 명령

```sh
cd backend
./gradlew test
./gradlew flywayValidate
```

Repository 통합 테스트는 기존 Testcontainers 구성을 사용한다.

## 6. 후속 SDD

- `markdown-document-pagination.md`: 목록 cursor 페이지네이션, 응답 형태 전환, 프론트 목록 소비 재배선
- 범용 버전 이력은 보류하고 AI 전·후 snapshot은 AI editing SDD에서 정의
- [`markdown-document-assets.md`](./markdown-document-assets.md): 이미지 attachment, 권한, 참조 수명주기
- `markdown-document-sharing.md`: 공유 링크, 만료, 해제
- `markdown-document-ai-editing.md`: AI 편집, Markdown 검증, diff·적용
