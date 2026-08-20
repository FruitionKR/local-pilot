# 소규모 사용자 피드백 profile (docs/Fruition_AWS_MSA_Architecture.md §8) 기준 IaC.
terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # 원격 state는 팀 결정 후 활성화 (S3 + DynamoDB lock)
  # backend "s3" {}
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "terraform"
    }
  }
}
