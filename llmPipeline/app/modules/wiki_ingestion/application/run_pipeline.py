from threading import Lock
from typing import Any

from app.modules.wiki_ingestion.application.models import (
    PipelineRunCommand,
    PipelineRunRegistration,
)
from app.modules.wiki_ingestion.application.ports import (
    PipelineRunnerPort,
    PipelineRunRepositoryPort,
    WikiEmbeddingJobPort,
)


_PIPELINE_EXECUTION_LOCK = Lock()


class RunPipelineUseCase:
    def __init__(
        self,
        runner: PipelineRunnerPort,
        repository: PipelineRunRepositoryPort,
        embedding_job: WikiEmbeddingJobPort,
    ) -> None:
        self._runner = runner
        self._repository = repository
        self._embedding_job = embedding_job

    def register(self, registration: PipelineRunRegistration) -> None:
        self._repository.create(
            registration.run_id,
            registration.document_id,
            registration.input_source,
            registration.output_dir,
            registration.mode,
        )

    def execute(self, run_id: str, command: PipelineRunCommand) -> dict[str, Any]:
        with _PIPELINE_EXECUTION_LOCK:
            try:
                manifest = self._runner.run(
                    command,
                    progress_callback=lambda: self._repository.touch(run_id),
                )
                page_ids = self._repository.finish(run_id, manifest)
                self._embedding_job.start(run_id, page_ids)
                return manifest
            except Exception as exc:
                self._repository.fail(run_id, str(exc))
                raise
