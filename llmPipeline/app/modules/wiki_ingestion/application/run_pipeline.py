from dataclasses import dataclass
from typing import Any

from app.modules.wiki_ingestion.application.ports import (
    PipelineRunnerPort,
    PipelineRunRepositoryPort,
    WikiEmbeddingJobPort,
)


@dataclass(frozen=True)
class PipelineRunRegistration:
    run_id: str
    document_id: str | None
    input_source: str
    output_dir: str
    mode: str


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

    def execute(self, run_id: str, args: Any) -> dict[str, Any]:
        try:
            manifest = self._runner.run(args)
            page_ids = self._repository.finish(run_id, manifest)
            self._embedding_job.start(run_id, page_ids)
            return manifest
        except Exception as exc:
            self._repository.fail(run_id, str(exc))
            raise
