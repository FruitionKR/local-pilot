from __future__ import annotations

from typing import Any

from app.modules.wiki_ingestion.application.models import RestoreWikiCommand
from app.modules.wiki_ingestion.application.ports import (
    PipelineResultNotifierPort,
    WikiPageRestorePort,
)
from app.modules.wiki_ingestion.domain.operation_recovery import PageRebuildError


class RestoreWikiPagesUseCase:
    def __init__(
        self,
        page_restore: WikiPageRestorePort,
        result_notifier: PipelineResultNotifierPort | None = None,
    ) -> None:
        self._page_restore = page_restore
        self._result_notifier = result_notifier

    def execute(self, command: RestoreWikiCommand) -> dict[str, Any]:
        changed_pages: list[dict[str, Any]] = []
        failed_pages: list[dict[str, str]] = []
        for page in command.rebuild_pages:
            try:
                changed_pages.append(
                    self._page_restore.rebuild_page(
                        command.operation_id,
                        command.workspace_id,
                        page,
                    )
                )
            except PageRebuildError:
                failed_pages.append(
                    {
                        "page_id": page.page_id,
                        "reason": "contribution_missing",
                    }
                )

        status = "succeeded" if not failed_pages else "partially_succeeded"
        result = {
            "operation_id": command.operation_id,
            "operation_type": "restore",
            "status": status,
            "changed_pages": changed_pages,
            "failed_pages": failed_pages,
            "restored_pages": list(command.restored_pages),
            "deleted_pages": list(command.deleted_pages),
        }
        if command.result_callback_url and self._result_notifier is not None:
            self._result_notifier.notify(command.result_callback_url, result)
        return result
