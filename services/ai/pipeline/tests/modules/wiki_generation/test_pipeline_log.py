import json
from pathlib import Path
from unittest.mock import patch

import pytest

from app.core.pipeline_control import PipelineRunCancelledError
from app.modules.wiki_generation.infrastructure.pipeline_log import PipelineLog


def test_emit_sends_internal_token_to_log_callback(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("INTERNAL_CALLBACK_TOKEN", "test-internal-token")
    log = PipelineLog(
        tmp_path / "pipeline.log",
        callback_url="http://backend/pipeline-events",
    )

    with patch("urllib.request.urlopen") as urlopen:
        log.emit("변환", "문서를 변환했습니다.")

    request = urlopen.call_args.args[0]
    assert request.get_header("X-internal-token") == "test-internal-token"


def test_emit_reports_pipeline_progress(tmp_path: Path) -> None:
    calls: list[str] = []
    log = PipelineLog(
        tmp_path / "pipeline.log",
        progress_callback=lambda: calls.append("touch"),
    )

    log.emit("변환", "문서를 변환했습니다.")

    assert calls == ["touch"]


def test_emit_keeps_pipeline_running_when_heartbeat_fails(tmp_path: Path) -> None:
    def fail_heartbeat() -> None:
        raise RuntimeError("database unavailable")

    log_path = tmp_path / "pipeline.log"
    log = PipelineLog(log_path, progress_callback=fail_heartbeat)

    log.emit("변환", "문서를 변환했습니다.")

    content = log_path.read_text(encoding="utf-8")
    assert "문서를 변환했습니다." in content
    assert "heartbeat 갱신 실패" in content
    assert "database unavailable" in content


def test_emit_propagates_inactive_pipeline_signal(tmp_path: Path) -> None:
    log = PipelineLog(
        tmp_path / "pipeline.log",
        progress_callback=lambda: False,
    )

    with pytest.raises(PipelineRunCancelledError):
        log.emit("변환", "문서를 변환했습니다.")


def test_emit_excludes_private_llm_fields_from_callback_payload(tmp_path: Path) -> None:
    log = PipelineLog(tmp_path / "pipeline.log", callback_url="http://backend/events")

    with patch("urllib.request.urlopen") as urlopen:
        log.emit(
            "API 설정",
            "클라이언트를 준비했습니다.",
            {
                "provider": "gemini",
                "model": "gemini-2.5-flash-lite",
                "endpoint": "https://provider.example/v1",
                "base_url": "https://provider.example",
                "api_key": "secret",
                "token": "secret-token",
            },
        )

    event = json.loads(urlopen.call_args.args[0].data)
    assert event["data"] == {
        "provider": "gemini",
        "model": "gemini-2.5-flash-lite",
    }
