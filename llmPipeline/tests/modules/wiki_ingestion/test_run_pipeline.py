import unittest

from app.modules.wiki_ingestion.application.run_pipeline import (
    PipelineRunRegistration,
    RunPipelineUseCase,
)


class FakeRunner:
    def __init__(self, calls: list[object], error: Exception | None = None) -> None:
        self.calls = calls
        self.error = error

    def run(self, args: object) -> dict[str, object]:
        self.calls.append(("run", args))
        if self.error is not None:
            raise self.error
        return {"manifest": "value"}


class FakeRepository:
    def __init__(self, calls: list[object]) -> None:
        self.calls = calls

    def create(
        self,
        run_id: str,
        document_id: str | None,
        input_source: str,
        output_dir: str,
        mode: str,
    ) -> None:
        self.calls.append(
            ("create", run_id, document_id, input_source, output_dir, mode)
        )

    def finish(self, run_id: str, manifest: dict[str, object]) -> list[str]:
        self.calls.append(("finish", run_id, manifest))
        return ["page-1"]

    def fail(self, run_id: str, error: str) -> None:
        self.calls.append(("fail", run_id, error))


class FakeEmbeddingJob:
    def __init__(self, calls: list[object]) -> None:
        self.calls = calls

    def start(self, run_id: str, page_ids: list[str]) -> None:
        self.calls.append(("embedding", run_id, page_ids))


class RunPipelineUseCaseTest(unittest.TestCase):
    def test_register_and_execute_preserve_pipeline_state_order(self) -> None:
        calls: list[object] = []
        use_case = RunPipelineUseCase(
            runner=FakeRunner(calls),
            repository=FakeRepository(calls),
            embedding_job=FakeEmbeddingJob(calls),
        )
        registration = PipelineRunRegistration(
            run_id="run-1",
            document_id="doc-1",
            input_source="storage:doc-1.md",
            output_dir="runs/run-1",
            mode="api",
        )
        args = object()

        use_case.register(registration)
        manifest = use_case.execute("run-1", args)

        self.assertEqual(manifest, {"manifest": "value"})
        self.assertEqual(
            calls,
            [
                (
                    "create",
                    "run-1",
                    "doc-1",
                    "storage:doc-1.md",
                    "runs/run-1",
                    "api",
                ),
                ("run", args),
                ("finish", "run-1", {"manifest": "value"}),
                ("embedding", "run-1", ["page-1"]),
            ],
        )

    def test_execute_marks_pipeline_failed_before_propagating_error(self) -> None:
        calls: list[object] = []
        use_case = RunPipelineUseCase(
            runner=FakeRunner(calls, RuntimeError("pipeline failed")),
            repository=FakeRepository(calls),
            embedding_job=FakeEmbeddingJob(calls),
        )
        args = object()

        with self.assertRaisesRegex(RuntimeError, "pipeline failed"):
            use_case.execute("run-1", args)

        self.assertEqual(
            calls,
            [
                ("run", args),
                ("fail", "run-1", "pipeline failed"),
            ],
        )


if __name__ == "__main__":
    unittest.main()
