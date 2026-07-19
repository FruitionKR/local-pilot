import argparse
from unittest.mock import patch

from app.modules.wiki_ingestion.application.models import PipelineRunCommand
from app.modules.wiki_ingestion.infrastructure.pipeline_run_adapters import (
    RunLabPipelineRunner,
)


def test_run_lab_pipeline_runner_converts_command_at_infrastructure_boundary() -> None:
    command = PipelineRunCommand(
        run_id="run-1",
        input="input.md",
        input_name="input.md",
        out="runs/run-1",
        user_id="user-1",
        workspace_id="workspace-1",
        selection_mode="partial",
    )

    with patch(
        "app.modules.wiki_ingestion.infrastructure.pipeline_run_adapters.run_pipeline",
        return_value={"manifest": "value"},
    ) as run_pipeline:
        result = RunLabPipelineRunner().run(command)

    args = run_pipeline.call_args.args[0]
    assert isinstance(args, argparse.Namespace)
    assert args.run_id == "run-1"
    assert args.selection_mode == "partial"
    assert result == {"manifest": "value"}
