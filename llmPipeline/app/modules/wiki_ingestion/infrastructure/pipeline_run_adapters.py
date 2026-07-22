from collections.abc import Callable
from pathlib import Path
from typing import Any

from app.modules.wiki_ingestion.application.models import PipelineRunCommand
from app.modules.wiki_ingestion.infrastructure import (
    postgres_wiki_ingestion_repository as database,
)
from app.modules.wiki_ingestion.infrastructure.object_storage import read_text_object
from run_lab import run_pipeline


class RunLabPipelineRunner:
    def run(
        self,
        command: PipelineRunCommand,
        progress_callback: Callable[[], None] | None = None,
    ) -> dict[str, Any]:
        return run_pipeline(command, progress_callback=progress_callback)


class PostgresPipelineRunRepository:
    def create(
        self,
        run_id: str,
        document_id: str | None,
        input_source: str,
        output_dir: str,
        mode: str,
    ) -> None:
        database.create_pipeline_run(
            run_id,
            document_id,
            input_source,
            output_dir,
            mode,
        )

    def finish(self, run_id: str, manifest: dict[str, Any]) -> list[str]:
        return database.finish_pipeline_run(run_id, manifest)

    def fail(self, run_id: str, error: str) -> None:
        database.fail_pipeline_run(run_id, error)

    def touch(self, run_id: str) -> None:
        database.touch_pipeline_run(run_id)

    def get_document(self, document_id: str) -> dict[str, Any] | None:
        return database.get_document(document_id)

    def get_run(self, run_id: str) -> dict[str, Any] | None:
        return database.get_pipeline_run(run_id)

    def list_active_concept_index(
        self,
        user_id: str,
        workspace_id: str,
    ) -> list[dict[str, Any]]:
        return database.list_active_concept_index(user_id, workspace_id)

    def latest_source_page_context(
        self,
        document_id: str,
        user_id: str,
        workspace_id: str,
    ) -> dict[str, Any] | None:
        return database.latest_source_page_context(
            document_id,
            user_id,
            workspace_id,
        )


class ObjectStoragePipelineSourceReader:
    def read_text(self, object_uri: str) -> str:
        return read_text_object(object_uri)


class LocalPipelineLogReader:
    def read_text(self, path: str) -> str:
        log_path = Path(path)
        if not log_path.exists():
            return ""
        return log_path.read_text(encoding="utf-8")
