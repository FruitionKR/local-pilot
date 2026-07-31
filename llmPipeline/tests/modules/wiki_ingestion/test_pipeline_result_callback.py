import json
import urllib.error

from app.modules.wiki_ingestion.infrastructure.pipeline_result_callback import (
    PipelineResultCallbackError,
    post_pipeline_result,
)


class Response:
    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None


def test_result_callback_retries_server_errors_with_same_payload() -> None:
    requests = []
    sleeps = []

    def urlopen(request, timeout):
        requests.append((request, timeout))
        if len(requests) < 3:
            raise urllib.error.HTTPError(
                request.full_url,
                503,
                "unavailable",
                {},
                None,
            )
        return Response()

    payload = {
        "operation_id": "op-1",
        "status": "succeeded",
        "changed_pages": [{"page_id": "C1"}],
    }

    post_pipeline_result(
        "http://backend/api/ai-operations/op-1/result",
        payload,
        urlopen=urlopen,
        sleep=sleeps.append,
        max_attempts=3,
    )

    assert len(requests) == 3
    assert [request.data for request, _timeout in requests] == [
        json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    ] * 3
    assert sleeps == [1.0, 2.0]


def test_result_callback_does_not_retry_conflict() -> None:
    attempts = 0

    def urlopen(request, timeout):
        nonlocal attempts
        attempts += 1
        raise urllib.error.HTTPError(
            request.full_url,
            409,
            "conflict",
            {},
            None,
        )

    try:
        post_pipeline_result(
            "http://backend/result",
            {"operation_id": "op-1"},
            urlopen=urlopen,
            sleep=lambda _seconds: None,
        )
    except PipelineResultCallbackError as exc:
        assert exc.status_code == 409
    else:
        raise AssertionError("callback conflict must fail")

    assert attempts == 1
