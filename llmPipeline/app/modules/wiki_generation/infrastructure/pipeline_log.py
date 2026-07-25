from __future__ import annotations

import json
import urllib.error
import urllib.request
from collections.abc import Callable
from datetime import datetime
from pathlib import Path
from typing import Any

from app.core.pipeline_control import PipelineRunCancelledError
from app.modules.wiki_ingestion.infrastructure.file_io import append_text


class PipelineLog:
    def __init__(
        self,
        path: str | Path,
        callback_url: str | None = None,
        run_id: str | None = None,
        progress_callback: Callable[[], bool | None] | None = None,
    ) -> None:
        self.path = Path(path)
        self.callback_url = callback_url
        self.run_id = run_id
        self.progress_callback = progress_callback
        if self.path.exists():
            self.path.unlink()

    def emit(self, stage: str, message: str, data: dict[str, Any] | None = None) -> None:
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        event = {
            "run_id": self.run_id,
            "timestamp": now,
            "stage": stage,
            "message": message,
            "data": {key: str(value) for key, value in (data or {}).items()},
        }
        lines = [f"[{now}] [{stage}] {message}"]
        for key, value in event["data"].items():
            lines.append(f"  - {key}: {value}")
        append_text(self.path, "\n".join(lines) + "\n")
        if self.progress_callback:
            self._report_progress(event["timestamp"])
        if self.callback_url:
            self._post_event(event)

    def _report_progress(self, timestamp: str) -> None:
        try:
            should_continue = self.progress_callback()
            if should_continue is False:
                raise PipelineRunCancelledError(
                    "Pipeline run cancelled because its document or workspace is inactive."
                )
        except PipelineRunCancelledError:
            raise
        except Exception as exc:
            append_text(self.path, f"[{timestamp}] [heartbeat 갱신 실패] {exc}\n")

    def _post_event(self, event: dict[str, Any]) -> None:
        body = json.dumps(event, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.callback_url,
            data=body,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=5):
                pass
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            append_text(self.path, f"[{event['timestamp']}] [로그 전송 실패] {exc}\n")
