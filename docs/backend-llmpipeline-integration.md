# Backend와 llmPipeline Query 연결

이 문서는 현재 구현 기준으로 Spring Boot backend와 Python `llmPipeline`의 Query API 연결 방식을 정리한다.
이전 구현 과정 기록은 `docs/backlog/backend-llmpipeline-integration.md`에 보관한다.

## 연결 원칙

프론트엔드는 `llmPipeline`을 직접 호출하지 않는다.
오른쪽 채팅 영역은 Spring backend의 `POST /api/query`만 호출하고, Spring이 FastAPI `POST /query`를 중계한다.

```text
Frontend
  -> POST /api/query
  -> Spring QueryService
  -> POST {QUERY_ENDPOINT}
  -> llmPipeline FastAPI /query
  -> Spring 응답 반환 + chat_messages 저장
```

이 구조를 유지해야 하는 이유는 다음과 같다.

- Spring이 `chat_messages`에 user/assistant 메시지를 저장한다.
- Spring이 `chat_message_references`에 답변 근거 `evidence_snippets`를 저장한다.
- 프론트엔드는 Spring API 계약만 바라보면 된다.
- FastAPI 내부 주소와 실행 방식이 바뀌어도 프론트 계약은 유지된다.

## 엔드포인트 분리

문서 처리와 자연어 질의는 서로 다른 FastAPI endpoint를 사용한다.

| 목적 | Spring 설정 | 기본값 | FastAPI endpoint |
| --- | --- | --- | --- |
| 문서 업로드 후 Wiki 생성 | `PROCESSING_ENDPOINT` | `http://localhost:8000/pipeline/runs` | `POST /pipeline/runs` |
| Wiki 기반 자연어 질의 | `QUERY_ENDPOINT` | `http://localhost:8000/query` | `POST /query` |

문서 처리 요청은 `document_id`만 전달한다.

```json
{
  "document_id": "doc_123"
}
```

질의 요청은 사용자 질문만 전달한다.

```json
{
  "question": "LLM Wiki가 뭐야?"
}
```

## 현재 응답 계약

Spring `POST /api/query`는 FastAPI `/query` 응답을 현재 API 계약에 맞춰 반환한다.

주요 응답 필드는 다음과 같다.

| 필드 | 용도 |
| --- | --- |
| `user_message` | 저장된 사용자 질문 메시지 |
| `assistant_message` | 저장된 답변 메시지 |
| `related_pages` | 탐색에 사용된 Wiki page 목록 |
| `evidence_snippets` | 답변 문장 근거. `rank`는 답변의 `[N]` 표식과 대응 |
| `graph_context` | 그래프 하이라이트용 node/edge |
| `traversal_paths` | 답변 생성에 사용한 탐색 경로 |

이전 `source_references`, `highlighted_paths` 중심 응답은 사용하지 않는다.

## 영속화 정책

현재 단계에서는 `related_pages` 영속화를 보류한다.

- `POST /api/query` 응답에는 `related_pages`를 그대로 포함한다.
- `chat_message_references`에는 `evidence_snippets` 기반 근거만 저장한다.
- `GET /api/chat/messages`는 저장된 evidence reference만 복원한다.
- 이전 채팅에서 `related_pages`까지 복원해야 하는 요구가 확정되면 별도 설계를 진행한다.

보류 이유는 `related_pages`가 탐색 결과 목록이고, `evidence_snippets`는 답변 근거 문장이라 데이터 성격이 다르기 때문이다.
같은 테이블에 섞으면 nullable 컬럼이 늘고, 별도 테이블을 만들면 조회 구조가 바뀐다.
현재 목표는 백엔드와 `llmPipeline` Query 연결 검증이므로 영속화 범위를 넓히지 않는다.

## 로컬 실행

`infra/.env`를 준비한다.

```sh
cp infra/.env.example infra/.env
```

Query 연결에 필요한 주요 값은 다음과 같다.

```env
PROCESSING_ENDPOINT=http://localhost:8000/pipeline/runs
QUERY_ENDPOINT=http://localhost:8000/query
QUERY_TIMEOUT_SECONDS=30
QUERY_EMBEDDING_MODE=text-only
UPSTAGE_API_KEY=
UPSTAGE_MODEL=solar-pro2
```

로컬에서 pipeline까지 함께 실행한다.

```sh
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml -f infra/docker-compose.pipeline.yml up -d --build
```

Spring backend는 호스트 기준 `localhost:8000`으로 pipeline을 호출한다.
`pipeline-api` 컨테이너 내부에서는 PostgreSQL과 MinIO를 각각 `postgresql`, `minio` 서비스명으로 호출한다.

## 검증 순서

1. FastAPI 상태 확인

```sh
curl http://localhost:8000/health
```

2. FastAPI Query 직접 확인

```sh
curl -X POST http://localhost:8000/query \
  -H "Content-Type: application/json" \
  -d '{"question":"LLM Wiki가 뭐야?"}'
```

3. Spring Query 중계 확인

```sh
curl -X POST http://localhost:8080/api/query \
  -H "Content-Type: application/json" \
  -d '{"question":"LLM Wiki가 뭐야?"}'
```

4. 채팅 저장 확인

```sh
curl http://localhost:8080/api/chat/messages
```

성공 기준:

- Spring `POST /api/query`가 200을 반환한다.
- 응답에 `assistant_message`, `related_pages`, `evidence_snippets`, `graph_context`, `traversal_paths`가 포함된다.
- `GET /api/chat/messages`에 user/assistant 메시지가 저장된다.
- assistant 메시지의 `references`에는 `evidence_snippets` 기반 reference가 포함된다.

## 남은 결정

- 이전 채팅 기록에서 `related_pages`까지 복원할지 결정해야 한다.
- 복원이 필요하면 `chat_message_references`에 섞을지, `chat_message_related_pages` 같은 별도 테이블로 분리할지 정한다.
- `markdown_uri`는 아직 pipeline 컨테이너 내부 경로 의존 이슈가 남아 있어, 이후 MinIO `wiki/` prefix 저장으로 정리한다.
