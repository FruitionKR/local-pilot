# 로컬 구동·데모 절차

로컬 전체 스택 구동과 데모 시나리오 요약. 상세 절차·문제 해결은 원문 참조.

> 원문: docs/backlog/local-runbook.md

## 1. 사전 조건

필요 도구.

| 도구 | 버전 | 확인 |
|---|---|---|
| Docker + Compose | 최신 | `docker compose version` |
| Java JDK | 21 | `java -version` |
| Node.js / npm | 20+ / 10+ | `node -v`, `npm -v` |
| Python | 3.10+ | `python3 --version` |
| curl | 기본 | `curl --version` |

환경변수는 `infra/.env`에서 관리. 없으면 예시에서 복사.

```sh
cp infra/.env.example infra/.env
```

필수 키 이름(값은 각자 채움, 시크릿 커밋 금지).

- 공통: `JWT_SECRET`
- AI 기능 사용 시: `LLM_PROVIDER`, `LLM_API_KEY`, `LLM_MODEL`
- 소셜 로그인 사용 시(선택): `GOOGLE_CLIENT_ID/SECRET`, `NAVER_CLIENT_ID/SECRET`, `KAKAO_CLIENT_ID/SECRET`
- 이메일 로그인 데모용 고정 인증 코드: `AUTH_EMAIL_DEV_FIXED_CODE`

Java 21이 기본이 아니면 경로 지정.

```sh
export JAVA_HOME_21=/path/to/jdk-21
```

pipeline 테스트를 로컬에서 실행하려면 가상환경을 만들고 requirements를 설치합니다.

```sh
python3 -m venv services/ai/pipeline/.venv
services/ai/pipeline/.venv/bin/python -m pip install -r services/ai/pipeline/requirements-dev.txt
cd services/ai/pipeline
.venv/bin/python -m pytest -q --ignore=tests/modules/document_restoration
```

`document_restoration` 테스트까지 실행하려면 추가로 `requirements-document-restoration.txt`를 설치합니다.

## 2. 구동 순서

### 2-1. 인프라 (PostgreSQL·MongoDB·Kafka·Redis·MinIO)

```sh
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml up -d
docker compose -f infra/docker-compose.dev.yml ps
```

`fruition-postgresql-dev`가 `healthy`가 되면 다음 단계 진행.

### 2-2. 백엔드 (document-svc :8080 → access-svc :8081)

스크립트 사용(인프라 기동 포함, Flyway 소유자인 document-svc를 먼저 시작).

```sh
./scripts/back-up.sh
```

수동 실행 시(터미널 2개).

```sh
cd services/backend
./gradlew :document-svc:bootRun   # :8080, Flyway migration 수행
./gradlew :access-svc:bootRun     # :8081, document-svc 기동 후
```

확인.

```sh
curl http://localhost:8080/actuator/health   # document-svc {"status":"UP"}
curl http://localhost:8081/actuator/health   # access-svc  {"status":"UP"}
```

### 2-3. ai-svc (converter → pipeline-api·워커)

PDF→Markdown 변환기(markitdown, :8010).

```sh
docker compose -f infra/docker-compose.converter.yml up -d --build
curl http://localhost:8010/health
```

pipeline-api(:8000)와 워커(ingest-worker, edit-event-consumer). 백엔드 기동 후 실행(스키마 순서 보장).

```sh
docker compose --env-file infra/.env \
  -f infra/docker-compose.dev.yml -f infra/docker-compose.pipeline.yml \
  up -d --build pipeline-api ingest-worker edit-event-consumer
curl http://localhost:8000/health
```

pipeline-api만 필요하면 `./scripts/ai-up.sh` 사용 가능.

### 2-4. 프론트엔드 (:3000)

```sh
./scripts/front-up.sh
```

또는 수동 실행.

```sh
cd services/frontend
npm install
npm run dev
```

확인: `curl -I http://localhost:3000` 후 브라우저에서 `http://localhost:3000` 접속.

## 3. 데모 시나리오

1. 로그인 — `http://localhost:3000` 접속, 이메일 가입/로그인. 인증 코드는 `infra/.env`의 `AUTH_EMAIL_DEV_FIXED_CODE` 값 입력. (OAuth 키 설정 시 소셜 로그인도 가능)
2. 워크스페이스 — 워크스페이스 생성 후 진입.
3. 문서 업로드 — PDF 업로드 → converter가 Markdown 변환 → pipeline 워커가 처리. 상태가 `processing`에서 완료로 바뀌는지 확인. 멈춰 있으면 `:8000/health`와 `LLM_*` 키 확인.
4. 문서 편집 — 문서를 열어 내용 수정. 편집 이벤트가 Kafka(`document.edit.event`)로 흘러 파생 상태가 갱신됨.
5. AI 질의 — 문서/워크스페이스 대상 질의 실행, 업로드 문서 기반 응답 확인.

## 4. 종료·초기화

앱 프로세스와 인프라·pipeline 컨테이너 일괄 종료.

```sh
./scripts/dev-down.sh
```

converter 종료.

```sh
docker compose -f infra/docker-compose.converter.yml down
```

데이터까지 초기화(DB·MinIO·pipeline 산출물 삭제, 재현 환경 초기화 시에만).

```sh
./scripts/dev-down.sh --volumes
```

개별 종료 스크립트: `scripts/front-down.sh`, `scripts/back-down.sh`, `scripts/ai-down.sh`.
