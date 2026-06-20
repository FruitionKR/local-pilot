from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from datetime import datetime, timezone

from app.modules.query.application.ports import QueryEventPublisherPort


class NoOpQueryEventPublisher(QueryEventPublisherPort):
    def publish(self, stage: str, message: str, data: dict[str, object] | None = None) -> None:
        return None


class HttpQueryEventPublisher(QueryEventPublisherPort):
    def __init__(self, callback_url: str, request_id: str | None = None, timeout_seconds: int = 5) -> None:
        self._callback_url = callback_url
        self._request_id = request_id
        self._timeout_seconds = timeout_seconds
        self._sequence = 0

    def publish(self, stage: str, message: str, data: dict[str, object] | None = None) -> None:
        self._sequence += 1
        body = json.dumps(
            {
                "request_id": self._request_id,
                "event_type": "query.log",
                "stage": stage,
                "message": message,
                "sequence": self._sequence,
                "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                "data": data or {},
            },
            ensure_ascii=False,
        ).encode("utf-8")
        request = urllib.request.Request(
            self._callback_url,
            data=body,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self._timeout_seconds):
                pass
        except (urllib.error.URLError, TimeoutError, OSError):
            return None


def build_query_event_publisher(
    callback_url: str | None = None,
    request_id: str | None = None,
) -> QueryEventPublisherPort:
    callback_url = callback_url or os.environ.get("QUERY_LOG_CALLBACK_URL")
    if not callback_url:
        return NoOpQueryEventPublisher()
    return HttpQueryEventPublisher(callback_url, request_id=request_id)
