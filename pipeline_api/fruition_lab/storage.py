from __future__ import annotations

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


def read_text_object(uri: str) -> str:
    bucket, object_name = split_storage_uri(uri)
    response = client().get_object(bucket, object_name)
    try:
        return response.read().decode("utf-8")
    finally:
        response.close()
        response.release_conn()
