import unittest
from collections.abc import Callable
from threading import Event, Thread

from app.modules.wiki_ingestion.application.run_pipeline import (
    PipelineRunCommand,
    PipelineRunRegistration,
    RunPipelineUseCase,
)


class FakeRunner:
    def __init__(self, calls: list[object], error: Exception | None = None) -> None:
        self.calls = calls
        self.error = error

    def run(
        self,
        command: PipelineRunCommand,
        progress_callback: Callable[[], None] | None = None,
    ) -> dict[str, object]:
        self.calls.append(("run", command))
        if self.error is not None:
            raise self.error
        return {"manifest": "value"}


class FakeRepository:
    def __init__(
        self,
        calls: list[object],
        active_results: list[bool] | None = None,
        run_result: dict[str, object] | None = None,
    ) -> None:
        self.calls = calls
        self.active_results = list(active_results or [])
        self.run_result = run_result

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

    def mark_notification_pending(
        self,
        run_id: str,
        error: str,
        callback_url: str,
        payload: dict[str, object],
        status_code: int | None = None,
    ) -> None:
        self.calls.append(
            (
                "notify_pending",
                run_id,
                error,
                callback_url,
                payload,
                status_code,
            )
        )

    def complete_notification(self, run_id: str, status: str) -> None:
        self.calls.append(("notify_complete", run_id, status))

    def get_run(self, run_id: str) -> dict[str, object] | None:
        self.calls.append(("get_run", run_id))
        return self.run_result

    def touch(self, run_id: str) -> bool:
        self.calls.append(("touch", run_id))
        if self.active_results:
            return self.active_results.pop(0)
        return True


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
        command = PipelineRunCommand(
            run_id="run-1",
            input="input.md",
            input_name="input.md",
            out="runs/run-1",
            user_id="user-1",
            workspace_id="workspace-1",
        )

        use_case.register(registration)
        manifest = use_case.execute("run-1", command)

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
                ("touch", "run-1"),
                ("run", command),
                ("touch", "run-1"),
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
        command = PipelineRunCommand(
            run_id="run-1",
            input="input.md",
            input_name="input.md",
            out="runs/run-1",
            user_id="user-1",
            workspace_id="workspace-1",
        )

        with self.assertRaisesRegex(RuntimeError, "pipeline failed"):
            use_case.execute("run-1", command)

        self.assertEqual(
            calls,
            [
                ("touch", "run-1"),
                ("run", command),
                ("fail", "run-1", "pipeline failed"),
            ],
        )

    def test_execute_notifies_operation_failure(self) -> None:
        calls: list[object] = []

        class ResultNotifier:
            def notify(self, callback_url, payload):
                calls.append(("notify", callback_url, payload))

        use_case = RunPipelineUseCase(
            runner=FakeRunner(calls, RuntimeError("pipeline failed")),
            repository=FakeRepository(calls),
            embedding_job=FakeEmbeddingJob(calls),
            result_notifier=ResultNotifier(),
        )
        command = PipelineRunCommand(
            run_id="run-1",
            operation_id="op-1",
            result_callback_url="http://backend/result",
            input="input.md",
            input_name="input.md",
            out="runs/run-1",
            user_id="user-1",
            workspace_id="workspace-1",
            source_document_id="doc-1",
        )

        with self.assertRaisesRegex(RuntimeError, "pipeline failed"):
            use_case.execute("run-1", command)

        payload = next(call[2] for call in calls if call[0] == "notify")
        self.assertEqual(payload["status"], "failed")
        self.assertEqual(payload["changed_pages"], [])

    def test_execute_connects_pipeline_progress_to_run_heartbeat(self) -> None:
        class ProgressRunner:
            def run(
                self,
                command: PipelineRunCommand,
                progress_callback: Callable[[], None] | None = None,
            ) -> dict[str, object]:
                assert progress_callback is not None
                progress_callback()
                return {"manifest": "value"}

        calls: list[object] = []
        use_case = RunPipelineUseCase(
            runner=ProgressRunner(),
            repository=FakeRepository(calls),
            embedding_job=FakeEmbeddingJob(calls),
        )
        command = PipelineRunCommand(
            run_id="run-1",
            input="input.md",
            input_name="input.md",
            out="runs/run-1",
            user_id="user-1",
            workspace_id="workspace-1",
        )

        use_case.execute("run-1", command)

        self.assertEqual(
            calls[:3],
            [
                ("touch", "run-1"),
                ("touch", "run-1"),
                ("touch", "run-1"),
            ],
        )

    def test_execute_stops_before_runner_for_inactive_target(self) -> None:
        calls: list[object] = []
        use_case = RunPipelineUseCase(
            runner=FakeRunner(calls),
            repository=FakeRepository(calls, active_results=[False]),
            embedding_job=FakeEmbeddingJob(calls),
        )
        command = PipelineRunCommand(
            run_id="run-1",
            input="input.md",
            input_name="input.md",
            out="runs/run-1",
            user_id="user-1",
            workspace_id="workspace-1",
        )

        with self.assertRaisesRegex(
            RuntimeError,
            "document or workspace is inactive",
        ):
            use_case.execute("run-1", command)

        self.assertEqual(
            calls,
            [
                ("touch", "run-1"),
                (
                    "fail",
                    "run-1",
                    "Pipeline run cancelled because its document or workspace is inactive.",
                ),
            ],
        )

    def test_execute_stops_before_finish_when_target_becomes_inactive(self) -> None:
        calls: list[object] = []
        use_case = RunPipelineUseCase(
            runner=FakeRunner(calls),
            repository=FakeRepository(calls, active_results=[True, False]),
            embedding_job=FakeEmbeddingJob(calls),
        )
        command = PipelineRunCommand(
            run_id="run-1",
            input="input.md",
            input_name="input.md",
            out="runs/run-1",
            user_id="user-1",
            workspace_id="workspace-1",
        )

        with self.assertRaisesRegex(
            RuntimeError,
            "document or workspace is inactive",
        ):
            use_case.execute("run-1", command)

        self.assertEqual(
            calls,
            [
                ("touch", "run-1"),
                ("run", command),
                ("touch", "run-1"),
                (
                    "fail",
                    "run-1",
                    "Pipeline run cancelled because its document or workspace is inactive.",
                ),
            ],
        )

    def test_execute_serializes_runs_across_use_case_instances(self) -> None:
        first_entered = Event()
        release_first = Event()
        second_entered = Event()

        class BlockingRunner:
            def run(
                self,
                command: PipelineRunCommand,
                progress_callback: Callable[[], None] | None = None,
            ) -> dict[str, object]:
                if command.run_id == "run-1":
                    first_entered.set()
                    release_first.wait(timeout=1)
                else:
                    second_entered.set()
                return {"manifest": command.run_id}

        calls: list[object] = []
        first_use_case = RunPipelineUseCase(
            runner=BlockingRunner(),
            repository=FakeRepository(calls),
            embedding_job=FakeEmbeddingJob(calls),
        )
        second_use_case = RunPipelineUseCase(
            runner=BlockingRunner(),
            repository=FakeRepository(calls),
            embedding_job=FakeEmbeddingJob(calls),
        )
        first = Thread(
            target=first_use_case.execute,
            args=(
                "run-1",
                PipelineRunCommand(
                    run_id="run-1",
                    input="one.md",
                    input_name="one.md",
                    out="runs/one",
                    user_id="user-1",
                    workspace_id="workspace-1",
                ),
            ),
        )
        second = Thread(
            target=second_use_case.execute,
            args=(
                "run-2",
                PipelineRunCommand(
                    run_id="run-2",
                    input="two.md",
                    input_name="two.md",
                    out="runs/two",
                    user_id="user-1",
                    workspace_id="workspace-1",
                ),
            ),
        )

        first.start()
        self.assertTrue(first_entered.wait(timeout=1))
        second.start()
        self.assertFalse(second_entered.wait(timeout=0.1))
        release_first.set()
        first.join(timeout=1)
        second.join(timeout=1)

        self.assertFalse(first.is_alive())
        self.assertFalse(second.is_alive())
        self.assertTrue(second_entered.is_set())

    def test_execute_starts_embedding_before_operation_result_callback(self) -> None:
        calls: list[object] = []

        class ResultRepository(FakeRepository):
            def finish(self, run_id, manifest):
                calls.append(("finish", run_id))
                manifest["operation_artifacts"] = [
                    {
                        "page_id": "concept-1",
                        "page_type": "concept",
                        "markdown_key": "wiki/ws/pages/concept-1/ops/op-1.md",
                        "contribution_key": (
                            "wiki/ws/pages/concept-1/ops/op-1.json"
                        ),
                        "content_hash": "sha256:abc",
                    }
                ]
                return ["concept-1"]

        class ResultNotifier:
            def notify(self, callback_url, payload):
                calls.append(("notify", callback_url, payload))

        use_case = RunPipelineUseCase(
            runner=FakeRunner(calls),
            repository=ResultRepository(calls),
            embedding_job=FakeEmbeddingJob(calls),
            result_notifier=ResultNotifier(),
        )
        command = PipelineRunCommand(
            run_id="run-1",
            operation_id="op-1",
            result_callback_url="http://backend/result",
            input="input.md",
            input_name="input.md",
            out="runs/run-1",
            user_id="user-1",
            workspace_id="workspace-1",
            source_document_id="doc-1",
        )

        use_case.execute("run-1", command)

        notify_index = next(
            index for index, call in enumerate(calls) if call[0] == "notify"
        )
        embedding_index = next(
            index for index, call in enumerate(calls) if call[0] == "embedding"
        )
        payload = calls[notify_index][2]
        self.assertLess(embedding_index, notify_index)
        self.assertEqual(payload["operation_id"], "op-1")
        self.assertEqual(payload["operation_type"], "ingest")
        self.assertEqual(payload["target_document_id"], "doc-1")
        self.assertEqual(payload["changed_pages"][0]["page_id"], "concept-1")

    def test_callback_failure_keeps_ingest_success_and_starts_embedding(self) -> None:
        calls: list[object] = []

        class FailingNotifier:
            def notify(self, callback_url, payload):
                calls.append(("notify", callback_url, payload))
                raise RuntimeError("callback failed")

        use_case = RunPipelineUseCase(
            runner=FakeRunner(calls),
            repository=FakeRepository(calls),
            embedding_job=FakeEmbeddingJob(calls),
            result_notifier=FailingNotifier(),
        )
        command = PipelineRunCommand(
            run_id="run-1",
            operation_id="op-1",
            result_callback_url="http://backend/result",
            input="input.md",
            input_name="input.md",
            out="runs/run-1",
            user_id="user-1",
            workspace_id="workspace-1",
            source_document_id="doc-1",
        )

        manifest = use_case.execute("run-1", command)

        self.assertEqual(manifest, {"manifest": "value"})
        self.assertIn(
            "notify_pending",
            [call[0] for call in calls],
        )
        pending = next(call for call in calls if call[0] == "notify_pending")
        self.assertEqual(pending[3], "http://backend/result")
        self.assertEqual(pending[4]["status"], "succeeded")
        self.assertTrue(any(call[0] == "embedding" for call in calls))
        self.assertFalse(any(call[0] == "fail" for call in calls))

    def test_retry_notification_reuses_durable_payload(self) -> None:
        calls: list[object] = []
        payload = {"operation_id": "op-1", "status": "succeeded"}

        class ResultNotifier:
            def notify(self, callback_url, value):
                calls.append(("notify", callback_url, value))

        repository = FakeRepository(
            calls,
            run_result={
                "manifest": {
                    "pending_notification": {
                        "callback_url": "http://backend/result",
                        "payload": payload,
                    }
                }
            },
        )
        use_case = RunPipelineUseCase(
            runner=FakeRunner(calls),
            repository=repository,
            embedding_job=FakeEmbeddingJob(calls),
            result_notifier=ResultNotifier(),
        )

        result = use_case.retry_notification("run-1")

        self.assertEqual(result, payload)
        self.assertEqual(
            calls,
            [
                ("get_run", "run-1"),
                ("notify", "http://backend/result", payload),
                ("notify_complete", "run-1", "succeeded"),
            ],
        )

    def test_retry_notification_rejects_conflicting_result(self) -> None:
        calls: list[object] = []

        class ResultNotifier:
            def notify(self, callback_url, value):
                calls.append(("notify", callback_url, value))

        repository = FakeRepository(
            calls,
            run_result={
                "manifest": {
                    "pending_notification": {
                        "callback_url": "http://backend/result",
                        "payload": {"status": "succeeded"},
                        "status_code": 409,
                    }
                }
            },
        )
        use_case = RunPipelineUseCase(
            runner=FakeRunner(calls),
            repository=repository,
            embedding_job=FakeEmbeddingJob(calls),
            result_notifier=ResultNotifier(),
        )

        with self.assertRaisesRegex(ValueError, "cannot be retried"):
            use_case.retry_notification("run-1")
        self.assertFalse(any(call[0] == "notify" for call in calls))

if __name__ == "__main__":
    unittest.main()
