from collections.abc import Callable
from typing import Any, Protocol

from app.modules.wiki_ingestion.application.models import PipelineRunCommand


class PipelineRunnerPort(Protocol):
    def run(
        self,
        command: PipelineRunCommand,
        progress_callback: Callable[[], None] | None = None,
    ) -> dict[str, Any]: ...


class PipelineRunRepositoryPort(Protocol):
    def create(
        self,
        run_id: str,
        document_id: str | None,
        input_source: str,
        output_dir: str,
        mode: str,
    ) -> None: ...

    def finish(self, run_id: str, manifest: dict[str, Any]) -> list[str]: ...

    def fail(self, run_id: str, error: str) -> None: ...

    def touch(self, run_id: str) -> None: ...

    def get_document(self, document_id: str) -> dict[str, Any] | None: ...

    def get_run(self, run_id: str) -> dict[str, Any] | None: ...

    def list_active_concept_index(
        self,
        user_id: str,
        workspace_id: str,
    ) -> list[dict[str, Any]]: ...

    def latest_source_page_context(
        self,
        document_id: str,
        user_id: str,
        workspace_id: str,
    ) -> dict[str, Any] | None: ...


class WikiEmbeddingJobPort(Protocol):
    def start(self, run_id: str, page_ids: list[str]) -> None: ...


class PipelineSourceReaderPort(Protocol):
    def read_text(self, object_uri: str) -> str: ...


class PipelineLogReaderPort(Protocol):
    def read_text(self, path: str) -> str: ...
