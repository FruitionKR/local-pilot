# 개발 환경

이 저장소는 Fruition MVP 아키텍처 문서를 기준으로 구성합니다.
PostgreSQL은 AppDB 역할을 맡고, S3 호환 객체 스토리지는 원본 파일과
생성된 Wiki Markdown을 prefix로 분리해 보관합니다.

## 서비스

`infra/docker-compose.dev.yml`은 애플리케이션 스택이 추가되기 전에도 역할이
명확한 MVP 인프라만 실행합니다.

| 서비스 | 로컬 URL | 역할 |
|---|---|---|
| PostgreSQL | `localhost:5432` | 문서, Wiki page, 연결 관계, 채팅 로그를 저장하는 AppDB |
| MinIO API | `http://localhost:9000` | `sources/`와 `wiki/`를 보관하는 S3 호환 객체 스토리지 |
| MinIO Console | `http://localhost:9001` | 로컬 객체 스토리지 확인용 콘솔 |
| PDF Converter | `http://localhost:8010` | PDF 진단, OCR, Markdown 변환을 컨테이너 안에서 처리하는 워커 API |

앱 컨테이너는 아직 정의하지 않습니다. 현재 저장소에는 백엔드나 프론트엔드
코드가 없으므로 placeholder 앱 이미지를 추가하면 개발 환경의 정확도가
떨어집니다. 실제 스택이 도입될 때 `api`, `web` 서비스를 추가합니다.

## 환경변수

`infra/.env.example`을 기준으로 로컬 `infra/.env`를 만들고 필요한 비밀값을 채웁니다.

```sh
cp infra/.env.example infra/.env
```

`.env`와 로컬 override 파일은 Git에서 무시합니다. `infra/.env.example`은 필요한
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
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml up -d
```

`minio-init` 서비스가 bucket을 자동으로 생성합니다. MVP 객체는 아래 prefix에
저장합니다.

```text
sources/documents/{document_id}/original.{ext}
sources/documents/{document_id}/extracted.txt
wiki/sources/{document_slug}.md
wiki/concepts/{concept_slug}.md
```

PDF 변환 워커를 함께 띄울 때는 별도 compose 파일을 추가합니다.

```sh
docker compose \
  --env-file infra/.env \
  -f infra/docker-compose.dev.yml \
  -f infra/docker-compose.converter.yml \
  up -d
```

converter는 PDF를 Spring 프로세스 안에서 직접 파싱하지 않고, 별도 컨테이너의
`/convert` API에서 임시 작업 디렉터리를 만들어 처리합니다. 처리 순서는 아래와
같습니다.

```text
input.pdf
  -> pdfinfo / pdffonts 진단
  -> ocrmypdf -l kor+eng --force-ocr --deskew --clean
  -> markitdown fixed.pdf -o output.md
```

컨테이너 안에서 생성되는 작업 파일은 `input.pdf`, `info.txt`, `fonts.txt`,
`fixed.pdf`, `output.md`, `process.log`입니다. 요청 처리가 끝나면 임시
디렉터리는 삭제되고, API 응답에는 `markdown`, `pdfinfo`, `pdffonts`,
`process_log`가 포함됩니다.

```sh
curl -F "file=@sample.pdf" http://localhost:8010/convert
```

converter 이미지는 `poppler-utils`, `ocrmypdf`, `tesseract-ocr`,
`tesseract-ocr-kor`, `tesseract-ocr-eng`, `ghostscript`, `unpaper`,
`markitdown[pdf]`를 포함합니다.

PDF 입력은 신뢰할 수 없는 파일로 보고 converter 컨테이너 안에서만 처리합니다.
compose 설정은 root filesystem을 read-only로 두고, Linux capability를 제거하며,
작업 파일은 `/tmp` tmpfs에만 생성합니다.

## GitHub Webhook 알림

Discord의 GitHub Webhook 연동을 사용하면 별도 workflow 없이 저장소 이벤트를
Discord 채널로 보낼 수 있습니다. 이 저장소에서는 Pull Request 이벤트를
Discord로 받을 수 있도록 GitHub repository webhook을 설정합니다.

설정 절차:

1. Discord에서 알림을 받을 채널의 `Edit Channel` > `Integrations` > `Webhooks`로 이동합니다.
2. 새 Webhook을 만들고 Webhook URL을 복사합니다.
3. GitHub 저장소의 `Settings` > `Webhooks`로 이동합니다.
4. `Add webhook`을 선택합니다.
5. `Payload URL`에 Discord Webhook URL 끝에 `/github`을 붙여 입력합니다.
6. `Content type`은 `application/json`을 선택합니다.
7. `Secret`은 비워둡니다. Discord의 GitHub Webhook endpoint는 GitHub 서명 검증 secret을 따로 처리하지 않습니다.
8. `Which events would you like to trigger this webhook?`에서 `Let me select individual events`를 선택합니다.
9. `Pull requests`만 선택하고 `Active`를 켠 뒤 `Add webhook`으로 저장합니다.

GitHub Webhook 설정은 모든 Pull Request 이벤트를 Discord로 전달합니다.
GitHub repository webhook과 Discord의 기본 GitHub 연동만으로는 GitHub Actions처럼
`opened`, `reopened`, `ready_for_review` 또는 대상 브랜치가 `dev`/`main`인지까지
저장소 코드에서 세밀하게 제한할 수 없습니다. 알림량을 줄이려면 Discord 채널을
별도로 두거나, 더 세밀한 필터가 필요할 때 GitHub Actions 또는 중간 Webhook
receiver를 사용합니다.

Webhook URL은 해당 Discord 채널에 메시지를 보낼 수 있는 비밀값입니다. 저장소에는
URL을 커밋하지 않고 GitHub repository webhook 설정에만 저장합니다. URL 교체가
필요하면 Discord에서 기존 Webhook을 삭제하거나 재생성한 뒤 GitHub Webhook의
`Payload URL`을 갱신합니다.

## 중지

```sh
docker compose -f infra/docker-compose.dev.yml down
```

로컬 데이터베이스와 객체 스토리지 데이터를 함께 삭제하려면 아래 명령을
사용합니다.

```sh
docker compose -f infra/docker-compose.dev.yml down -v
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
