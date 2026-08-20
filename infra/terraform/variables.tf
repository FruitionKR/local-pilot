variable "project" {
  description = "리소스 이름 접두사"
  type        = string
  default     = "fruition"
}

variable "region" {
  description = "AWS region"
  type        = string
  default     = "ap-northeast-2"
}

variable "vpc_cidr" {
  description = "VPC CIDR"
  type        = string
  default     = "10.0.0.0/16"
}

variable "eks_version" {
  description = "EKS Kubernetes 버전"
  type        = string
  default     = "1.31"
}

variable "github_repo" {
  description = "GitHub Actions OIDC를 허용할 repo (owner/name)"
  type        = string
  default     = "FruitionKR/local-pilot"
}

variable "github_deploy_ref" {
  description = "GitHub Actions OIDC 배포를 허용할 Git ref"
  type        = string
  default     = "refs/heads/dev-msa"
}

variable "budget_email" {
  description = "AWS Budget 알림 수신 이메일. 빈 값이면 budget을 만들지 않는다."
  type        = string
  default     = ""
}

variable "eks_public_access_cidrs" {
  description = "EKS API 서버 public endpoint 허용 CIDR. 기본은 전체 개방(GitHub Actions hosted runner IP가 고정되지 않아서) — 운영 전환 시 사무실/VPN 대역으로 제한할 것."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}
