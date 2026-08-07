from __future__ import annotations

import hashlib
import json
import os
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from typing import Any

from app.modules.wiki_ingestion.application.ports import (
    PipelineResultNotifierPort,
)
from app.modules.wiki_ingestion.infrastructure.object_storage import (
    read_text_object,
    write_text_object,
)


class PipelineResultCallbackError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code


def resolve_internal_callback_token() -> str:
    """Backend가 콜백을 검증하는 내부 토큰. 없으면 콜백이 401로 버려지므로 기동을 막는다."""
    token = os.environ.get("INTERNAL_CALLBACK_TOKEN", "").strip()
    if not token:
        raise RuntimeError("Set INTERNAL_CALLBACK_TOKEN for pipeline result callbacks.")
    return token


class HttpPipelineResultNotifier(PipelineResultNotifierPort):
    def __init__(self, token: str | None = None) -> None:
        self._token = token or resolve_internal_callback_token()

    def notify(
        self,
        callback_url: str,
        payload: dict[str, Any],
    ) -> None:
        post_pipeline_result(
            callback_url,
            payload,
            token=self._token,
            rewrite_artifacts=_rewrite_operation_artifacts,
        )


def post_pipeline_result(
    callback_url: str,
    payload: dict[str, Any],
    *,
    token: str,
    urlopen: Callable[..., Any] = urllib.request.urlopen,
    sleep: Callable[[float], None] = time.sleep,
    max_attempts: int = 5,
    timeout_seconds: int = 5,
    rewrite_artifacts: Callable[[dict[str, Any]], None] | None = None,
) -> None:
    for attempt in range(1, max_attempts + 1):
        body = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
        ).encode("utf-8")
        request = urllib.request.Request(
            callback_url,
            data=body,
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "X-Internal-Token": token,
            },
            method="POST",
        )
        try:
            with urlopen(request, timeout=timeout_seconds):
                return
        except urllib.error.HTTPError as exc:
            if exc.code == 422 and attempt < max_attempts:
                if rewrite_artifacts is None:
                    raise PipelineResultCallbackError(
                        "pipeline result callback cannot repair HTTP 422",
                        status_code=exc.code,
                    ) from exc
                rewrite_artifacts(payload)
            elif exc.code < 500 or attempt == max_attempts:
                raise PipelineResultCallbackError(
                    f"pipeline result callback failed with HTTP {exc.code}",
                    status_code=exc.code,
                ) from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            if attempt == max_attempts:
                raise PipelineResultCallbackError(
                    "pipeline result callback failed after retries"
                ) from exc
        sleep(float(2 ** (attempt - 1)))


def _rewrite_operation_artifacts(payload: dict[str, Any]) -> None:
    workspace_id = str(payload.get("workspace_id") or "")
    operation_id = str(payload.get("operation_id") or "")
    for page in payload.get("changed_pages", []):
        if not isinstance(page, dict):
            continue
        page_id = str(page.get("page_id") or "")
        prefix = f"wiki/{workspace_id}/pages/{page_id}/ops/{operation_id}"
        markdown = read_text_object(str(page["markdown_key"]))
        markdown_key = f"{prefix}.md"
        write_text_object(
            markdown_key,
            markdown,
            "text/markdown; charset=utf-8",
        )
        page["markdown_key"] = markdown_key
        page["content_hash"] = "sha256:" + hashlib.sha256(
            markdown.encode("utf-8")
        ).hexdigest()
        contribution_key = page.get("contribution_key")
        if contribution_key:
            contribution = read_text_object(str(contribution_key))
            rewritten_key = f"{prefix}.json"
            write_text_object(
                rewritten_key,
                contribution,
                "application/json; charset=utf-8",
            )
            page["contribution_key"] = rewritten_key
