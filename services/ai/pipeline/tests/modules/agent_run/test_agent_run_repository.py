import unittest
from unittest.mock import MagicMock, patch

from app.modules.agent_run.infrastructure.postgres_agent_run_repository import PostgresAgentRunRepository
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database


class AgentRunRepositoryTest(unittest.TestCase):
    def test_revise_clears_previous_clarification_error_code(self) -> None:
        connection = MagicMock()
        locked_result = MagicMock()
        locked_result.fetchone.return_value = _run_row(
            status="clarification_required",
            error_code="react_replan_state_changed",
            current_plan_id="plan-1",
        )
        supersede_result = MagicMock()
        update_result = MagicMock()
        update_result.fetchone.return_value = _run_row(
            status="queued",
            error_code=None,
            current_plan_id=None,
            request_summary="새 계획으로 수정해줘",
        )
        insert_result = MagicMock()
        connection.execute.side_effect = [
            locked_result,
            supersede_result,
            update_result,
            insert_result,
        ]
        connection_context = MagicMock()
        connection_context.__enter__.return_value = connection

        with patch.object(database, "connect_ai", return_value=connection_context):
            run = PostgresAgentRunRepository().revise(
                "workspace-1",
                "user-1",
                "run-1",
                "새 계획으로 수정해줘",
                "job-1",
            )

        update_query = connection.execute.call_args_list[2].args[0]
        self.assertIn("error_code = NULL", update_query)
        self.assertIsNone(run.error_code)

    def test_repository_uses_ai_database_connection(self) -> None:
        connection = MagicMock()
        result = MagicMock()
        result.fetchone.return_value = None
        connection.execute.return_value = result
        connection_context = MagicMock()
        connection_context.__enter__.return_value = connection

        with patch.object(database, "connect_ai", return_value=connection_context) as connect_ai:
            PostgresAgentRunRepository().get_for_user("workspace-1", "user-1", "run-1")

        connect_ai.assert_called_once_with()


def _run_row(
    *,
    status: str,
    error_code: str | None,
    current_plan_id: str | None,
    request_summary: str = "문서를 정리해줘",
) -> dict[str, object]:
    return {
        "id": "run-1",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "action": "folder_organize",
        "skill_version_id": None,
        "status": status,
        "request_summary": request_summary,
        "current_plan_id": current_plan_id,
        "error_code": error_code,
        "created_at": None,
        "updated_at": None,
        "finished_at": None,
    }


if __name__ == "__main__":
    unittest.main()
