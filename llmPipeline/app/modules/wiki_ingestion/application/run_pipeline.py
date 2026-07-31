from threading import Lock
from typing import Any

from app.core.pipeline_control import PipelineRunCancelledError
from app.modules.wiki_ingestion.application.models import (
    PipelineRunCommand,
    PipelineRunRegistration,
)
from app.modules.wiki_ingestion.application.ports import (
    PipelineRunnerPort,
    PipelineResultNotifierPort,
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
        result_notifier: PipelineResultNotifierPort | None = None,
    ) -> None:
        self._runner = runner
        self._repository = repository
        self._embedding_job = embedding_job
        self._result_notifier = result_notifier

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
                self._ensure_active(run_id)
                manifest = self._runner.run(
                    command,
                    progress_callback=lambda: self._repository.touch(run_id),
                )
                self._ensure_active(run_id)
                page_ids = self._repository.finish(run_id, manifest)
                self._embedding_job.start(run_id, page_ids)
                if (
                    command.operation_id
                    and command.result_callback_url
                    and self._result_notifier is not None
                ):
                    try:
                        self._result_notifier.notify(
                            command.result_callback_url,
                            _result_payload(command, manifest),
                        )
                    except Exception as exc:
                        self._repository.mark_notification_pending(
                            run_id,
                            str(exc),
                        )
                        return manifest
                return manifest
            except Exception as exc:
                self._repository.fail(run_id, str(exc))
                raise

    def _ensure_active(self, run_id: str) -> None:
        if self._repository.touch(run_id) is False:
            raise PipelineRunCancelledError(
                "Pipeline run cancelled because its document or workspace is inactive."
            )


def _result_payload(
    command: PipelineRunCommand,
    manifest: dict[str, Any],
) -> dict[str, Any]:
    return {
        "operation_id": command.operation_id,
        "operation_type": "ingest",
        "status": "succeeded",
        "workspace_id": command.workspace_id,
        "user_id": command.user_id,
        "target_document_id": command.source_document_id,
        "summary": "Wiki ingest를 완료했습니다.",
        "changed_pages": manifest.get("operation_artifacts", []),
    }
