from unittest.mock import patch

from app.modules.wiki_ingestion.application.models import PipelineRunCommand
from run_lab import (
    parse_args,
    pipeline_command_from_cli_args,
    resolve_api_defaults,
)


def test_wiki_evaluation_loop_is_enabled_by_default() -> None:
    with patch("sys.argv", ["run_lab.py", "--input", "input.md"]):
        args = parse_args()

    assert args.wiki_evaluation_loop is True


def test_wiki_evaluation_loop_can_be_disabled() -> None:
    with patch("sys.argv", ["run_lab.py", "--input", "input.md", "--no-wiki-evaluation-loop"]):
        args = parse_args()

    assert args.wiki_evaluation_loop is False


def test_cli_args_are_converted_to_typed_pipeline_command() -> None:
    with patch(
        "sys.argv",
        [
            "run_lab.py",
            "--input",
            "inputs/document.md",
            "--out",
            "runs/cli",
            "--provider",
            "generic",
            "--no-wiki-evaluation-loop",
        ],
    ):
        args = parse_args()

    command = pipeline_command_from_cli_args(args)

    assert command.run_id is None
    assert command.input == "inputs/document.md"
    assert command.input_name == "inputs/document.md"
    assert command.out == "runs/cli"
    assert command.provider == "generic"
    assert command.wiki_evaluation_loop is False


def test_api_defaults_return_resolved_command_without_mutating_input(
    monkeypatch,
) -> None:
    monkeypatch.delenv("UPSTAGE_BASE_URL", raising=False)
    monkeypatch.delenv("LLM_BASE_URL", raising=False)
    monkeypatch.delenv("UPSTAGE_MODEL", raising=False)
    monkeypatch.delenv("LLM_MODEL", raising=False)
    command = PipelineRunCommand(
        run_id=None,
        input="input.md",
        input_name="input.md",
        out="runs/cli",
        user_id="local-user",
        workspace_id="local-workspace",
    )

    resolved = resolve_api_defaults(command)

    assert command.api_base_url is None
    assert command.api_key_env is None
    assert command.model is None
    assert resolved.api_base_url == "https://api.upstage.ai/v1"
    assert resolved.api_key_env == "UPSTAGE_API_KEY"
    assert resolved.model == "solar-pro2"
