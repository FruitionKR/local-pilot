from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

from app.modules.query.application.ports import QueryEventPublisherPort


class NoOpQueryEventPublisher(QueryEventPublisherPort):
    def publish(self, stage: str, message: str, data: dict[str, object] | None = None) -> None:
        return None


class HttpQueryEventPublisher(QueryEventPublisherPort):
    def __init__(self, callback_url: str, timeout_seconds: int = 5) -> None:
        self._callback_url = callback_url
        self._timeout_seconds = timeout_seconds

    def publish(self, stage: str, message: str, data: dict[str, object] | None = None) -> None:
        body = json.dumps(
            {
                "event_type": "query.log",
                "stage": stage,
                "message": message,
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


def build_query_event_publisher() -> QueryEventPublisherPort:
    callback_url = os.environ.get("QUERY_LOG_CALLBACK_URL")
    if not callback_url:
        return NoOpQueryEventPublisher()
    return HttpQueryEventPublisher(callback_url)
