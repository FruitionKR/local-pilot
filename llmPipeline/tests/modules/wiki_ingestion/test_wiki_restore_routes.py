from app.modules.wiki_ingestion.application.models import RestoreWikiCommand
from app.modules.wiki_ingestion.interfaces.http.routes import (
    restore_wiki_pages,
)
from app.modules.wiki_ingestion.interfaces.http.schemas import WikiRestoreRunIn


class RestoreUseCase:
    def __init__(self) -> None:
        self.commands: list[RestoreWikiCommand] = []

    def execute(self, command: RestoreWikiCommand) -> dict:
        self.commands.append(command)
        return {
            "operation_id": command.operation_id,
            "operation_type": "restore",
            "status": "succeeded",
            "changed_pages": [{"page_id": "C3"}],
            "failed_pages": [],
        }


def test_restore_route_preserves_keep_contribution_order() -> None:
    use_case = RestoreUseCase()

    result = restore_wiki_pages(
        WikiRestoreRunIn(
            operation_id="restore-1",
            workspace_id="ws-1",
            result_callback_url="http://backend/restore-result",
            restored_pages=["S_A", "C2"],
            deleted_pages=["C1", "C5"],
            rebuild_pages=[
                {
                    "page_id": "C3",
                    "keep_contributions": [
                        {"operation_id": "A", "document_id": "doc-A"},
                        {"operation_id": "B", "document_id": "doc-B"},
                    ],
                }
            ],
        ),
        use_case=use_case,
    )

    assert result["status"] == "succeeded"
    assert [
        item.operation_id
        for item in use_case.commands[0]
        .rebuild_pages[0]
        .keep_contributions
    ] == ["A", "B"]
    assert use_case.commands[0].restored_pages == ("S_A", "C2")
    assert use_case.commands[0].deleted_pages == ("C1", "C5")


def test_restore_route_requires_result_callback_url() -> None:
    for payload in (
        {
            "operation_id": "restore-1",
            "workspace_id": "ws-1",
            "rebuild_pages": [],
        },
        {
            "operation_id": "restore-1",
            "workspace_id": "ws-1",
            "result_callback_url": "   ",
            "rebuild_pages": [],
        },
    ):
        try:
            WikiRestoreRunIn(**payload)
        except ValueError as exc:
            assert "result_callback_url" in str(exc)
        else:
            raise AssertionError("restore 요청은 callback URL이 필요해야 한다")
