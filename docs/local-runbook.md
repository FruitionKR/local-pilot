# 로컬 실행 가이드

이 문서는 Fruition MVP를 새 환경에서 재현 가능하게 실행하기 위한 요구사항과 실행 순서를 정리합니다. 로컬 실행 대상은 PostgreSQL, MinIO, Spring Boot 백엔드, Next.js 프론트엔드입니다.

## 요구사항

| 항목 | 권장 버전 | 확인 명령 | 비고 |
|---|---:|---|---|
| Docker | 최신 안정 버전 | `docker --version` | PostgreSQL, MinIO 실행에 필요 |
| Docker Compose | Docker CLI plugin | `docker compose version` | `docker-compose` 단독 명령이 아니라 `docker compose` 사용 |
| Java JDK | 21 | `java -version` 또는 `$JAVA_HOME_21/bin/java -version` | 백엔드 Gradle toolchain 요구사항 |
| Node.js | 20 LTS 이상 | `node -v` | 프론트엔드 Next.js 실행 |
| npm | 10 이상 | `npm -v` | `frontend/package-lock.json` 기준 설치 |
| curl | 기본 제공 또는 설치 | `curl --version` | 기동 확인에 사용 |
| lsof 또는 fuser | OS 기본 도구 | `lsof -v` 또는 `fuser -V` | 전체 종료 스크립트의 포트 기반 프로세스 정리에 사용 |

macOS에서 Docker Desktop 대신 Colima를 쓸 수 있습니다. 이 경우 `colima start` 후 Docker context가 Colima를 보도록 설정되어 있어야 합니다. 제공 스크립트는 Docker daemon에 연결하지 못하고 `colima` 명령이 있으면 Colima 시작을 시도합니다.

## 포트

아래 포트가 비어 있어야 합니다.

| 포트 | 서비스 |
|---:|---|
| `3000` | Next.js 프론트엔드 |
| `8080` | Spring Boot 백엔드 |
| `5432` | PostgreSQL |
| `9000` | MinIO API |
| `9001` | MinIO Console |

LLM pipeline까지 함께 실행하는 경우 `8000`도 필요합니다. 기본 프론트엔드 설정은 백엔드를 `http://localhost:8080`으로 호출합니다.

## 환경변수

로컬 환경변수는 `infra/.env`에서 관리합니다. 파일이 없으면 아래 명령으로 생성합니다.

```sh
cp infra/.env.example infra/.env
```

기본 인프라와 프론트/백엔드 기동에는 예시 값만으로 충분합니다. 문서 처리 pipeline 또는 LLM 기능을 실제로 사용하려면 `OPENAI_API_KEY` 또는 `UPSTAGE_API_KEY` 같은 외부 API 키를 채워야 합니다.

Java 21이 기본 Java가 아닌 환경에서는 아래 중 하나를 지정합니다.

```sh
export JAVA_HOME_21=/path/to/jdk-21
```

macOS Homebrew 설치 예시는 다음 경로입니다.

```sh
export JAVA_HOME_21=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

## 한 번에 실행

실행 스크립트를 사용하면 인프라, 백엔드, 프론트엔드를 순서대로 시작하고 HTTP 응답까지 확인합니다.

```sh
chmod +x scripts/dev-up.sh
./scripts/dev-up.sh
```

스크립트가 수행하는 작업은 아래와 같습니다.

1. `infra/.env`가 없으면 `infra/.env.example`에서 복사
2. Docker daemon 확인, 가능한 경우 Colima 시작
3. `infra/docker-compose.dev.yml`로 PostgreSQL과 MinIO 시작
4. Java 21 경로 탐색 후 `backend/./gradlew bootRun` 실행
5. `frontend/node_modules`가 없으면 `npm install` 실행
6. `frontend/npm run dev` 실행
7. `http://localhost:8080/api/documents`, `http://localhost:3000` 응답 확인

스크립트를 종료하려면 터미널에서 `Ctrl-C`를 누릅니다. 이때 백엔드와 프론트엔드 프로세스는 종료되지만 PostgreSQL과 MinIO 컨테이너는 유지됩니다.

## 수동 실행 순서

자동 스크립트 대신 단계별로 실행하려면 아래 순서를 사용합니다.

### 1. 인프라 시작

```sh
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml up -d
```

상태 확인:

```sh
docker compose -f infra/docker-compose.dev.yml ps
```

`fruition-postgresql-dev`가 `healthy` 상태가 되면 백엔드를 실행할 수 있습니다.

### 2. 백엔드 시작

Java 21이 기본 Java인 경우:

```sh
cd backend
./gradlew bootRun
```

기본 Java가 21이 아닌 경우:

```sh
cd backend
./gradlew bootRun -Porg.gradle.java.installations.paths=/path/to/jdk-21
```

기동 확인:

```sh
curl http://localhost:8080/api/documents
```

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

### 3. 프론트엔드 시작

```sh
cd frontend
npm install
npm run dev
```

기동 확인:

```sh
curl -I http://localhost:3000
```

브라우저에서 아래 주소를 엽니다.

```text
http://localhost:3000
```

## 종료

프론트엔드와 백엔드는 실행 중인 터미널에서 `Ctrl-C`로 종료합니다.

인프라 컨테이너를 중지하려면 저장소 루트에서 아래 명령을 실행합니다.

```sh
docker compose -f infra/docker-compose.dev.yml down
```

로컬 데이터베이스와 객체 스토리지 볼륨까지 삭제하려면 `-v`를 추가합니다.

```sh
docker compose -f infra/docker-compose.dev.yml down -v
```

## 전체 종료 스크립트

앱 프로세스와 로컬 인프라를 한 번에 종료하려면 아래 스크립트를 사용합니다.

```sh
./scripts/dev-down.sh
```

스크립트가 수행하는 작업은 아래와 같습니다.

1. `3000` 포트의 Next.js 프로세스 종료
2. `8080` 포트의 Spring Boot 프로세스 종료
3. `infra/docker-compose.dev.yml`의 PostgreSQL, MinIO 컨테이너 종료

로컬 데이터베이스와 객체 스토리지 볼륨까지 삭제하려면 `--volumes` 옵션을 사용합니다.

```sh
./scripts/dev-down.sh --volumes
```

`--volumes`는 업로드 문서, Wiki page, DB 레코드 등 로컬 개발 데이터를 삭제하므로 재현 환경 초기화가 필요한 경우에만 사용합니다.

## 문제 해결

### Docker daemon에 연결할 수 없음

오류 예:

```text
failed to connect to the docker API at unix:///var/run/docker.sock
```

Docker Desktop을 사용하면 앱이 실행 중인지 확인합니다. Colima를 사용하면 아래 명령으로 시작합니다.

```sh
colima start
docker context use colima
```

필요하면 Docker socket을 직접 지정합니다.

```sh
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
```

### Java 21 toolchain을 찾지 못함

오류 예:

```text
Cannot find a Java installation matching: {languageVersion=21}
```

JDK 21을 설치하고 `JAVA_HOME_21`을 지정하거나, Gradle 실행 시 경로를 직접 넘깁니다.

```sh
./gradlew bootRun -Porg.gradle.java.installations.paths=/path/to/jdk-21
```

### 포트가 이미 사용 중임

아래 포트를 점유한 프로세스를 종료하거나, 해당 서비스를 먼저 내립니다.

```sh
lsof -nP -iTCP:3000 -sTCP:LISTEN
lsof -nP -iTCP:8080 -sTCP:LISTEN
lsof -nP -iTCP:5432 -sTCP:LISTEN
```

### 프론트엔드에서 백엔드 호출이 실패함

`frontend/next.config.mjs`는 기본 백엔드 주소를 `http://localhost:8080`으로 사용합니다. 다른 주소를 사용해야 하면 프론트엔드 실행 전에 아래 값을 지정합니다.

```sh
export NEXT_PUBLIC_BACKEND_URL=http://localhost:8080
```

### 업로드 후 문서 처리가 계속 processing 상태임

기본 실행은 PostgreSQL, MinIO, 백엔드, 프론트엔드만 포함합니다. 실제 문서 처리 pipeline이 필요하면 `infra/docker-compose.pipeline.yml` 또는 관련 pipeline 서비스를 추가로 실행하고, `infra/.env`의 `PROCESSING_ENDPOINT`, `OPENAI_API_KEY`, `UPSTAGE_API_KEY` 값을 확인해야 합니다.
