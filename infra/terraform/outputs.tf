output "cluster_name" {
  value = module.eks.cluster_name
}

output "ecr_repository_urls" {
  value = { for k, r in aws_ecr_repository.services : k => r.repository_url }
}

output "access_rds_endpoint" {
  description = "access-svc deployment env POSTGRES_HOST에 넣을 값"
  value       = aws_db_instance.access.address
}

output "core_rds_endpoint" {
  description = "document-svc deployment·pipeline env POSTGRES_HOST에 넣을 값"
  value       = aws_db_instance.core.address
}

output "redis_endpoint" {
  description = "k8s/overlays/aws configmap의 REDIS_HOST에 넣을 값"
  value       = aws_elasticache_cluster.main.cache_nodes[0].address
}

output "s3_bucket" {
  description = "k8s/overlays/aws configmap의 S3_BUCKET에 넣을 값"
  value       = aws_s3_bucket.storage.bucket
}

output "app_secret_arn" {
  value = aws_secretsmanager_secret.app.arn
}

output "github_deploy_role_arn" {
  description = "GitHub repo variable AWS_DEPLOY_ROLE_ARN에 넣을 값"
  value       = aws_iam_role.github_deploy.arn
}

output "irsa_role_arns" {
  description = "helm addon 설치 시 serviceAccount annotation에 넣을 role ARN"
  value = {
    alb_controller     = module.alb_controller_irsa.iam_role_arn
    external_secrets   = module.external_secrets_irsa.iam_role_arn
    cluster_autoscaler = module.cluster_autoscaler_irsa.iam_role_arn
  }
}
