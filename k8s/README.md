# Kubernetes (kind) 로컬 배포

전 서비스를 Kubernetes로 실행하기 위한 매니페스트. `docs/backlog/Fruition_AWS_MSA_Architecture.md`(EKS·Strimzi Kafka·KEDA) 방향의 로컬(kind) 검증판이다.
환경변수 계약은 `infra/docker-compose.deploy.yml`·`infra/docker-compose.pipeline.yml`의 environment 블록을 그대로 옮기고 호스트명만 k8s Service DNS로 바꿨다.

## 구성

| 파일 | 내용 |
|---|---|
| `kind/cluster.yaml` | kind 클러스터 (document-svc NodePort 30080, access-svc NodePort 30081 → host 동일 포트) |
| `base/namespace.yaml` | `fruition` namespace |
| `base/configmap.yaml` / `base/secret.yaml` | 공통 env (비밀은 Secret) |
| `base/postgres.yaml` / `base/redis.yaml` / `base/minio.yaml` | 상태 계층 (single replica + PVC, minio 버킷 init Job 포함). postgres는 admin 계정으로 기동 후 init ConfigMap 스크립트가 access_db/core_db/ai_db와 runtime/migration 계정을 생성 |
| `base/document-svc.yaml` | Spring Boot :8080 (기능 경로·flyway migration 소유), probe `/actuator/health`, Service NodePort 30080 |
| `base/access-svc.yaml` | Spring Boot :8081 (인증·OAuth·워크스페이스 CRUD), probe `/actuator/health`, Service NodePort 30081 |
| `base/pipeline-api.yaml` | FastAPI :8000, probe `/health`, ClusterIP + `pipeline-runs` PVC |
| `base/ingest-worker.yaml` | pipeline 이미지 + `python -m app.workers.ingest_worker` |
| `base/converter.yaml` | markitdown :8000, read-only rootfs, ClusterIP |
| `base/kafka.yaml` | Strimzi KafkaNodePool + Kafka CR (KRaft, broker 1) + KafkaTopic `ai.ingest.command` (partitions 12) |
| `base/keda-scaledobject.yaml` | ingest-worker lag 기반 1~4 스케일 (lagThreshold 5) |
| `base/networkpolicy.yaml` | pipeline-api·converter·ingest-worker는 namespace 내부 수신만 허용 |

## 사전 조건

- docker (colima), kind, kubectl
- colima 리소스 최소 6 CPU / 8GiB 권장: `colima stop && colima start --cpu 6 --memory 10`

## 기동 절차

```bash
cd <repo-root>

# 1. 이미지 빌드 (java 서비스는 빌드 컨텍스트가 services/ 루트)
docker build -t fruition-document-svc:dev -f services/backend/document-svc/Dockerfile services/backend
docker build -t fruition-access-svc:dev -f services/backend/access-svc/Dockerfile services/backend
docker build -t fruition-mvp-dev-pipeline-api:latest services/ai/pipeline
docker build -t fruition-mvp-dev-ingest-worker:latest services/ai/pipeline
docker build -t fruition-converter:latest services/ai/converter

# 2. 클러스터 생성
kind create cluster --name fruition --config k8s/kind/cluster.yaml

# 3. namespace + Strimzi operator + KEDA 설치
kubectl apply -f k8s/base/namespace.yaml
kubectl create -f 'https://strimzi.io/install/latest?namespace=fruition' -n fruition
kubectl apply --server-side -f https://github.com/kedacore/keda/releases/download/v2.20.2/keda-2.20.2.yaml
kubectl -n fruition wait deploy/strimzi-cluster-operator --for=condition=Available --timeout=600s
kubectl -n keda wait deploy --all --for=condition=Available --timeout=600s

# 4. Kafka 기동 (broker Ready까지 수 분 소요)
kubectl apply -f k8s/base/kafka.yaml
kubectl -n fruition wait kafka/kafka --for=condition=Ready --timeout=600s

# 5. 로컬 빌드 이미지 주입 (imagePullPolicy: Never)
#    postgres/redis/minio 등 공개 이미지는 kind가 Docker Hub에서 직접 pull한다.
#    (colima 환경에서는 pull된 공개 이미지의 kind load가 "content digest not found"로 실패할 수 있음)
kind load docker-image --name fruition \
  fruition-document-svc:dev \
  fruition-access-svc:dev \
  fruition-mvp-dev-pipeline-api:latest \
  fruition-mvp-dev-ingest-worker:latest \
  fruition-converter:latest

# 6. 앱 배포
kubectl apply -f k8s/base/configmap.yaml -f k8s/base/secret.yaml
kubectl apply -f k8s/base/postgres.yaml \
  -f k8s/base/redis.yaml -f k8s/base/minio.yaml
kubectl apply -f k8s/base/document-svc.yaml -f k8s/base/access-svc.yaml \
  -f k8s/base/pipeline-api.yaml -f k8s/base/ingest-worker.yaml \
  -f k8s/base/edit-event-consumer.yaml -f k8s/base/converter.yaml
kubectl apply -f k8s/base/networkpolicy.yaml -f k8s/base/keda-scaledobject.yaml

# 초기에는 postgres 기동 전 document-svc·access-svc·pipeline-api가 몇 차례 재시작(CrashLoop)할 수 있다 — 자가 복구됨
# (flyway migration은 document-svc 소유 — access-svc는 schema 준비 전까지 재시작될 수 있다)
kubectl -n fruition wait deploy --all --for=condition=Available --timeout=600s
```

## 검증

```bash
# document-svc / access-svc health (NodePort)
curl -s http://localhost:30080/actuator/health   # {"status":"UP"}
curl -s http://localhost:30081/actuator/health   # {"status":"UP"}

# pipeline-api 내부 인증 (클러스터 내부에서)
kubectl -n fruition run curl --rm -it --image=curlimages/curl --restart=Never -- \
  sh -c 'curl -s -o /dev/null -w "%{http_code}" http://pipeline-api:8000/query'   # 무토큰 401

# ingest-worker consumer group join
kubectl -n fruition logs deploy/ingest-worker | grep 'worker 기동'

# KEDA
kubectl -n fruition get scaledobject
```

## 접근 경로

- document-svc: `http://localhost:30080` (NodePort) 또는 `kubectl -n fruition port-forward svc/document-svc 8080:8080`
- access-svc: `http://localhost:30081` (NodePort) 또는 `kubectl -n fruition port-forward svc/access-svc 8081:8081`
- pipeline-api·converter: ClusterIP 전용 (필요 시 port-forward)

## 참고·한계

- DB 접속 env는 서비스별 분리 계약을 따른다: access-svc는 `ACCESS_DB_*`, document-svc는 `CORE_DB_*`, pipeline·ingest-worker는 `AI_DATABASE_URL`(ai_runtime@ai_db)을 쓴다. 이름류는 configmap, 비밀번호는 secret에 있다.
- `secret.yaml`은 compose dev 기본값과 동일한 로컬 개발용 값이다. 실제 LLM 호출은 선택 provider의 `OPENAI_API_KEY`·`GEMINI_API_KEY`·`ANTHROPIC_API_KEY` 중 해당 키를 덮어써야 한다(모델·base URL은 API/DB snapshot과 provider 고정값을 사용). 운영에서는 Secret 관리 도구로 대체할 것.
- `pipeline-runs` PVC는 RWO라 단일 노드에서만 pipeline-api·ingest-worker 공유가 가능하다. 멀티 노드 전환 시 S3 기반 아티팩트 저장으로 이전 필요.
- postgres·minio·kafka는 single replica 구성 — 로컬 검증용이며 EKS 전환 시 관리형(RDS/S3/MSK 또는 Strimzi HA)으로 대체한다.
- 클러스터 삭제: `kind delete cluster --name fruition`
