# AWS 전환 Terraform

`docs/backlog/Fruition_AWS_MSA_Architecture.md` §8 소규모 사용자 피드백 profile의 IaC.
Kafka는 MSK가 아니라 EKS 안 Strimzi(§8.3)를 그대로 쓴다 — `k8s/base/kafka.yaml`.

## 만드는 것

| 파일 | 리소스 |
|---|---|
| `vpc.tf` | VPC, 2 AZ, public 2(ALB)·private 2(EKS·RDS·Redis), NAT 1개 |
| `eks.tf` | EKS + General node(2/2/3, t3.large, On-Demand) + AI Worker(0/0/2, Spot, taint) + IRSA 4종 |
| `rds.tf` | RDS PostgreSQL 16 **2대** (access/core, db.t4g.small, Single-AZ, backup 7일) |
| `elasticache.tf` | Redis 7, cache.t4g.micro, single node |
| `s3.tf` | 저장 버킷(versioning + tmp/ lifecycle) + 앱용 IAM user 정적 키 |
| `ecr.tf` | document-svc·access-svc·pipeline·converter 레포 |
| `github-oidc.tf` | GitHub Actions OIDC deploy role (ECR push + EKS 배포) |
| `secrets.tf` | Secrets Manager `fruition/app` (DB URL·S3 키 자동, 나머지 CHANGE_ME) |
| `budgets.tf` | 월 USD 500·700 알림 (`budget_email` 설정 시) |

## 적용 절차

```bash
cd infra/terraform
terraform init
terraform plan -var budget_email=<알림 이메일>
terraform apply -var budget_email=<알림 이메일>
```

apply 후 수동 단계:

0. **DB 계정·database 생성** — EKS 내부(또는 bastion)에서 각 RDS endpoint에
   `infra/postgres/init-db-isolation.sh`를 실행해 access_db/core_db/ai_db와
   runtime/migration 계정을 만든다. 비밀번호는 Secrets Manager `fruition/app`의
   `*_DB_*_PASSWORD` 값과 동일하게 넣을 것 (access 인스턴스는 access_db만,
   core 인스턴스는 core_db·ai_db만 실제 사용 — 나머지는 무해).
   MongoDB는 Atlas 클러스터 생성 후 `DOCUMENT_MONGODB_URI` 교체.
1. **Secrets Manager 값 채우기** — `fruition/app`의 `JWT_SECRET`, `INTERNAL_CALLBACK_TOKEN`,
   `AGENT_INTERNAL_TOKEN`, 선택 provider의 `OPENAI_API_KEY`·`GEMINI_API_KEY`·`ANTHROPIC_API_KEY` (live 호출 시 필요).
2. **ACM 인증서** — `api.<도메인>`, `access.<도메인>` 포함 인증서 발급, ARN을 overlay ingress에 기입.
3. **Route 53** — ALB 생성 후 두 호스트 A(alias) 레코드 연결.
4. **GitHub repo Variables** — `AWS_DEPLOY_ROLE_ARN` = `terraform output github_deploy_role_arn`.
5. **Vercel env** — `NEXT_PUBLIC_BACKEND_URL=https://api.<도메인>`,
   `NEXT_PUBLIC_ACCESS_URL=https://access.<도메인>`.

## 클러스터 addon (helm — Terraform 범위 밖)

kubeconfig 발급(`aws eks update-kubeconfig --name fruition-eks`) 후 순서대로:

```bash
# 1. AWS Load Balancer Controller (Ingress → ALB)
helm repo add eks https://aws.github.io/eks-charts
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=fruition-eks \
  --set serviceAccount.name=aws-load-balancer-controller \
  --set "serviceAccount.annotations.eks\.amazonaws\.com/role-arn=$(terraform output -json irsa_role_arns | jq -r .alb_controller)"

# 2. External Secrets Operator (Secrets Manager → k8s Secret)
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets \
  -n external-secrets --create-namespace \
  --set "serviceAccount.annotations.eks\.amazonaws\.com/role-arn=$(terraform output -json irsa_role_arns | jq -r .external_secrets)"

# 3. Cluster Autoscaler (AI Worker node 0→N)
helm repo add autoscaler https://kubernetes.github.io/autoscaler
helm install cluster-autoscaler autoscaler/cluster-autoscaler \
  -n kube-system \
  --set autoDiscovery.clusterName=fruition-eks \
  --set awsRegion=ap-northeast-2 \
  --set rbac.serviceAccount.name=cluster-autoscaler \
  --set "rbac.serviceAccount.annotations.eks\.amazonaws\.com/role-arn=$(terraform output -json irsa_role_arns | jq -r .cluster_autoscaler)"

# 4. Strimzi + KEDA — k8s/README.md 3번 절차와 동일 (namespace는 fruition)
```

addon 완료 후 앱 배포는 `k8s/overlays/aws/README.md` 참조.

## 한계 (의도된 것)

- RDS Single-AZ·Redis single node·Kafka broker 1 — Production HA 아님 (§8 profile).
- Access/Core 물리 DB 분할 미적용 — 단일 RDS instance, 후속 단계.
- S3 접근이 IAM user 정적 키 — 앱이 endpoint+키 방식(MinIO 호환)이라 IRSA 전환은 코드 수정 후.
- 원격 state(backend "s3") 미설정 — 팀 결정 후 활성화.
