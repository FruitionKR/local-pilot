# AWS(EKS) overlay

`k8s/base`(앱 + Strimzi Kafka)를 그대로 쓰고 상태 계층만 관리형으로 바꾼다:
postgres → RDS, redis → ElastiCache, minio → S3, secret.yaml → Secrets Manager(ExternalSecret).

전제: `infra/terraform` apply + addon(helm) 설치 완료 — `infra/terraform/README.md` 참조.

## REPLACE_ME 치환

| 위치 | 값 | 출처 |
|---|---|---|
| `kustomization.yaml` images | `REPLACE_ME_ACCOUNT_ID` | AWS 계정 ID (`terraform output ecr_repository_urls`) |
| `configmap-aws.yaml` | `REPLACE_ME_RDS_ENDPOINT` | `terraform output rds_endpoint` |
| `configmap-aws.yaml` | `REPLACE_ME_REDIS_ENDPOINT` | `terraform output redis_endpoint` |
| `configmap-aws.yaml` | `REPLACE_ME_S3_BUCKET` | `terraform output s3_bucket` |
| `configmap-aws.yaml` | `REPLACE_ME_APP_DOMAIN` | Vercel production 도메인 |
| `ingress.yaml` | `REPLACE_ME_ACM_CERT_ARN` | ACM 인증서 ARN |
| `ingress.yaml` | `REPLACE_ME_DOMAIN` | API 도메인 (api.·access. 붙는 zone) |

## 배포

수동 검증:

```bash
kubectl kustomize k8s/overlays/aws   # 렌더 확인
kubectl apply -k k8s/overlays/aws
kubectl -n fruition rollout status deploy --timeout=600s
```

평시 배포는 GitHub Actions `Deploy (EKS)` workflow(수동 트리거)가 이미지 태그 세팅까지 수행한다.

## 로컬(kind)과 차이

| 항목 | kind (base + -f 개별 적용) | AWS overlay |
|---|---|---|
| postgres·redis·minio | pod (`base/*.yaml`) | RDS·ElastiCache·S3 |
| secret | `base/secret.yaml` 평문 | ExternalSecret ← Secrets Manager `fruition/app` |
| 노출 | NodePort 30080/30081 | ALB Ingress (host 기반: api.→document, access.→access) |
| 이미지 | 로컬 빌드 + `imagePullPolicy: Never` | ECR + 커밋 SHA 태그 |
| Kafka | Strimzi broker 1 | 동일 (§8.3 — MSK 아님) |

## 알려진 제약

- `pipeline-runs` PVC가 RWO(EBS)라 ingest-worker에 podAffinity로 pipeline-api와 같은 노드 강제.
  S3 아티팩트 저장으로 이전하면 affinity 제거 + AI Worker(Spot) 노드 분리 활성화.
- AI Worker node group(Spot, taint `fruition.io/ai-worker`)은 위 이전 전까지 미사용(0대 유지).
- Strimzi broker 1대 — AZ 장애 시 중단 허용, 복구는 operation 재발행 절차(§8.3).
