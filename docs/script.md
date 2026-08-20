# 실행 스크립트·로컬 데모 절차

프로젝트 스크립트의 역할, 로컬 스택 구동 순서와 데모 시나리오를 정리한다. 상세 문제 해결은 원문을 참조한다.

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

구동·재검증 명령에서 `infra/.env`를 shell source하지 않는다. `scripts/*.sh`의
`--env-file` 경로와 Gradle `bootRun`의 dotenv 경로를 사용한다.

```sh
cp infra/.env.example infra/.env
```

필수 키 이름(값은 각자 채움, 시크릿 커밋 금지).

- 공통: `JWT_SECRET`
- AI 기능 사용 시: ai-svc secret env `OPENAI_API_KEY`, `GEMINI_API_KEY`, `ANTHROPIC_API_KEY` 중 사용할 provider의 키
- 소셜 로그인 사용 시(선택): `GOOGLE_CLIENT_ID/SECRET`, `NAVER_CLIENT_ID/SECRET`, `KAKAO_CLIENT_ID/SECRET`
- 이메일 로그인 데모용 고정 인증 코드: `AUTH_EMAIL_DEV_FIXED_CODE`

Java 21이 기본이 아니면 경로 지정.

```sh
export JAVA_HOME=/path/to/jdk-21
export JAVA_HOME_21="$JAVA_HOME"
```

`JAVA_HOME_21`은 프로젝트 구동 스크립트가 사용하고, `JAVA_HOME`은 직접 실행한 Gradle wrapper가 사용한다.

pipeline 테스트를 로컬에서 처음 실행할 때만 가상환경을 만들고 requirements를 설치합니다.

```sh
python3 -m venv services/ai/pipeline/.venv
services/ai/pipeline/.venv/bin/python -m pip install -r services/ai/pipeline/requirements-dev.txt
cd services/ai/pipeline
.venv/bin/python -m pytest -q --ignore=tests/modules/document_restoration
```

이미 `services/ai/pipeline/.venv`가 있고 requirements가 바뀌지 않았다면 기존 interpreter를
그대로 사용한다. 재검증마다 가상환경을 다시 만들거나 의존성을 다시 설치하지 않는다.

`document_restoration` 테스트까지 실행하려면 추가로 `requirements-document-restoration.txt`를 설치합니다.

## 2. 실행 스크립트

일반적인 로컬 개발은 루트 디렉터리에서 `dev-up.sh`와 `dev-down.sh`를 사용한다.

| 스크립트 | 역할 | 유지되는 항목 |
|---|---|---|
| `scripts/bootstrap.sh` | 필수 도구와 프론트엔드 의존성을 준비한다. `dev-up.sh`가 자동 호출한다. | 해당 없음 |
| `scripts/dev-up.sh` | 공용 인프라, 호스트 백엔드, AI API·워커, 프론트엔드를 순서대로 시작한다. | 실행 중인 supervisor가 전체 호스트 프로세스를 관리한다. |
| `scripts/dev-down.sh` | 이 프로젝트가 등록한 supervisor와 Compose 컨테이너를 종료한다. | 기본값은 로컬 볼륨을 유지한다. |
| `scripts/front-up.sh` / `front-down.sh` | 프론트엔드만 시작하거나 종료한다. | 백엔드와 Compose 서비스는 유지한다. |
| `scripts/back-up.sh` / `back-down.sh` | 공용 인프라와 호스트 백엔드를 시작하거나 백엔드만 종료한다. | 종료 후 공용 인프라와 볼륨은 유지한다. |
| `scripts/back-test.sh` | Java 21을 찾아 백엔드 Gradle 테스트를 실행한다. 인자가 없으면 CI와 같은 세 모듈 테스트를 실행한다. | 서비스를 시작하거나 종료하지 않는다. |
| `scripts/ai-up.sh` / `ai-down.sh` | 백엔드 기동 후 AI image 하나로 Pipeline API와 워커 전체를 시작하거나 종료한다. | 종료 후 공용 인프라와 볼륨은 유지한다. |
| `scripts/ai-e2e.sh` | 배포용 Compose 조합을 빌드하고 converter·ingest·query·agent·lint를 Gemini로 실제 실행한다. | 전체 컨테이너와 DB 볼륨을 유지해 결과를 재확인할 수 있다. |

전체 로컬 환경을 시작한다.

```sh
./scripts/dev-up.sh
```

`dev-up.sh`는 현재 터미널에서 계속 실행된다. 다른 터미널에서 다음 명령으로 종료한다.

```sh
./scripts/dev-down.sh
```

PDF 변환기 `markitdown`은 선택 서비스이므로 `dev-up.sh`에 포함되지 않는다. PDF 업로드를 검증할 때 별도로 시작한다.

```sh
docker compose -f infra/compose.converter.yml up -d
```

지표 확인용 Prometheus·Grafana도 선택 스택이다. 자세한 절차는 3-6을 본다.

```sh
docker compose -f infra/compose.monitoring.yml up -d
```

up 스크립트는 `.runtime/`에 supervisor PID를 등록한다. down 스크립트는 등록된 supervisor만 종료하며, 같은 포트를 사용하는 다른 프로젝트 프로세스는 종료하지 않는다. 이미 다른 프로세스가 필요한 포트를 사용 중이면 up 스크립트는 즉시 실패한다.

## 3. 상세 구동 순서

### 3-1. 인프라 (PostgreSQL·Kafka·Redis·MinIO)

```sh
docker compose --env-file infra/.env -f infra/compose.infra.yml up -d
docker compose -f infra/compose.infra.yml ps
```

`fruition-postgresql-dev`가 `healthy`가 되면 다음 단계 진행.

배포 이미지 단위 검증은 공용 인프라·AI·변환기·컨테이너 통합 override를 함께 구성한다.

```sh
docker compose --env-file infra/.env \
  -f infra/compose.infra.yml -f infra/compose.ai.yml \
  -f infra/compose.converter.yml -f infra/compose.containerized.yml \
  up -d --build
```

### 3-2. 백엔드 (document-svc :8080 → access-svc :8081)

백엔드 테스트는 Java 설치 경로를 직접 추측하지 말고 루트에서 스크립트로 실행한다.

```sh
./scripts/back-test.sh
./scripts/back-test.sh :document-svc:test --tests 'fruition.core.aihistory.*'
```

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

actuator는 업무 포트와 분리된 관리 포트에 있다(document 8082, access 8083).
ALB Ingress가 업무 포트만 라우팅하므로 `/actuator/prometheus`가 외부에 노출되지 않는다.

```sh
curl http://localhost:8082/actuator/health   # document-svc {"status":"UP"}
curl http://localhost:8083/actuator/health   # access-svc  {"status":"UP"}
curl http://localhost:8082/actuator/prometheus   # Prometheus 스크레이프용 지표
```

### 3-3. ai-svc (converter → pipeline-api·워커)

PDF→Markdown 변환기(markitdown, :8010).

```sh
docker compose -f infra/compose.converter.yml up -d
curl http://localhost:8010/health
```

실제 PDF를 Gemini로 변환하는 Docker E2E는 공용 스크립트로 실행한다. 스크립트는
`infra/.env`의 `GEMINI_API_KEY`를 사용해 이미지를 다시 빌드하고, health 확인 후
Markdown과 복원 summary를 저장소의 `.tmp/converter-e2e/`에 저장한다. 출력 경로를
명시하면 그 위치를 사용한다. 별도 worktree의 env 파일을
사용하려면 `CONVERTER_ENV_FILE`을 지정한다.

```sh
./scripts/converter-e2e.sh /path/to/input.pdf [/path/to/output.md]
```

converter뿐 아니라 컨테이너형 백엔드와 모든 AI worker를 함께 검증하려면 통합 E2E를 실행한다.
이 명령은 로컬 고정 이메일 인증번호로 격리된 계정·워크스페이스를 생성하고 실제
스마트팜 문서 4개를 누적 ingest한 뒤 query·agent·lint 완료까지 기다리고 promotion 결과도
기록한다. 결과는 `.tmp/ai-e2e/<실행 ID>/`에 남으며
기존 개발 DB를 마이그레이션하거나 지우지 않도록 `fruition-ai-e2e` Compose project의
별도 볼륨을 쓴다. 고정 컨테이너 이름 충돌을 막기 위해 기존 개발 컨테이너는 내리지만
그 볼륨은 보존하며, E2E 컨테이너와 볼륨도 후속 점검을 위해 유지한다.

```sh
./scripts/ai-e2e.sh /path/to/input.pdf
```

다른 env 또는 결과 디렉터리를 쓰려면 `AI_E2E_ENV_FILE`, `AI_E2E_OUTPUT_DIR`을 지정한다.

검증 후 converter를 종료한다.

```sh
docker compose --env-file infra/.env -f infra/compose.converter.yml down
```

pipeline-api(:8000)와 워커(ingest/query/agent/maintenance task worker, edit-event-consumer). 백엔드 기동 후 실행(스키마 순서 보장).

```sh
docker compose --env-file infra/.env \
  -f infra/compose.infra.yml -f infra/compose.ai.yml \
  up -d pipeline-api ingest-worker query-task-worker agent-task-worker \
  maintenance-task-worker edit-event-consumer pipeline-agent-worker
curl http://localhost:8000/health
```

`./scripts/ai-up.sh`는 pipeline image를 한 번 빌드한 뒤 pipeline-api와 전체 워커를 같은 image로 시작한다.
기존 image를 재사용할 때는 위 compose 명령을 `--build` 없이 실행한다.

### 안정적 통합 재검증 규칙

- 백엔드는 `./scripts/back-up.sh` 또는 위의 Gradle 명령으로 전용 장기 runner 터미널에서 실행한다. 일회성 테스트 agent가 runner를 소유하거나 종료하지 않는다.
- 결과 topic은 표준 이름을 사용하고 publisher와 consumer의 topic 설정이 일치하는지 먼저 확인한다.
- 격리된 통합 재검증 wave마다 아직 존재하지 않는 consumer group을 하나만 만들고 `latest`를 한 번 적용한다. wave 안에서는 같은 group을 모든 lane이 재사용하며 lane마다 group을 새로 만들거나 offset을 초기화하지 않는다.
- 재검증 전후에 HEAD, health, 프로세스 cwd, Tool flags, 재시작 횟수, partition별 log-end와 lag를 기록하고 고정한다. 각 lane 전후에는 동일한 고정 preflight를 실행하고, 기준선과 달라진 값은 테스트 결과와 별도의 drift로 분류한다.
- 변경되지 않은 서비스·의존성은 재시작·재빌드·재설치하지 않는다. backend-only 변경이면 기존 AI image를 유지한다. worker를 내릴 때도 runtime 자체를 중지하지 않는다.
- AI image를 빌드하기 전 `docker system df`, `uname -m`, `docker info --format '{{.Architecture}}'`로 저장공간과 host/Docker 아키텍처를 확인한다. 공간 부족이나 host/image 아키텍처 불일치는 코드 결함과 분리하고, 대상이 확인된 미사용 build cache·image만 정리한다. 실행 중인 container와 공용 volume은 정리하지 않는다.
- 로컬 CPU 재검증에 불필요한 GPU/CUDA 산출물이 설치되기 시작하면 반복 설치하지 말고 requirements, base image, host/image 아키텍처가 맞는지 먼저 확인한다.
- 재검증 중 Kafka, DB, volume을 reset하지 않는다.
- 재검증 요청 중 상태를 바꾸는 public API에는 첫 요청부터 255자 이하의 짧은 `Idempotency-Key`를 넣는다.
- 여러 단계의 재검증 harness는 `#!/usr/bin/env bash`를 선언하고 Bash로 실행한다. zsh 일회성 명령에서는 읽기 전용 변수 `status`를 쓰지 않고 `http_status` 또는 `state`를 사용한다.
- curl 증거를 `{status, body}`로 감쌌다면 HTTP 코드는 `.status`, 비동기 업무 상태는 `.body.status`로 판정한다. operation log 목록은 응답의 `.logs` 배열을 읽는다.
- Bash에서 JSON 기본값을 `${arg:-{}}`처럼 중괄호가 겹치는 매개변수 확장으로 만들지 않는다. 빈 값은 별도 문장으로 `'{}'`를 대입하고, 요청 전 `jq -e .`로 payload를 검증한다.
- 이전 단계가 성공한 fixture, 응답, 비동기 checkpoint는 그대로 이어서 사용하고 lane의 모든 검증이 끝난 뒤 한 번만 정리한다. harness 오류만 고친 경우 ingest·서비스 기동·의존성 설치부터 다시 시작하지 않는다.
- fixture 정리는 public API로 수행한다. 문서 삭제 요청에는 직전 조회에서 확인한 현재 `base_version`을 넣고, 이미 정리된 fixture 때문에 앞 단계 전체를 다시 실행하지 않는다.

통합 재검증용 backend를 처음 띄우는 runner 터미널에서 group을 한 번만 지정한다. 이미
backend가 실행 중이면 `back-up.sh`가 그대로 반환하므로, 실행 중간에 group을 바꾸지 않는다.

```sh
export AI_TASK_RESULT_CONSUMER_GROUP="document-svc-ai-task-result-$(git rev-parse --short HEAD)-$(date +%Y%m%d%H%M%S)"
./scripts/back-up.sh
```

AI image를 처음 만들거나 AI 코드·의존성이 바뀐 경우에만 위 compose 명령의 `up -d`에
`--build`를 추가한다.
기존 image로 재검증할 때는 `--build` 없이 `up -d`를 사용한다.

### 3-3-1. 기존 AI 데이터 maintenance cutover

신규 빈 환경에는 필요 없다. 기존 `core_db`의 Wiki·Agent·Skill·checkpoint를 옮길 때는 먼저 외부 요청을 차단하고 `pipeline-api`를 내려 lint/restore/reingest/Agent mutation을 막는다. Wiki와 Agent 실행이 모두 terminal 상태가 된 뒤 관련 worker를 내린다.

```sh
docker compose --env-file infra/.env \
  -f infra/compose.infra.yml -f infra/compose.ai.yml \
  stop pipeline-api agent-task-worker pipeline-agent-worker
docker compose --env-file infra/.env -f infra/compose.infra.yml \
  exec -T postgresql sh -c \
  'PGPASSWORD="$CORE_DB_MIGRATION_PASSWORD" exec psql -U "$CORE_DB_MIGRATION_USER" -d "$CORE_DB_NAME" -At' <<'SQL'
select count(*) from pipeline_runs where status not in ('succeeded','failed','notify_pending');
select count(*) from agent_runs where status not in ('completed','partial_failed','failed','conflicted','rejected','cancelled');
SQL
docker compose --env-file infra/.env \
  -f infra/compose.infra.yml -f infra/compose.ai.yml \
  stop ingest-worker
```

두 결과가 0인지 확인하고 core/ai DB snapshot 식별자를 기록한다. 먼저 두 runtime role의 core Wiki·Agent·Skill·checkpoint DML을 차단한다. `copy`는 active run 0건과 이 권한 차단 상태를 다시 검증한 뒤, 하나의 `REPEATABLE READ READ ONLY` source transaction에서 ID 보존 stream copy와 row count·PK·canonical content hash·고아 참조 검증을 수행한다.

```sh
services/ai/pipeline/.venv/bin/python services/ai/pipeline/wiki_db_cutover.py lock-core-writes
services/ai/pipeline/.venv/bin/python services/ai/pipeline/wiki_db_cutover.py copy \
  --writes-stopped \
  --core-snapshot-id '<core snapshot ID>' \
  --ai-snapshot-id '<ai snapshot ID>'
```

`copy` 또는 row count·PK·hash·고아 참조 검증이 실패하면 연결을 전환하지 말고 즉시 write fence를 복구한다. 실패한 target transaction은 rollback되므로 ai_db의 부분 복사본을 덮어쓰지 않는다. rollback 명령은 `core_runtime`과 `ai_runtime`의 source table·sequence 권한을 복구하고 두 role의 실제 write를 검증한다.

```sh
services/ai/pipeline/.venv/bin/python services/ai/pipeline/wiki_db_cutover.py rollback-core-permissions
```

외부 요청 차단은 유지한 채 새 이미지의 `pipeline-api`만 올린다.

```sh
docker compose --env-file infra/.env \
  -f infra/compose.infra.yml -f infra/compose.ai.yml \
  up -d --build pipeline-api
```

내부 pipeline API로 ingest/query/lint/restore/agent smoke test를 모두 실행하고, 다섯 기능이 성공한 경우에만 `ai_runtime`의 core 권한을 완전히 회수하고 core source table을 read-only로 유지한 뒤 worker를 재개한다.

```sh
services/ai/pipeline/.venv/bin/python services/ai/pipeline/wiki_db_cutover.py \
  finalize-core-permissions --smoke-tested ingest query lint restore agent
docker compose --env-file infra/.env \
  -f infra/compose.infra.yml -f infra/compose.ai.yml \
  start ingest-worker
```

smoke test가 실패하면 worker를 재개하지 않는다. 구버전 이미지와 core DB 연결로 되돌린 뒤 다음 명령으로 core source write 권한을 복구한다.

```sh
services/ai/pipeline/.venv/bin/python services/ai/pipeline/wiki_db_cutover.py rollback-core-permissions
```

문서 편집 저장소는 V39가 비어 있는 `document_edit_states`와 `document_content_versions`에 편집 revision을 초기화하는 fresh PostgreSQL cutover다. 기존 Mongo 편집 데이터와 두 PostgreSQL table의 폐기는 대상별 승인 후 수행하며, 기존 편집 상태·write receipt·pending edit event를 import하거나 dual-write하지 않는다. 초기화된 편집 상태의 본문·revision·receipt·content version·asset/reference·적용 감사·outbox를 하나의 core DB transaction으로 기록한다. 결정 근거: [adr/0016](adr/0016-consolidate-document-body-into-postgres.md).

### 3-4. 프론트엔드 (:3000)

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

### 3-5. 로그 확인

`dev-up.sh`는 로그를 `logs/`에 남긴다. 컨테이너 로그는 `dev-down.sh`로 컨테이너를 지우면 함께 사라지므로, 재기동 뒤에도 이전 에러를 보려면 이 파일을 본다. `logs/`는 gitignore 대상이다.

```
logs/workers.log        워커 6개 + pipeline-api (서비스명 접두어로 구분)
logs/document-svc.log   backend
logs/access-svc.log     backend
logs/frontend.log       frontend
```

```sh
tail -f logs/*.log                      # 흐름 실시간 확인
grep -iE "error|exception" logs/*.log   # 에러만 확인
```

워커 로그 수집만 따로 제어하려면 `./scripts/logs-up.sh [start|stop|status]`를 쓴다. 수집을 시작할 때 `workers.log`가 100MB를 넘었으면 `workers.log.1`로 밀고 새로 쌓는다(수집 중에는 회전하지 않는다).

### 3-6. 모니터링 (선택)

백엔드 지표를 그래프로 보는 스택이다. 업무 기능과 무관하므로 `dev-up.sh`는 띄우지 않는다.

```sh
docker compose -f infra/compose.monitoring.yml up -d
```

| 대상 | 주소 | 비고 |
|---|---|---|
| Prometheus | http://localhost:9090 | 15초마다 백엔드 관리 포트와 kafka-exporter를 긁는다 |
| Grafana | http://localhost:3001 | 초기 계정 `admin` / `admin` |
| kafka-exporter | (내부 전용 :9308) | 브로커에 직접 물어 consumer group별 lag을 낸다 |
| pipeline-api | http://localhost:8000/metrics | FastAPI 요청 지표. Prometheus는 infra 네트워크에서 컨테이너 이름으로 긁는다 |

kafka-exporter는 `compose.infra.yml`이 만드는 네트워크(`fruition-mvp-dev_default`)에 붙으므로 **인프라가 먼저 떠 있어야 한다**. Kafka의 EXTERNAL 리스너는 `localhost:9092`로 광고돼 컨테이너에서 쓸 수 없어 INTERNAL(`kafka:19092`)로 접속한다.

동작 원리는 세 단계다. 백엔드가 `/actuator/prometheus`에 지표를 텍스트로 내걸고, Prometheus가 주기적으로 긁어 시계열로 쌓고, Grafana가 그것을 조회해 그린다. 세 프로세스는 HTTP로만 연결돼 있어 서로를 모른다.

확인 순서.

1. http://localhost:9090/targets — `document-svc`와 `access-svc`가 모두 UP이어야 한다. DOWN이면 백엔드가 떠 있는지, 관리 포트(8082·8083)가 열렸는지 본다.
2. Grafana 접속 → Dashboards → **Fruition 운영**. 데이터소스와 대시보드 모두 기동 시 자동 등록되므로 import 절차가 없다.

#### 무엇을 보는가

`Fruition 운영`은 장애 조사 1차 화면이다. Google SRE의 Four Golden Signals를 인과 순서대로 배치했으므로
왼쪽 위에서 시작해 시계 방향으로 읽는다.

| 순서 | 패널 | 정상 | 이상 신호 |
|---|---|---|---|
| ① | **Traffic** | 평소 수준 | 다른 세 신호를 해석하는 기준선이다. 이것 없이는 지연·에러가 유입 증가 탓인지 코드 탓인지 구분할 수 없다 |
| ② | **Saturation** | lag 0(또는 올랐다 복귀), DB 사용률 0.5 이하 | lag이 안 내려오면 워커 정지·반복 실패. 점선은 KEDA `lagThreshold`(5). DB 사용률이 1.0에 붙으면 풀(기본 10개)이 마른 것으로, API 지연의 원인이 DB가 아니라 풀인 경우다 |
| ③ | **Latency** | 배포 전과 비슷 | p95·p99가 평소의 3~5배. 트래픽이 없으면 선이 끊기는데 정상이다(rate 분모가 0) |
| ④ | **Errors** | 0 | 건수가 아니라 **비율**이다. 4xx는 뺀다 — 미로그인 401은 정상 동작이라 신호가 되지 않는다 |

네 신호 아래에는 드릴다운 패널이 하나 더 있다. **partition별 lag**은 ②에서 특정 consumer group이
밀릴 때 어느 partition인지 좁힌다. 메시지 key가 `document_id`라 partition은 문서 단위로 묶이므로,
한 partition만 쌓여 있으면 그 partition에 걸린 특정 문서가 반복 실패하는 것이고, 전 partition이
고르게 쌓이면 처리량 부족이다. 상단 `Consumer group` 드롭다운으로 대상을 바꾼다.

그래프의 세로선은 **프로세스 재시작(배포) 시각**이다. 배포 전후로 지표가 어떻게 달라졌는지 눈으로
맞출 수 있다. `process_start_time_seconds`로 감지하며, 재시작 후 5분 이내 구간을 표시하므로
실제 시각과 최대 5분 차이가 날 수 있다.

④에는 한계가 있다. 여기 잡히는 것은 프로토콜 수준의 **명시적 실패**뿐이다. AI 처리는 202로 즉시 응답한 뒤
비동기로 실패할 수 있어, 이 그래프가 평평해도 사용자는 결과를 못 받을 수 있다. 그런 **묵시적 실패**는 현재
ERROR 로그로만 간접 관측된다. 처리 실패율을 직접 세려면 워커에 도메인 지표를 심어야 한다.

여기서 이상이 잡히면 JVM 내부를 본다. Grafana → Dashboards → New → Import → ID `4701`(JVM Micrometer) → 데이터소스 `Prometheus`. 힙·GC·스레드를 `application` 단위로 보여준다. 다만 이 대시보드의 Heap used(%)는 로컬에서 의미가 없다 — `-Xmx`를 주지 않아 max heap이 12GB로 잡히므로 사용률이 항상 1%대다. 비율 대신 `JVM Heap` 패널의 톱니 모양을 본다. `Utilisation` 패널도 비어 있는데, Tomcat 스레드 지표에 `server.tomcat.mbeanregistry.enabled=true`가 필요하기 때문이다. 지금 규모에서는 DB 풀이 훨씬 먼저 막히므로 켜지 않았다.

`prometheus.yml`을 고쳤다면 Prometheus를 재시작해야 반영된다(`docker restart fruition-prometheus`). 대시보드 JSON은 30초마다 자동으로 다시 읽으므로 재시작이 필요 없다.

단, `git rebase`나 브랜치 전환처럼 디렉터리를 지웠다 다시 만드는 작업 뒤에는 bind mount가 옛 inode를
가리켜 컨테이너에서 파일이 보이지 않는다. 대시보드 수정이 반영되지 않으면 이것부터 의심한다.

```sh
docker exec fruition-grafana ls /var/lib/grafana/dashboards/   # 비어 있으면 마운트가 끊긴 것
docker compose -f infra/compose.monitoring.yml up -d --force-recreate grafana
```

데이터소스·대시보드는 기동 시 자동 등록된다(`infra/monitoring/grafana/provisioning/`, `infra/monitoring/grafana/dashboards/`). 대시보드는 파일이 단일 소스라 UI에서 고쳐도 저장되지 않는다 — JSON을 고쳐 커밋한다. 스크레이프 대상은 `infra/monitoring/prometheus.yml`에 있고, 호스트에서 bootRun으로 도는 백엔드를 가리킨다. `compose.containerized.yml`로 백엔드를 컨테이너로 띄웠다면 대상 주소를 바꿔야 한다.

종료는 다음과 같다. 볼륨을 지우지 않으면 수집한 지표와 대시보드가 남는다.

```sh
docker compose -f infra/compose.monitoring.yml down
```

## 4. 데모 시나리오

1. 로그인 — `http://localhost:3000` 접속, 이메일 가입/로그인. 인증 코드는 `infra/.env`의 `AUTH_EMAIL_DEV_FIXED_CODE` 값 입력. (OAuth 키 설정 시 소셜 로그인도 가능)
2. 워크스페이스 — 워크스페이스 생성 후 진입.
3. 문서 업로드 — PDF 업로드 → converter가 Markdown 변환 → pipeline 워커가 처리. 상태가 `processing`에서 완료로 바뀌는지 확인. 멈춰 있으면 `:8000/health`와 선택 provider secret key를 확인.
4. 문서 편집 — 문서를 열어 내용 수정. 편집 이벤트가 Kafka(`document.edit.event`)로 흘러 파생 상태가 갱신됨.
5. AI 질의 — 비동기 Query run/SSE가 완료되고 업로드 문서 기반 응답·원문 링크가 저장되는지 확인.
6. Agent/Lint/Restore — 요청이 즉시 202를 반환하고 각 run 완료 후에만 결과가 반영되는지 확인.
7. 병렬 ingest — 같은 workspace의 서로 다른 문서를 동시에 올려 병렬 처리되고 동일 slug Concept가 하나만 남는지 확인.

## 5. 종료·초기화

앱 프로세스와 인프라·pipeline 컨테이너 일괄 종료.

```sh
./scripts/dev-down.sh
```

converter 종료.

```sh
docker compose -f infra/compose.converter.yml down
```

모니터링 종료.

```sh
docker compose -f infra/compose.monitoring.yml down
```

데이터까지 초기화(DB·MinIO·pipeline 산출물과 이 project의 orphan 볼륨 삭제, 재현 환경 초기화 시에만).

```sh
./scripts/dev-down.sh --volumes
```

개별 종료 스크립트: `scripts/front-down.sh`, `scripts/back-down.sh`, `scripts/ai-down.sh`.
호스트 앱 종료 스크립트는 `.runtime/`에 등록된 supervisor만 종료한다. 다른 프로젝트가 같은 포트를 사용 중이면 종료하지 않는다.
`scripts/ai-down.sh`는 pipeline-api와 전체 워커를 함께 종료한다.
