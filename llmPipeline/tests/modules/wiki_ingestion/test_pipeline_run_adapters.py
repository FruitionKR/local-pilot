from unittest.mock import patch

from app.modules.wiki_ingestion.application.models import PipelineRunCommand
from app.modules.wiki_ingestion.infrastructure.pipeline_run_adapters import (
    RunLabPipelineRunner,
)


def test_run_lab_pipeline_runner_passes_typed_command_without_conversion() -> None:
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

    run_pipeline.assert_called_once_with(command)
    assert result == {"manifest": "value"}
