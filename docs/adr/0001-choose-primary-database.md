# ADR-0001: 주 데이터베이스 선택 — PostgreSQL 물리 분리 + MongoDB 본문 저장

- 상태: 채택 (2026-08 기준 운영 반영 완료)
- 관련: [architecture.md](../architecture.md) §4, [data-model.md](../data-model.md)

## 맥락

단일 PostgreSQL에 인증·워크스페이스·문서·AI 테이블이 동거했다. MSA 전환에서 서비스별 데이터 소유권을 강제해야 했고, 문서 편집 본문은 revision·write-id·outbox를 한 트랜잭션으로 묶는 저장 모델이 필요했다.

## 결정

1. **PostgreSQL을 서비스별 물리 DB로 분리**: access_db(access-svc) / core_db(document-svc) / ai_db(ai-svc). AWS에서는 Access RDS + Core RDS 2 instance.
2. **DB 계정 격리**: 서비스마다 runtime(DML)/migration(DDL) 계정 분리, 타 서비스 DB write 불가 (`infra/postgres/init-db-isolation.sh`, validation 스크립트로 실검증).
3. **문서 본문·편집 revision은 MongoDB**: `document_edit_states`·`document_edit_writes`·`document_edit_outbox`를 단일 Mongo 트랜잭션으로 기록. AWS에서는 MongoDB Atlas.
4. **전환기 예외**: ai 테이블 4개(pipeline_runs·임베딩 3종)는 core_db 동거 유지 — 검색 CTE·ingest 원자성 재설계가 선행돼야 이전 가능. ai_runtime 별도 계정으로 안전 확보. AI 독립 스케일이 필요해지는 시점에 이전.

## 대안과 기각 사유

- **단일 DB + 스키마 분리만**: 계정 실수·JOIN 유혹으로 경계 침식. 물리 분리로 컴파일·네트워크 수준 강제 선택.
- **본문도 PostgreSQL(JSONB)**: revision + write-id + outbox 원자 기록과 문서 단위 동시 편집 병합 모델에 Mongo 트랜잭션 + 문서 지향 모델이 단순.

## 결과

- 서비스 간 DB 직접 참조 불가 — 내부 API·Redis projection·Kafka로만 연결.
- Flyway는 core_db(document-svc 소유), access_db(access-svc 소유) 각자 관리. ai_db는 python `ai_schema.sql` 멱등 부트스트랩.
- 비용: RDS 2대 + Atlas. 트레이드오프로 수용.
