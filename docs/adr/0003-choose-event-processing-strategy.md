# 0003. PostgreSQL queue와 내부 HTTP 기반 event processing 선택

- Status: Accepted
- Date: 2026-08-06

## Context

문서 ingestion과 Query는 LLM 호출 때문에 HTTP 요청보다 오래 걸릴 수 있다. Spring은 public API·workspace 권한·제품 상태를 담당하고, Python `llmPipeline`은 ingestion·retrieval·Wiki·Agent 실행을 담당한다.

MVP에는 다음 조건이 있다.

- 업로드 transaction과 긴 AI 작업을 분리한다.
- 서버 재시작 후 pending 작업을 복구한다.
- 로컬 Docker Compose에서 별도 broker 없이 실행한다.
- 브라우저는 pipeline을 직접 호출하지 않는다.

## Decision

Spring Boot를 public API 경계로 두고, `document_processing_queue`와 `DocumentProcessingWorker`를 문서 작업의 실행 진입점으로 사용한다.

- worker는 pending row를 2초 fixed delay로 하나 claim하고 FastAPI `/pipeline/runs` 또는 `/chat-wiki/runs`를 호출한다.
- pipeline 진행 상황은 문서 heartbeat callback으로 전달하고, AI operation 결과는 result callback으로 전달한다.
- Query는 짧은 동기 endpoint와 긴 비동기 run + SSE/polling 경로를 함께 제공한다.
- FastAPI 내부 route는 내부 token 계약으로 보호하고, 브라우저에는 Spring의 workspace-scoped API만 노출한다.
- MVP에서는 대량 Wiki 결과를 pipeline이 공용 PostgreSQL에 직접 쓰는 것을 허용한다. 단일 writer 회수는 후속 작업으로 남긴다.

## Alternatives Considered

- Kafka·Redis Streams 같은 durable broker: 처리량과 재생성에는 유리하지만 MVP 운영·배포 복잡도가 커진다.
- Spring process 내부 executor만 사용: queue durable state와 재시작 복구가 약해진다.
- 모든 산출물을 Spring API로 전달하는 single-writer: 소유권은 명확하지만 대량 결과 전달 계약과 변경 규모가 커진다.

## Consequences / Trade-offs

### Positive

- PostgreSQL만으로 pending·processing·completed·failed 상태와 재시작 복구를 구현할 수 있다.
- Spring과 FastAPI의 책임을 분리하면서 브라우저 API 계약을 안정적으로 유지한다.
- Query는 짧은 작업과 긴 작업의 사용자 경험을 각각 제공한다.

### Negative

- 현재 worker는 한 번에 하나의 document만 처리해 처리량이 제한된다.
- Query run과 SSE channel은 Spring process memory에 있어 다중 replica·재시작 replay가 어렵다.
- pipeline이 일부 domain table을 직접 쓰므로 schema coupling과 single-writer 위반이 남는다.
- FastAPI가 내부 token을 요구하는 반면 Spring outbound requester 일부에서 header 주입이 확인되지 않아 설정 환경에서 `401`이 될 수 있다.

## Follow-up

- pipeline status polling 또는 completion callback으로 `documents.status` 소유권을 Backend로 회수한다.
- worker lease·concurrency·durable broker 도입은 처리량 요구가 확인될 때 결정한다.
- 모든 outbound requester와 callback에 내부 token·timeout·retry·replay 방지 정책을 일관되게 적용한다.

자세한 이전 소유권 분석은 [보관 ADR](../backlog/adr-0001-pipeline-db-ownership.md)을 참고한다.
