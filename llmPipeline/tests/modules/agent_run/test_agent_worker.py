import unittest
from unittest.mock import MagicMock, patch

import agent_worker
from app.modules.agent_run.application.agent_worker import AgentWorker
from app.modules.agent_run.application.agent_worker import _resolve_operation_references
from app.modules.agent_run.domain.entities import AgentRun, AgentRunContext
from app.modules.agent_run.infrastructure.postgres_agent_job_repository import PostgresAgentJobRepository
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database


class AgentWorkerTest(unittest.TestCase):
    def test_resolves_approved_dependency_output_without_changing_other_arguments(self) -> None:
        arguments = {
            "document_id": "document-1",
            "folder_id": {"$operation_result": "plan-1-op-1", "field": "id"},
            "position": None,
            "base_version": 3,
        }

        resolved = _resolve_operation_references(
            arguments,
            {"plan-1-op-1": {"id": "folder-created", "current_version": 1}},
        )

        self.assertEqual(
            resolved,
            {
                "document_id": "document-1",
                "folder_id": "folder-created",
                "position": None,
                "base_version": 3,
            },
        )

    def test_rejects_missing_dependency_output(self) -> None:
        with self.assertRaisesRegex(ValueError, "cannot be resolved"):
            _resolve_operation_references(
                {"folder_id": {"$operation_result": "missing", "field": "id"}},
                {},
            )

    def test_deletes_only_expired_terminal_runs(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.fetchall.return_value = [{"id": "run-1"}, {"id": "run-2"}]
        connection_context = MagicMock()
        connection_context.__enter__.return_value = connection

        with patch.object(database, "connect", return_value=connection_context):
            deleted_count = PostgresAgentJobRepository().delete_expired_runs()

        self.assertEqual(deleted_count, 2)
        query, parameters = connection.execute.call_args.args
        self.assertIn("finished_at < now() - interval '90 days'", query)
        self.assertEqual(
            parameters,
            (["completed", "partial_failed", "failed", "conflicted", "rejected", "cancelled"],),
        )

    def test_runs_expired_run_cleanup_once_per_day(self) -> None:
        repository = MagicMock()
        repository.delete_expired_runs.return_value = 3

        next_cleanup_at = agent_worker._cleanup_expired_runs_if_due(repository, 0.0, 100.0)
        unchanged = agent_worker._cleanup_expired_runs_if_due(repository, next_cleanup_at, 101.0)

        self.assertEqual(next_cleanup_at, 100.0 + 24 * 60 * 60)
        self.assertEqual(unchanged, next_cleanup_at)
        repository.delete_expired_runs.assert_called_once_with()

    def test_selected_skill_with_empty_allowed_tools_cannot_read_hierarchy(self) -> None:
        repository = MagicMock()
        worker = AgentWorker(repository, MagicMock(), MagicMock(), MagicMock())
        context = AgentRunContext(
            run=AgentRun(
                id="run-1",
                workspace_id="workspace-1",
                user_id="user-1",
                action="folder_organize",
                skill_version_id="skill-version-1",
                status="planning",
                request_summary="정리",
            ),
            skill_instructions="정리한다.",
            allowed_tools=(),
        )

        with self.assertRaisesRegex(ValueError, "does not allow"):
            worker._read_tool(context, "list_root_items", {})

        repository.reserve_tool_call.assert_not_called()

    def test_terminal_run_status_is_not_overwritten_when_job_fails(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.fetchone.return_value = {"id": "job-1"}
        connection_context = MagicMock()
        connection_context.__enter__.return_value = connection
        job = MagicMock(id="job-1", run_id="run-1", attempt_count=3, lease_token="lease-1")

        with patch.object(database, "connect", return_value=connection_context):
            PostgresAgentJobRepository().fail(job, "RuntimeError")

        operation_update, operation_parameters = connection.execute.call_args_list[1].args
        terminal_update = connection.execute.call_args_list[2].args[0]
        self.assertIn("status = 'failed'", operation_update)
        self.assertIn("status = 'running'", operation_update)
        self.assertEqual(operation_parameters, ("run-1", "RuntimeError"))
        self.assertIn("'partial_failed'", terminal_update)
        self.assertIn("'conflicted'", terminal_update)


if __name__ == "__main__":
    unittest.main()
