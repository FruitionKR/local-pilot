import json
import urllib.error

from app.modules.wiki_ingestion.infrastructure.pipeline_result_callback import (
    PipelineResultCallbackError,
    _rewrite_operation_artifacts,
    post_pipeline_result,
)


class Response:
    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None


def test_result_callback_retries_server_errors_with_same_payload(monkeypatch) -> None:
    requests = []
    sleeps = []
    monkeypatch.setenv("INTERNAL_CALLBACK_TOKEN", "test-callback-token")

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
    assert {
        request.headers["X-internal-token"] for request, _timeout in requests
    } == {"test-callback-token"}
    assert sleeps == [1.0, 2.0]


def test_result_callback_does_not_retry_conflict(monkeypatch) -> None:
    attempts = 0
    monkeypatch.setenv("INTERNAL_CALLBACK_TOKEN", "test-callback-token")

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


def test_result_callback_retries_unprocessable_result(monkeypatch) -> None:
    attempts = 0
    rewrites = []
    monkeypatch.setenv("INTERNAL_CALLBACK_TOKEN", "test-callback-token")

    def urlopen(request, timeout):
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise urllib.error.HTTPError(
                request.full_url,
                422,
                "unprocessable",
                {},
                None,
            )
        return Response()

    post_pipeline_result(
        "http://backend/result",
        {"operation_id": "op-1", "changed_pages": []},
        urlopen=urlopen,
        sleep=lambda _seconds: None,
        rewrite_artifacts=lambda payload: rewrites.append(payload),
    )

    assert attempts == 2
    assert len(rewrites) == 1


def test_rewrite_operation_artifacts_repairs_keys_and_hash(monkeypatch) -> None:
    writes = []
    payload = {
        "operation_id": "op-1",
        "workspace_id": "ws-1",
        "changed_pages": [
            {
                "page_id": "page-1",
                "markdown_key": "wrong/page.md",
                "contribution_key": "wrong/page.json",
                "content_hash": "sha256:wrong",
            }
        ],
    }
    monkeypatch.setattr(
        "app.modules.wiki_ingestion.infrastructure.pipeline_result_callback.read_text_object",
        lambda key: "# Page\n" if key.endswith(".md") else "{}",
    )
    monkeypatch.setattr(
        "app.modules.wiki_ingestion.infrastructure.pipeline_result_callback.write_text_object",
        lambda *args: writes.append(args),
    )

    _rewrite_operation_artifacts(payload)

    page = payload["changed_pages"][0]
    assert page["markdown_key"] == "wiki/ws-1/pages/page-1/ops/op-1.md"
    assert page["contribution_key"] == "wiki/ws-1/pages/page-1/ops/op-1.json"
    assert page["content_hash"].startswith("sha256:")
    assert len(writes) == 2
