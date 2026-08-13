from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from app.modules.wiki_ingestion.application.models import PipelineRunCommand
from run_lab import (
    _prepare_api_client,
    parse_args,
    pipeline_command_from_cli_args,
    resolve_api_defaults,
    resolve_endpoint,
    run_pipeline,
)


def _args(*extra: str):
    with patch(
        "sys.argv",
        [
            "run_lab.py",
            "--input",
            "input.md",
            "--provider",
            "openai",
            "--model",
            "gpt-5-nano",
            *extra,
        ],
    ):
        return parse_args()


def test_wiki_evaluation_loop_is_enabled_by_default() -> None:
    assert _args().wiki_evaluation_loop is True


def test_wiki_evaluation_loop_can_be_disabled() -> None:
    assert _args("--no-wiki-evaluation-loop").wiki_evaluation_loop is False


def test_cli_args_are_converted_to_typed_pipeline_command() -> None:
    args = _args("--out", "runs/cli")
    command = pipeline_command_from_cli_args(args)

    assert command.run_id is None
    assert command.input == "input.md"
    assert command.input_name == "input.md"
    assert command.out == "runs/cli"
    assert command.provider == "openai"
    assert command.model == "gpt-5-nano"


def test_api_defaults_validate_and_preserve_request_snapshot() -> None:
    command = PipelineRunCommand(
        run_id=None,
        input="input.md",
        input_name="input.md",
        out="runs/cli",
        user_id="local-user",
        workspace_id="local-workspace",
        provider="gemini",
        model="gemini-3.1-flash-lite",
    )

    resolved = resolve_api_defaults(command)

    assert resolved.provider == "gemini"
    assert resolved.model == "gemini-3.1-flash-lite"
    assert command.provider == "gemini"
    assert command.model == "gemini-3.1-flash-lite"
    assert resolve_endpoint(resolved) == (
        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    )


def test_claude_uses_messages_endpoint() -> None:
    command = PipelineRunCommand(
        run_id=None,
        input="input.md",
        input_name="input.md",
        out="runs/cli",
        user_id="local-user",
        workspace_id="local-workspace",
        provider="claude",
        model="claude-haiku-4-5-20251001",
    )

    assert resolve_endpoint(command) == "https://api.anthropic.com/v1/messages"


def test_api_debug_and_log_metadata_exclude_provider_endpoint(tmp_path: Path) -> None:
    command = PipelineRunCommand(
        run_id=None,
        input="input.md",
        input_name="input.md",
        out=str(tmp_path),
        user_id="local-user",
        workspace_id="local-workspace",
        mode="api",
        provider="openai",
        model="gpt-5-nano",
        save_debug_json=True,
    )
    log = MagicMock()

    with (
        patch("run_lab.load_api_client", return_value=object()),
        patch("run_lab.write_json") as write_json,
    ):
        _prepare_api_client(command, tmp_path, log)

    debug_payload = write_json.call_args.args[1]
    assert debug_payload == {
        "provider": "openai",
        "model": "gpt-5-nano",
        "timeout_seconds": 180,
        "secret_values_saved": False,
    }
    log.emit.assert_called_once_with(
        "API 설정",
        "LLM API 클라이언트를 준비했습니다.",
        {"provider": "openai", "model": "gpt-5-nano"},
    )


def test_run_pipeline_manifest_preserves_log_callback_url(tmp_path: Path) -> None:
    command = PipelineRunCommand(
        run_id="run-1",
        input="input.md",
        input_name="input.md",
        out=str(tmp_path / "run"),
        user_id="local-user",
        workspace_id="local-workspace",
        provider="openai",
        model="gpt-5-nano",
        log_callback_url="https://example.test/logs",
    )
    normalized = {
        "concept_ledger": [],
        "section_candidates": [],
        "mentions": [],
        "categories": [],
        "evidence_units": [],
        "warnings": [],
    }
    page_outputs = SimpleNamespace(
        source_page={},
        source_page_normalized={},
        concept_pages=[],
        links=[],
        source_page_mode="section-polish",
        concept_page_mode="skeleton",
    )

    with (
        patch("run_lab.PipelineLog"),
        patch("run_lab._load_pipeline_prompts", return_value=SimpleNamespace()),
        patch("run_lab._prepare_api_client", return_value=object()),
        patch(
            "run_lab._extract_pipeline_source",
            return_value=(SimpleNamespace(document_id="doc-1"), [], []),
        ),
        patch("run_lab._empty_normalized", return_value=normalized),
        patch("run_lab._assemble_wiki_pages", return_value=page_outputs),
        patch("run_lab._assemble_meaning_clusters", return_value=({}, {})),
    ):
        manifest = run_pipeline(command)

    assert manifest["log_callback_url"] == "https://example.test/logs"
    assert "result_callback_url" not in manifest
