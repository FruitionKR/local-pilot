from fastapi import HTTPException
import pytest

from app.modules.agent_run.interfaces.http.routes import get_markdown_agent_run


class ScopedRepository:
    def get_markdown_turn_status(self, workspace_id: str, user_id: str, run_id: str):
        if (workspace_id, user_id, run_id) != ("workspace-1", "user-1", "run-1"):
            return None
        return {
            "id": run_id,
            "document_id": "document-1",
            "base_version": 7,
            "apply_operation_id": "op-1",
            "status": "completed",
            "result": {"edit": {"changed": True}},
            "error_code": None,
        }


def test_internal_status_lookup_is_scoped_to_workspace_and_user() -> None:
    response = get_markdown_agent_run(
        "run-1", "workspace-1", "user-1", ScopedRepository()
    )

    assert response.id == "run-1"
    assert response.document_id == "document-1"

    with pytest.raises(HTTPException) as raised:
        get_markdown_agent_run("run-1", "workspace-2", "user-1", ScopedRepository())

    assert raised.value.status_code == 404
