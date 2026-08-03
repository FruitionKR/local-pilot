import unittest
from dataclasses import replace
from unittest.mock import MagicMock, patch

import agent_worker
from app.modules.agent_run.application.agent_worker import AgentWorker
from app.modules.agent_run.application.agent_worker import _resolve_operation_references
from app.modules.agent_run.domain.entities import AgentRun, AgentRunContext
from app.modules.agent_run.domain.execution import AgentExecutionDecision
from app.modules.agent_run.domain.plan import AgentPlan, AgentPlanOperation
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
        worker = AgentWorker(repository, MagicMock(), MagicMock(), MagicMock(), MagicMock())
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

    def test_react_executor_uses_stored_approved_operation_arguments(self) -> None:
        repository = MagicMock()
        repository.reserve_tool_call.return_value = True
        repository.mark_operation.return_value = True
        context = _executing_context()
        plan = _approved_plan()
        succeeded_plan = replace(
            plan,
            operations=(replace(plan.operations[0], status="succeeded"),),
        )
        repository.load_context.return_value = context
        repository.load_current_plan.side_effect = [plan, plan, succeeded_plan]
        repository.load_operation_results.return_value = {}
        tool_gateway = MagicMock()
        tool_gateway.read.return_value = {"items": []}
        tool_gateway.execute.return_value = {"id": "document-1", "folder_id": "folder-1"}
        decider = MagicMock()
        decider.decide.side_effect = [
            AgentExecutionDecision(
                action="execute_operation",
                operation_id="plan-1-op-1",
                arguments={"document_id": "unapproved-document"},
            ),
            AgentExecutionDecision(action="finish"),
        ]
        worker = AgentWorker(repository, MagicMock(), tool_gateway, MagicMock(), decider)

        worker._execute(MagicMock(run_id="run-1"))

        self.assertEqual(
            tool_gateway.execute.call_args.kwargs["arguments"],
            {
                "document_id": "document-1",
                "folder_id": "folder-1",
                "position": None,
                "base_version": 3,
            },
        )
        repository.enqueue_verification.assert_called_once_with("run-1")

    def test_react_executor_rejects_read_tool_outside_skill_allowlist(self) -> None:
        worker = AgentWorker(MagicMock(), MagicMock(), MagicMock(), MagicMock(), MagicMock())

        with self.assertRaisesRegex(ValueError, "not allowed"):
            worker._validate_read_decision(
                "get_document_metadata",
                {"document_id": "document-1"},
                ("list_root_items",),
            )

    def test_react_executor_does_not_execute_unapproved_operation_id(self) -> None:
        repository = MagicMock()
        repository.reserve_tool_call.return_value = True
        context = _executing_context()
        plan = _approved_plan()
        repository.load_context.return_value = context
        repository.load_current_plan.side_effect = [plan, plan]
        repository.load_operation_results.return_value = {}
        tool_gateway = MagicMock()
        tool_gateway.read.return_value = {"items": []}
        decider = MagicMock()
        decider.decide.return_value = AgentExecutionDecision(
            action="execute_operation",
            operation_id="unapproved-operation",
        )
        worker = AgentWorker(repository, MagicMock(), tool_gateway, MagicMock(), decider)

        with self.assertRaisesRegex(ValueError, "not ready"):
            worker._execute(MagicMock(run_id="run-1"))

        tool_gateway.execute.assert_not_called()

    def test_react_executor_stops_for_new_plan_without_running_mutation(self) -> None:
        repository = MagicMock()
        repository.reserve_tool_call.return_value = True
        repository.mark_run_status.return_value = True
        context = _executing_context()
        plan = _approved_plan()
        repository.load_context.return_value = context
        repository.load_current_plan.side_effect = [plan, plan]
        repository.load_operation_results.return_value = {}
        tool_gateway = MagicMock()
        tool_gateway.read.return_value = {"items": []}
        decider = MagicMock()
        decider.decide.return_value = AgentExecutionDecision(
            action="request_replan",
            reason="현재 구조가 승인 시점과 다릅니다.",
        )
        worker = AgentWorker(repository, MagicMock(), tool_gateway, MagicMock(), decider)

        worker._execute(MagicMock(run_id="run-1"))

        repository.mark_run_status.assert_called_once_with(
            "run-1",
            ("executing",),
            "clarification_required",
        )
        tool_gateway.execute.assert_not_called()
        repository.enqueue_verification.assert_not_called()

    def test_react_executor_does_not_start_mutation_cancelled_during_decision(self) -> None:
        repository = MagicMock()
        repository.reserve_tool_call.return_value = True
        context = _executing_context()
        cancelled_context = replace(context, run=replace(context.run, status="cancelled"))
        plan = _approved_plan()
        repository.load_context.side_effect = [context, context, cancelled_context]
        repository.load_current_plan.side_effect = [plan, plan]
        repository.load_operation_results.return_value = {}
        tool_gateway = MagicMock()
        tool_gateway.read.return_value = {"items": []}
        decider = MagicMock()
        decider.decide.return_value = AgentExecutionDecision(
            action="execute_operation",
            operation_id="plan-1-op-1",
        )
        worker = AgentWorker(repository, MagicMock(), tool_gateway, MagicMock(), decider)

        worker._execute(MagicMock(run_id="run-1"))

        tool_gateway.execute.assert_not_called()
        repository.enqueue_verification.assert_not_called()

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


def _executing_context() -> AgentRunContext:
    return AgentRunContext(
        run=AgentRun(
            id="run-1",
            workspace_id="workspace-1",
            user_id="user-1",
            action="folder_organize",
            skill_version_id=None,
            status="executing",
            request_summary="문서를 정리해줘",
        ),
        skill_instructions=None,
        allowed_tools=(),
    )


def _approved_plan() -> AgentPlan:
    return AgentPlan(
        id="plan-1",
        run_id="run-1",
        version=1,
        summary="문서를 이동합니다.",
        operation_hash="approved-hash",
        status="approved",
        operations=(
            AgentPlanOperation(
                id="plan-1-op-1",
                sequence=1,
                tool_name="move_document",
                target_type="document",
                target_id="document-1",
                base_version=3,
                source_parent_id=None,
                destination_parent_id="folder-1",
                arguments={
                    "document_id": "document-1",
                    "folder_id": "folder-1",
                    "position": None,
                    "base_version": 3,
                },
                reason="관련 문서를 모읍니다.",
            ),
        ),
    )


if __name__ == "__main__":
    unittest.main()
