from app.modules.wiki_ingestion.application.models import (
    IngestOperationRestoreCommand,
    LintOperationRestoreCommand,
)
from app.modules.wiki_ingestion.interfaces.http.routes import (
    router,
    restore_ingest_operation,
    restore_lint_operation,
)
from app.modules.wiki_ingestion.interfaces.http.schemas import (
    IngestOperationRestoreIn,
    LintOperationRestoreIn,
)


class RestoreUseCase:
    def __init__(self) -> None:
        self.commands: list[
            IngestOperationRestoreCommand | LintOperationRestoreCommand
        ] = []

    def execute_ingest(self, command: IngestOperationRestoreCommand) -> dict:
        self.commands.append(command)
        return {"operation_type": "ingest_restore", "status": "succeeded"}

    def execute_lint(self, command: LintOperationRestoreCommand) -> dict:
        self.commands.append(command)
        return {"operation_type": "lint_restore", "status": "succeeded"}


def test_operation_restore_routes_are_split_by_operation_type() -> None:
    paths = {route.path for route in router.routes}

    assert "/wiki/ingest-restore-runs" in paths
    assert "/wiki/lint-restore-runs" in paths
    assert "/wiki/restore-runs" not in paths


def test_ingest_restore_route_preserves_source_and_contribution_order() -> None:
    use_case = RestoreUseCase()

    result = restore_ingest_operation(
        IngestOperationRestoreIn(
            operation_id="restore-1",
            target_operation_id="ingest-B",
            workspace_id="ws-1",
            result_callback_url="http://backend/result",
            source_page={
                "page_id": "S1",
                "restore_from_operation_id": "ingest-A",
            },
            rebuild_pages=[
                {
                    "page_id": "C3",
                    "keep_contributions": [
                        {"operation_id": "A", "document_id": "doc-A"},
                        {"operation_id": "L", "document_id": "lint:L"},
                    ],
                }
            ],
        ),
        use_case=use_case,
    )

    command = use_case.commands[0]
    assert result["operation_type"] == "ingest_restore"
    assert command.target_operation_id == "ingest-B"
    assert command.source_page.restore_from_operation_id == "ingest-A"
    assert [
        item.operation_id for item in command.rebuild_pages[0].keep_contributions
    ] == ["A", "L"]


def test_lint_restore_route_identifies_deleted_affected_pages() -> None:
    use_case = RestoreUseCase()

    result = restore_lint_operation(
        LintOperationRestoreIn(
            operation_id="restore-2",
            target_operation_id="lint-B",
            workspace_id="ws-1",
            result_callback_url="http://backend/result",
            rebuild_pages=[],
            deleted_pages=["C9"],
        ),
        use_case=use_case,
    )

    command = use_case.commands[0]
    assert result["operation_type"] == "lint_restore"
    assert command.target_operation_id == "lint-B"
    assert command.deleted_pages == ("C9",)


def test_operation_restore_rejects_cancelled_operation_as_active_input() -> None:
    invalid_payloads = (
        {
            "operation_id": "restore-1",
            "target_operation_id": "ingest-B",
            "workspace_id": "ws-1",
            "result_callback_url": "http://backend/result",
            "source_page": {
                "page_id": "S1",
                "restore_from_operation_id": "ingest-B",
            },
            "rebuild_pages": [],
        },
        {
            "operation_id": "restore-1",
            "target_operation_id": "lint-B",
            "workspace_id": "ws-1",
            "result_callback_url": "http://backend/result",
            "rebuild_pages": [
                {
                    "page_id": "C3",
                    "keep_contributions": [
                        {
                            "operation_id": "lint-B",
                            "document_id": "lint:lint-B",
                        }
                    ],
                }
            ],
        },
    )

    for payload, schema in zip(
        invalid_payloads,
        (IngestOperationRestoreIn, LintOperationRestoreIn),
        strict=True,
    ):
        try:
            schema(**payload)
        except ValueError as exc:
            assert "target_operation_id" in str(exc)
        else:
            raise AssertionError("취소 대상 operation은 활성 입력에 포함할 수 없다")
