from typing import Any, Protocol


class PipelineRunnerPort(Protocol):
    def run(self, args: Any) -> dict[str, Any]: ...


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


class WikiEmbeddingJobPort(Protocol):
    def start(self, run_id: str, page_ids: list[str]) -> None: ...
