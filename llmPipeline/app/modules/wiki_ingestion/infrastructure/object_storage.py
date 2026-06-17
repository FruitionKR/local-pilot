from __future__ import annotations

from io import BytesIO
import os
from urllib.parse import urlparse

from minio import Minio


def _endpoint() -> str:
    endpoint = os.environ.get("S3_ENDPOINT") or "http://localhost:9000"
    parsed = urlparse(endpoint)
    return parsed.netloc or parsed.path


def _secure() -> bool:
    endpoint = os.environ.get("S3_ENDPOINT") or "http://localhost:9000"
    return endpoint.startswith("https://")


def bucket_name() -> str:
    return os.environ.get("S3_BUCKET") or "fruition-storage"


def client() -> Minio:
    return Minio(
        _endpoint(),
        access_key=os.environ.get("S3_ACCESS_KEY") or "fruition",
        secret_key=os.environ.get("S3_SECRET_KEY") or "fruition_dev_secret",
        secure=_secure(),
    )


def split_storage_uri(uri: str) -> tuple[str, str]:
    if uri.startswith("s3://"):
        parsed = urlparse(uri)
        return parsed.netloc, parsed.path.lstrip("/")
    return bucket_name(), uri.lstrip("/")


def storage_uri(object_name: str, bucket: str | None = None) -> str:
    return f"s3://{bucket or bucket_name()}/{object_name.lstrip('/')}"


def read_text_object(uri: str) -> str:
    bucket, object_name = split_storage_uri(uri)
    response = client().get_object(bucket, object_name)
    try:
        return response.read().decode("utf-8")
    finally:
        response.close()
        response.release_conn()


def write_text_object(
    object_name: str,
    text: str,
    content_type: str = "text/markdown; charset=utf-8",
) -> str:
    bucket, key = split_storage_uri(object_name)
    data = text.encode("utf-8")
    minio_client = client()
    if not minio_client.bucket_exists(bucket):
        minio_client.make_bucket(bucket)
    minio_client.put_object(
        bucket,
        key,
        BytesIO(data),
        length=len(data),
        content_type=content_type,
    )
    return storage_uri(key, bucket)
