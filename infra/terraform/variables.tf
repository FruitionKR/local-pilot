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
  default     = "mireutale/local-pilot"
}

variable "budget_email" {
  description = "AWS Budget 알림 수신 이메일. 빈 값이면 budget을 만들지 않는다."
  type        = string
  default     = ""
}
