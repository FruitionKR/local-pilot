# Fruition Backend

Spring Boot 기반 문서 업로드 및 처리 백엔드 서버입니다.

## 기술 스택

| 항목 | 버전 |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.x |
| PostgreSQL | 16 |
| MinIO | 최신 |
| Gradle | Wrapper 포함 |

---

## 로컬 개발 환경 세팅

### 1. 사전 요구사항

- Java 21 이상 (`java -version` 으로 확인)
- Docker Desktop 실행 중

### 2. 인프라 실행 (PostgreSQL + MinIO)

`infra/` 디렉토리에서 실행합니다. `.env` 파일이 해당 위치에 있어야 합니다.

```bash
cd infra
docker compose -f docker-compose.dev.yml up -d
```

컨테이너가 정상 기동되면 다음 서비스가 활성화됩니다.

| 서비스 | 주소 |
|---|---|
| PostgreSQL | `localhost:5432` |
| MinIO API | `localhost:9000` |
| MinIO 콘솔 | `http://localhost:9001` |

MinIO 콘솔 로그인: `fruition` / `fruition_dev_secret`

### 3. 서버 실행

```bash
cd backend
./gradlew bootRun
```

서버가 기동되면 `http://localhost:8080` 에서 응답합니다.

### 4. 인프라 종료

```bash
# infra/ 디렉토리에서 실행
docker compose -f docker-compose.dev.yml down
```

볼륨까지 초기화하려면:

```bash
docker compose -f docker-compose.dev.yml down -v
```

---

## 환경 변수

환경 변수는 `infra/.env` 파일에서 관리합니다.
처음 세팅할 때 `infra/.env.example`을 복사해서 사용하세요.

```bash
cp infra/.env.example infra/.env
```

`./gradlew bootRun` 실행 시 `infra/.env`를 자동으로 읽어 환경변수를 주입합니다.
Docker Compose 인프라 기동 시에도 동일한 파일을 사용하므로 값을 한 곳에서만 관리하면 됩니다.

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | PostgreSQL 호스트 |
| `POSTGRES_PORT` | `5432` | PostgreSQL 포트 |
| `POSTGRES_DB` | `fruition_mvp` | 데이터베이스명 |
| `POSTGRES_USER` | `fruition` | DB 사용자명 |
| `POSTGRES_PASSWORD` | `fruition_dev_password` | DB 비밀번호 |
| `S3_ENDPOINT` | `http://localhost:9000` | MinIO 엔드포인트 |
| `S3_ACCESS_KEY` | `fruition` | MinIO 액세스 키 |
| `S3_SECRET_KEY` | `fruition_dev_secret` | MinIO 시크릿 키 |
| `S3_BUCKET` | `fruition-storage` | 오브젝트 스토리지 버킷명 |
| `OPENAI_API_KEY` | (없음) | OpenAI API 키 — LLM 기능 사용 시 필요 |
| `LLM_PROVIDER` | `openai` | LLM 프로바이더 |
| `LLM_MODEL` | `gpt-4.1-mini` | 사용할 LLM 모델명 |

---

## API

### Swagger UI

서버 실행 후 브라우저에서 확인할 수 있습니다.

```
http://localhost:8080/swagger-ui.html
```

### 엔드포인트 목록

#### 문서 업로드

```
POST /api/documents
Content-Type: multipart/form-data
```

**요청 파라미터**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `file` | MultipartFile | O | 업로드할 파일 (PDF 또는 Markdown) |

**허용 파일 형식**

- `application/pdf`
- `text/markdown` / `text/x-markdown` (`.md` 확장자)

**요청 예시**

```bash
curl -X POST http://localhost:8080/api/documents \
  -F "file=@/path/to/document.pdf"
```

**응답 예시 (201 Created)**

```json
{
  "id": "doc_a1b2c3d4",
  "filename": "document.pdf",
  "mime_type": "application/pdf",
  "byte_size": 204800,
  "status": "processing",
  "source_uri": "sources/documents/doc_a1b2c3d4/original",
  "uploaded_at": "2026-06-09T07:00:00Z"
}
```

**에러 응답 형식**

```json
{
  "error": {
    "code": "에러_코드",
    "message": "에러 메시지"
  }
}
```

**에러 코드 목록**

| HTTP 상태 | 코드 | 설명 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 파일이 없거나 비어 있음 |
| 409 | `DOCUMENT_ALREADY_EXISTS` | 동일 파일이 이미 업로드됨 (SHA-256 해시 기준) |
| 415 | `UNSUPPORTED_FILE_TYPE` | PDF, Markdown 외 파일 형식 |
| 500 | `INTERNAL_SERVER_ERROR` | 서버 처리 오류 |

---

## 패키지 구조

```
backend/src/main/java/fruition/poc/backend/
├── BackendApplication.java
├── common/
│   ├── ErrorResponse.java          # 공통 에러 응답 형식
│   └── GlobalExceptionHandler.java # 전역 예외 처리
├── config/
│   ├── MinioConfig.java            # MinIO 클라이언트 빈
│   ├── OpenApiConfig.java          # Swagger 설정
│   └── StorageProperties.java      # 스토리지 설정값
└── document/
    ├── api/
    │   └── DocumentController.java     # REST 엔드포인트
    ├── application/
    │   └── DocumentService.java        # 업로드 비즈니스 로직
    ├── domain/
    │   ├── Document.java               # JPA 엔티티
    │   ├── DocumentStatus.java         # 상태 enum (processing / completed / failed)
    │   ├── DocumentUploadException.java
    │   └── DuplicateDocumentException.java
    ├── dto/
    │   └── DocumentUploadResponse.java # 업로드 응답 DTO
    └── infra/
        ├── DocumentProcessingRequester.java # 처리 파이프라인 요청 (현재 스텁)
        └── DocumentRepository.java          # JPA Repository
```

---

## 자주 겪는 문제

**PostgreSQL 연결 실패**

Docker 컨테이너가 완전히 기동되기 전에 서버를 실행하면 연결 오류가 발생합니다.
`docker compose ps` 로 `postgresql` 컨테이너 상태가 `healthy`인지 확인 후 서버를 다시 기동하세요.

**MinIO 버킷 없음 오류**

`minio-init` 컨테이너가 버킷을 자동 생성합니다. 컨테이너 로그를 확인하세요.

```bash
docker logs fruition-minio-init-dev
```

**포트 충돌**

로컬에서 PostgreSQL이나 MinIO가 이미 실행 중이라면 포트가 충돌합니다.
기존 프로세스를 종료하거나 `infra/docker-compose.dev.yml`의 포트 매핑을 변경하세요.
