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
            manifest: dict[str, Any] = {}
            changed_pages: list[dict[str, Any]] = []
            try:
                self._ensure_active(run_id)
                manifest = self._runner.run(
                    command,
                    progress_callback=lambda: self._repository.touch(run_id),
                )
                self._ensure_active(run_id)
                page_ids = self._repository.finish(run_id, manifest)
                changed_pages = list(manifest.get("operation_artifacts", []))
                self._embedding_job.start(run_id, page_ids)
                if (
                    command.operation_id
                    and command.result_callback_url
                    and self._result_notifier is not None
                ):
                    payload = _result_payload(command, manifest)
                    try:
                        self._result_notifier.notify(
                            command.result_callback_url,
                            payload,
                        )
                    except Exception as exc:
                        self._repository.mark_notification_pending(
                            run_id,
                            str(exc),
                            command.result_callback_url,
                            payload,
                            getattr(exc, "status_code", None),
                        )
                        return manifest
                return manifest
            except Exception as exc:
                self._repository.fail(run_id, str(exc))
                if (
                    command.operation_id
                    and command.result_callback_url
                    and self._result_notifier is not None
                ):
                    payload = _failed_result_payload(command, exc, changed_pages)
                    try:
                        self._result_notifier.notify(
                            command.result_callback_url,
                            payload,
                        )
                    except Exception as callback_exc:
                        self._repository.mark_notification_pending(
                            run_id,
                            str(callback_exc),
                            command.result_callback_url,
                            payload,
                            getattr(callback_exc, "status_code", None),
                        )
                raise

    def retry_notification(self, run_id: str) -> dict[str, Any]:
        if self._result_notifier is None:
            raise RuntimeError("pipeline result notifier is not configured")
        run = self._repository.get_run(run_id)
        if run is None:
            raise LookupError("pipeline run not found")
        pending = (run.get("manifest") or {}).get("pending_notification")
        if not isinstance(pending, dict):
            raise ValueError("pipeline run has no pending notification")
        if pending.get("status_code") == 409:
            raise ValueError("conflicting callback result cannot be retried")
        callback_url = str(pending.get("callback_url") or "")
        payload = pending.get("payload")
        if not callback_url or not isinstance(payload, dict):
            raise ValueError("pending notification is invalid")
        self._result_notifier.notify(callback_url, payload)
        self._repository.complete_notification(
            run_id,
            str(payload.get("status") or "succeeded"),
        )
        return payload

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


def _failed_result_payload(
    command: PipelineRunCommand,
    error: Exception,
    changed_pages: list[dict[str, Any]],
) -> dict[str, Any]:
    return {
        "operation_id": command.operation_id,
        "operation_type": "ingest",
        "status": "failed",
        "workspace_id": command.workspace_id,
        "user_id": command.user_id,
        "target_document_id": command.source_document_id,
        "summary": str(error),
        "changed_pages": changed_pages,
    }
