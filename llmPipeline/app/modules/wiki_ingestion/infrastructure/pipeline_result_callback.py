from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from typing import Any

from app.modules.wiki_ingestion.application.ports import (
    PipelineResultNotifierPort,
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


class HttpPipelineResultNotifier(PipelineResultNotifierPort):
    def notify(
        self,
        callback_url: str,
        payload: dict[str, Any],
    ) -> None:
        post_pipeline_result(callback_url, payload)


def post_pipeline_result(
    callback_url: str,
    payload: dict[str, Any],
    *,
    urlopen: Callable[..., Any] = urllib.request.urlopen,
    sleep: Callable[[float], None] = time.sleep,
    max_attempts: int = 5,
    timeout_seconds: int = 5,
) -> None:
    body = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
    ).encode("utf-8")
    for attempt in range(1, max_attempts + 1):
        request = urllib.request.Request(
            callback_url,
            data=body,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=timeout_seconds):
                return
        except urllib.error.HTTPError as exc:
            if exc.code < 500 or attempt == max_attempts:
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
