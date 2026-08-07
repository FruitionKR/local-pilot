# Fruition Demo Script

> 기준: `dev` 브랜치 현재 구현
> 목적: 3~5분 안에 인증, 문서 ingestion, Wiki 탐색, 근거 기반 Query, Markdown 편집 흐름을 보여준다.

## 1. 사전 준비

```sh
cp infra/.env.example infra/.env
./scripts/dev-up.sh
```

실행 확인:

- Frontend: `http://localhost:3000`
- Backend health: `http://localhost:8080/actuator/health`
- Pipeline health: `http://localhost:8000/health`

LLM 기능을 시연하려면 `infra/.env`의 `LLM_PROVIDER`, `LLM_API_KEY`, `LLM_MODEL`을 설정한다. 현재 FastAPI 내부 token과 Spring outbound requester의 연결 gap이 있으므로 pipeline 요청이 `401`이면 [아키텍처 문서의 보안 gap](./architecture.md#security--privacy)과 [상세 계약](./spec/llmpipeline-backend-api-contract.md)을 먼저 확인한다.

## 2. 시연 순서

### 2.1 인증과 Workspace

1. 회원가입 또는 OAuth 로그인 화면을 연다.
2. 로그인 후 기본 Workspace가 생성되었는지 확인한다.
3. Workspace를 전환해 문서·Wiki·채팅이 Workspace 경계 안에서 표시되는지 확인한다.

보여줄 포인트:

- access token과 refresh token을 분리한다.
- Workspace membership이 없는 resource는 조회하지 않는다.

### 2.2 Markdown 문서 생성

다음과 같이 짧은 Markdown을 준비한다.

```markdown
# 온보딩 메모

## 배포 원칙

작은 변경을 자주 배포하고, 실패하면 이전 버전으로 복원한다.

## 관련 개념

- canary deployment
- rollback
```

1. Markdown 문서를 업로드하거나 새 문서로 생성한다.
2. 문서 상태가 `uploaded`에서 `processing`으로 바뀌는 것을 확인한다.
3. pipeline 처리 후 `completed`가 되고 Wiki page가 생성되는지 확인한다.

PDF는 현재 원본 저장이 중심이고 PDF converter가 일반 upload flow에 자동 연결되어 있지 않으므로, 안정적인 시연은 Markdown을 사용한다.

### 2.3 Wiki 탐색

1. Wiki graph에서 `source` page와 `concept` page를 확인한다.
2. page 상세에서 원본 문서와 source block 관계를 확인한다.
3. graph edge를 따라 관련 concept를 탐색한다.

보여줄 포인트:

- 원본 파일 자체가 graph node가 아니라 Wiki page가 graph node다.
- `document_wiki_links`, `wiki_page_links`, `source_blocks`가 각각 출처·관계·근거를 표현한다.

### 2.4 근거 기반 Query

1. 채팅 session을 만든다.
2. 다음 질문을 입력한다.

```text
배포 실패에 대비한 문서의 원칙은 무엇인가?
```

3. 답변과 함께 `related_pages`, `evidence_snippets`, graph highlight가 표시되는지 확인한다.
4. 근거를 클릭해 source document의 block으로 이동한다.

긴 질의는 비동기 run으로 실행되며 SSE 또는 polling으로 진행 상태를 확인한다.

### 2.5 Markdown 편집과 복원

1. 문서에서 한 문단을 선택한다.
2. Agent panel에 “문장을 더 간결하게 정리해줘”라고 요청한다.
3. 변경 preview와 target 범위를 확인한다.
4. 사용자가 적용한 뒤 version이 증가하는지 확인한다.
5. diff 화면에서 이전 version과 비교하고, 필요하면 restore한다.

보여줄 포인트:

- LLM 결과는 바로 저장하지 않고 preview와 validation을 거친다.
- restore는 과거 version을 덮어쓰지 않고 새 version으로 기록한다.

## 3. 실패 시연 선택지

시간이 허용되면 다음 중 하나를 추가로 보여준다.

- 잘못된 파일 형식 업로드 → `415`
- 다른 Workspace resource 요청 → `404`
- 오래된 `base_version`으로 저장 → `409`
- 다른 사용자가 잡은 edit lock에서 저장 → `423`
- LLM/pipeline 장애 → assistant 또는 document가 `failed`

## 4. 시연 완료 체크리스트

- [ ] 로그인과 Workspace 경계를 설명했다.
- [ ] Markdown upload → pipeline → Wiki page 흐름을 보여줬다.
- [ ] graph node/edge와 원본 source block의 차이를 설명했다.
- [ ] Query 답변과 evidence/related page를 함께 보여줬다.
- [ ] AI preview → version → diff/restore를 보여줬다.
- [ ] 한 가지 실패 처리와 HTTP status를 설명했다.

## 5. 종료

```sh
./scripts/dev-down.sh
```

로컬 데이터를 초기화할 필요가 없다면 volume 삭제 옵션은 사용하지 않는다. 상세 환경 요구사항은 보관된 [로컬 실행 가이드](./backlog/local-runbook.md)를 참고한다.
