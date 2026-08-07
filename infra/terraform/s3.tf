# MinIO 대체. 문서 원본·snapshot·AI 파일 저장 (§8.5).
# 앱은 S3_ENDPOINT + 정적 키 방식(MinIO 호환 client)이라 IAM user 키를 발급한다.
# IRSA(Pod Identity) 전환은 앱 코드가 default credential chain을 지원할 때 후속으로 진행.
resource "random_id" "bucket" {
  byte_length = 4
}

resource "aws_s3_bucket" "storage" {
  bucket = "${var.project}-storage-${random_id.bucket.hex}"
}

resource "aws_s3_bucket_versioning" "storage" {
  bucket = aws_s3_bucket.storage.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "storage" {
  bucket                  = aws_s3_bucket.storage.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 임시 파일 lifecycle (tmp/ prefix 7일 후 삭제)
resource "aws_s3_bucket_lifecycle_configuration" "storage" {
  bucket = aws_s3_bucket.storage.id

  rule {
    id     = "expire-tmp"
    status = "Enabled"
    filter {
      prefix = "tmp/"
    }
    expiration {
      days = 7
    }
  }
}

resource "aws_iam_user" "app" {
  name = "${var.project}-app"
}

resource "aws_iam_user_policy" "app_s3" {
  name = "${var.project}-app-s3"
  user = aws_iam_user.app.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [aws_s3_bucket.storage.arn]
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = ["${aws_s3_bucket.storage.arn}/*"]
      }
    ]
  })
}

resource "aws_iam_access_key" "app" {
  user = aws_iam_user.app.name
}
