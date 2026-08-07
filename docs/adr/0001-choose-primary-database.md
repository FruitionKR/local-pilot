# 0001. PostgreSQL을 primary database로 선택

- Status: Accepted
- Date: 2026-08-06

## Context

Fruition은 사용자·workspace·문서·Wiki·채팅·pipeline 실행 상태를 관리하면서 업로드 원본과 생성 artifact도 보존해야 한다.

- 관계와 외래 키가 많은 운영 데이터를 트랜잭션으로 관리해야 한다.
- 파일 원본과 애플리케이션 metadata를 분리해야 한다.
- 로컬 개발과 향후 S3 호환 환경의 차이를 줄여야 한다.
- Flyway migration으로 스키마를 재현해야 한다.

## Decision

primary database는 PostgreSQL을 사용하고, 파일 객체는 S3 호환 Object Storage에 저장한다. 로컬에서는 PostgreSQL 16과 MinIO를 `infra/docker-compose.dev.yml`로 실행한다.

Spring Boot와 llmPipeline은 공용 PostgreSQL을 사용하지만, 스키마 버전은 backend의 Flyway migration을 기준으로 관리한다. DB에는 식별자·상태·관계·metadata·URI를 저장하고, 원본 파일·Markdown·Wiki artifact·asset은 Object Storage에 저장한다.

## Alternatives Considered

- PostgreSQL만 사용: 큰 binary와 운영 데이터를 같은 저장 계층에 둔다.
- MongoDB 중심 구성: 현재 workspace·member·document·Wiki·chat 관계와 제약을 별도로 관리해야 한다.
- AWS RDS와 S3만 사용: 운영에는 적합하지만 로컬 MVP의 재현성과 비용이 불필요하게 복잡해진다.

## Consequences / Trade-offs

### Positive

- 관계형 제약, transaction, index, Flyway를 활용할 수 있다.
- MinIO와 S3 호환 API로 로컬·배포 환경의 object interface를 맞출 수 있다.
- 운영 데이터와 파일 데이터의 보존·백업 정책을 분리할 수 있다.

### Negative

- PostgreSQL과 MinIO를 함께 운영·백업·모니터링해야 한다.
- 두 저장소 사이 transaction은 원자적이지 않아 upload 실패와 고아 object 정리가 필요하다.
- llmPipeline이 일부 Wiki 산출물을 PostgreSQL에 직접 쓰는 소유권 부채가 남는다. 현재 event 처리 결정은 [ADR-0003](./0003-choose-event-processing-strategy.md)에서 관리한다.

## Follow-up

- 운영 환경의 PostgreSQL·Object Storage 배포 방식은 별도 결정으로 확정한다.
- object 참조 무결성과 고아 object 정리 지표를 운영 문서에 추가한다.
