# 개발 환경

이 저장소는 Fruition MVP 아키텍처 문서를 기준으로 구성합니다.
PostgreSQL은 AppDB 역할을 맡고, S3 호환 객체 스토리지는 원본 파일과
생성된 Wiki Markdown을 prefix로 분리해 보관합니다.

## 서비스

`docker-compose.dev.yml`은 애플리케이션 스택이 추가되기 전에도 역할이
명확한 MVP 인프라만 실행합니다.

| 서비스 | 로컬 URL | 역할 |
|---|---|---|
| PostgreSQL | `localhost:5432` | 문서, Wiki page, 연결 관계, 채팅 로그를 저장하는 AppDB |
| MinIO API | `http://localhost:9000` | `sources/`와 `wiki/`를 보관하는 S3 호환 객체 스토리지 |
| MinIO Console | `http://localhost:9001` | 로컬 객체 스토리지 확인용 콘솔 |

앱 컨테이너는 아직 정의하지 않습니다. 현재 저장소에는 백엔드나 프론트엔드
코드가 없으므로 placeholder 앱 이미지를 추가하면 개발 환경의 정확도가
떨어집니다. 실제 스택이 도입될 때 `api`, `web` 서비스를 추가합니다.

## 환경변수

`.env.example`을 기준으로 로컬 `.env`를 만들고 필요한 비밀값을 채웁니다.

```sh
cp .env.example .env
```

`.env`와 로컬 override 파일은 Git에서 무시합니다. `.env.example`은 필요한
설정의 공유 계약으로 커밋합니다.

주요 환경변수:

| 환경변수 | 역할 |
|---|---|
| `DATABASE_URL` | 애플리케이션이 PostgreSQL에 연결할 때 사용하는 connection string |
| `S3_ENDPOINT` | 백엔드가 사용할 로컬 MinIO endpoint |
| `S3_BUCKET` | 객체 스토리지 bucket, 기본값은 `fruition-storage` |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY` | 로컬 MinIO 인증 정보 |
| `LLM_PROVIDER`, `LLM_MODEL`, `OPENAI_API_KEY` | LLM provider 설정 |

## 실행

```sh
docker compose --env-file .env -f docker-compose.dev.yml up -d
```

`minio-init` 서비스가 bucket을 자동으로 생성합니다. MVP 객체는 아래 prefix에
저장합니다.

```text
sources/documents/{document_id}/original.{ext}
sources/documents/{document_id}/extracted.txt
wiki/sources/{document_slug}.md
wiki/concepts/{concept_slug}.md
```

## 중지

```sh
docker compose -f docker-compose.dev.yml down
```

로컬 데이터베이스와 객체 스토리지 데이터를 함께 삭제하려면 아래 명령을
사용합니다.

```sh
docker compose -f docker-compose.dev.yml down -v
```

## MVP 범위 메모

아래 서비스는 의도적으로 개발용 compose 파일에서 제외합니다.

- Redis 또는 별도 queue: MVP 아키텍처는 단순 백그라운드 처리를 사용하고,
  `processing_jobs`는 이후 확장 범위로 둡니다.
- Elasticsearch 또는 vector database: MVP 검색은 `wiki_pages.title`과
  `wiki_pages.summary`에 대한 PostgreSQL full-text search에서 시작합니다.
- graph database: MVP 그래프의 node와 edge는 `wiki_pages`와
  `wiki_page_links`로 표현합니다.
- 프로덕션 배포 서비스: 이 파일은 로컬 개발 전용입니다.
