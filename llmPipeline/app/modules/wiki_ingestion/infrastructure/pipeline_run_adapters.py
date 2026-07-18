from typing import Any

from app.modules.wiki_ingestion.infrastructure import (
    postgres_wiki_ingestion_repository as database,
)
from run_lab import run_pipeline


class RunLabPipelineRunner:
    def run(self, args: Any) -> dict[str, Any]:
        return run_pipeline(args)


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
