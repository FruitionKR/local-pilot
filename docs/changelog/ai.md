# AI/Pipeline 변경 기록

## 2026-08-09

### feat: Query·Agent 멀티턴 context 계약 추가

- Query와 Agent turn이 누적 요약과 최근 원문 메시지 최대 6개를 함께 사용하도록 `recent_messages` 계약을 추가했다.
- 최근 메시지 6개가 쌓이면 기존 요약과 합친 `updated_conversation_summary`를 반환하고 Query 경로에서는 같은 메시지를 중복 요약하지 않도록 했다.
- 대화 요약 LLM 호출이 실패하거나 빈 응답을 반환해도 본 요청과 이미 시작된 AgentRun은 정상 결과를 유지하도록 했다.
- 대화 요약기가 provider별 Query LLM `temperature` 설정을 덮어쓰지 않도록 했다.
- 이전 assistant 메시지를 사용자 Skill 생성 의도로 인정하지 않도록 Agent route guard를 제한했다.
- Backend의 context 저장·전달 후속 작업과 Skill 작성·실행 계약 재정렬을 `docs/issue/backend/2026-08-09.md`에 기록했다.
- llmPipeline 전체 테스트 `845 passed, 63 subtests passed`로 검증했다.

## 2026-08-08

### fix: Skill 개인정보 게시 차단 보강

- Skill 입력·설명·참조 원문·생성 결과·최종 게시에서 이메일, 전화번호, 검증된 주민등록번호·카드번호, 계좌번호와 이름·주소 필드값을 차단한다.
- JWT·Bearer를 포함한 여러 credential을 첫 항목에서 멈추지 않고 모두 반환하며, 비정형 개인정보·기밀정보는 의미 검사가 보완한다.
- 빈 필드·밑줄 템플릿·표 header는 허용하고 실제 값과 명시적 개인정보 placeholder만 차단하며, llmPipeline 전체 테스트 `825 passed, 61 subtests passed`로 검증했다.
### feat: 문서 선택 복원 lane과 fallback 추가

- 같은 페이지의 손상 본문과 표·수식을 별도 lane으로 묶고 그림은 원본 asset으로 유지하도록 선택 복원 경계를 정리했다.
- 기존 180초 묶음 timeout과 연결·JSON·ID 실패를 단건 fallback에 연결하고, Markdown 검증 탈락 항목만 재시도하며 단건 실패는 원문 유지와 summary `failed`로 기록하도록 추가했다.
- AnyDoc crop-first 25개 PDF·415페이지 실험과 기존 L1~L106 파이프라인 비교를 `docs/backlog/evaluation/anydoc-crop-first-pipeline-report.md`에 기록했다.
- 같은 4개 PDF·30페이지·445블록을 후보명과 A/B 위치를 숨겨 다시 평가해 제품 Docling 45.17% 대비 L107 89.89%의 실사용 정확도와 +44.72%p paired 개선을 확인했다.
- 기본 문서 복원 mode를 L107 기반 `crop-first`로 바꾸고 Rust Heron crop, AnyDoc 본문, 30% 본문 Luna route, 페이지 묶음 표·수식 복구와 원본 그림 조립을 제품 stage에 연결했다. `docling-only`는 명시적 fallback으로 유지했다.
- 문서 복원 모듈 테스트 `67 passed`, llmPipeline 전체 `820 passed, 61 subtests passed`로 lane 분리와 fallback 회귀를 확인했고, 원본 4페이지 smoke에서 표 2·수식 7·그림 4, marker 누락 0을 확인했다.
- 리뷰 후 본문 Luna 입력을 redacted page와 AnyDoc 초안 각 1개로 제한하고 fenced code block을 허용했으며, `pypdfium2` 기반 PDFium 탐색과 Rust 1.88 clean build·실제 8페이지 Heron 실행을 검증했다. 전체 테스트는 `821 passed, 61 subtests passed`다.
- 추가 리뷰에서 AI 복원 실패 시 AnyDoc 원문을 보존하되 token이 중복돼도 각 특수 영역을 한 번만 조립하도록 수정하고, redacted 본문만 CMap 손상 검사에 사용하도록 변경했으며, Heron `Cargo.lock`과 `--locked` 설치를 추가했다. Rust 1.88 locked build와 전체 테스트 `823 passed, 61 subtests passed`로 검증했다.

### refactor: Skill 작성 재생성 흐름 정리

- Skill 재생성 intent 판정과 생성 재시도를 하나의 bounded loop로 통합했다.
- repository port 반환 타입과 재시도 회귀 테스트를 보강하고, Skill 작성 흐름 문서를 추가했다.
- llmPipeline 전체 테스트 `790 passed, 61 subtests passed`로 검증했다.

### feat: Wiki 페이지 이름 변경 API 구현

- Backend의 기존 위임 계약에 맞춰 `PATCH /wiki/pages/{wiki_page_id}/rename`을 추가했다.
- 페이지 소속과 slug 충돌을 PostgreSQL transaction 안에서 검증하고, title만 변경하거나 title과 slug를 함께 변경할 수 있도록 했다.
- 잘못된 제목, 페이지 부재, slug 충돌, 동일 slug 재요청과 동시 unique 충돌을 자동화 테스트로 검증했다.

### fix: 실패한 Wiki ingest 변경 이력 보존

- 페이지 저장 뒤 후속 단계가 실패해도 실패 콜백의 `changed_pages`에 이미 생성된 operation artifact를 보존한다.
- operation별 JSON 기여 조각의 저장 필드와 식별자 검증 기반 재조립을 다시 확인했다.
- llmPipeline 전체 테스트 `800 passed, 61 subtests passed`로 검증했다.

### fix: 재편입 후 Concept 본문 의미 정합성 복구

- 재편입 영향 Page를 원문 문서별 최신 active contribution JSON으로 다시 조립해 수정·삭제 전 주장과 근거를 제거한다.
- 다른 활성 문서의 근거는 유지하고, Concept Markdown과 embedding unit을 같은 lint transaction에서 갱신한다.
- dry-run 후보 보고와 operation별 `rebuild` artifact 저장을 추가하고 llmPipeline 전체 테스트 `803 passed, 61 subtests passed`로 검증했다.

### feat: Query·Agent 응답 개인 설정 계약 구현

- Query와 Agent turn에 optional `output_language`, `response_length`, `allow_web_search`를 추가하고 동일한 trusted prompt 규칙을 적용했다.
- Markdown 편집 언어는 유지하고 새 문서 생성은 사용자 지시 언어를 우선하도록 분리했다.
- 요청이 web search를 금지하면 직접 fallback과 evaluator route를 모두 차단하며, llmPipeline 전체 테스트 `809 passed, 61 subtests passed`로 검증했다.

### fix: OpenAI provider 실환경 E2E runner 수정

- 별도 provider env 파일 없이 기존 `infra/.env`의 공통 `LLM_API_KEY`를 기본으로 읽도록 했다.
- `gpt-5-nano`가 허용하는 `temperature=1.0`을 사용하고, `markdown_create`에 생성 전용 prompt를 전달하도록 수정했다.
- OpenAI 실제 smoke 3종과 llmPipeline 전체 테스트 `810 passed, 61 subtests passed`를 통과했다. Gemini·Claude 실환경 검증은 남아 있다.

### fix: 무기여 Concept와 domain 의존성 정리

- 재편입 후 active contribution이 하나도 남지 않은 Concept를 `deleted` 처리하고 Page link와 embedding을 제거한다.
- Query·Agent·Markdown domain의 `core` 타입 의존을 제거하고 evaluator 호출부 들여쓰기를 정리했다.
- 완료된 AI issue 이동 안내 파일을 삭제하고 llmPipeline 전체 테스트 `812 passed, 61 subtests passed`로 검증했다.
