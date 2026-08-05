from __future__ import annotations

from typing import Any

from app.modules.wiki_ingestion.application.models import (
    IngestOperationRestoreCommand,
    LintOperationRestoreCommand,
)
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

    def execute_ingest(
        self,
        command: IngestOperationRestoreCommand,
    ) -> dict[str, Any]:
        changed_pages: list[dict[str, Any]] = []
        failed_pages: list[dict[str, str]] = []
        deleted_pages = list(command.deleted_pages)
        if command.restore_to_operation_id is None:
            if command.source_page.page_id not in deleted_pages:
                deleted_pages.append(command.source_page.page_id)
        else:
            try:
                changed_pages.append(
                    self._page_restore.restore_source_page(
                        command.operation_id,
                        command.restore_to_operation_id,
                        command.workspace_id,
                        command.source_page,
                    )
                )
            except PageRebuildError:
                failed_pages.append(
                    {
                        "page_id": command.source_page.page_id,
                        "reason": "source_snapshot_missing",
                    }
                )
        concept_pages, concept_failures = self._rebuild_pages(command)
        changed_pages.extend(concept_pages)
        failed_pages.extend(concept_failures)
        result = self._result(
            operation_id=command.operation_id,
            operation_type="ingest_restore",
            restore_to_operation_id=command.restore_to_operation_id,
            cancel_operation_ids=list(command.cancel_operation_ids),
            changed_pages=changed_pages,
            failed_pages=failed_pages,
            deleted_pages=deleted_pages,
        )
        if deleted_pages:
            self._page_restore.cleanup_deleted_pages(
                command.workspace_id,
                deleted_pages,
            )
        self._notify(command.result_callback_url, result)
        return result

    def execute_lint(
        self,
        command: LintOperationRestoreCommand,
    ) -> dict[str, Any]:
        changed_pages, failed_pages = self._rebuild_pages(command)
        supported_links = [
            link
            for page in changed_pages
            for link in page.get("supported_links", [])
        ]
        failed_actions: list[dict[str, str]] = []
        if failed_pages:
            link_changes = {"removed_links": [], "restored_links": []}
            failed_actions.append(
                {
                    "action": "restore_links",
                    "resource_id": command.target_operation_id,
                    "reason": "concept_rebuild_failed",
                }
            )
        else:
            try:
                link_changes = self._page_restore.calculate_lint_action_changes(
                    command.target_operation_id,
                    command.workspace_id,
                    [
                        *[page.page_id for page in command.rebuild_pages],
                        *command.deleted_pages,
                    ],
                    supported_links,
                )
            except PageRebuildError:
                link_changes = {"removed_links": [], "restored_links": []}
                failed_actions.append(
                    {
                        "action": "restore_links",
                        "resource_id": command.target_operation_id,
                        "reason": "operation_log_missing",
                    }
                )
        result = self._result(
            operation_id=command.operation_id,
            operation_type="lint_restore",
            target_operation_id=command.target_operation_id,
            changed_pages=changed_pages,
            failed_pages=failed_pages,
            deleted_pages=list(command.deleted_pages),
            link_changes=link_changes,
            failed_actions=failed_actions,
        )
        if command.deleted_pages:
            self._page_restore.cleanup_deleted_pages(
                command.workspace_id,
                list(command.deleted_pages),
            )
        self._notify(command.result_callback_url, result)
        return result

    def _rebuild_pages(
        self,
        command: IngestOperationRestoreCommand | LintOperationRestoreCommand,
    ) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
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

        return changed_pages, failed_pages

    @staticmethod
    def _result(
        *,
        operation_id: str,
        operation_type: str,
        changed_pages: list[dict[str, Any]],
        failed_pages: list[dict[str, str]],
        **values: Any,
    ) -> dict[str, Any]:
        failed_actions = values.get("failed_actions", [])
        return {
            "operation_id": operation_id,
            "operation_type": operation_type,
            "status": (
                "succeeded"
                if not failed_pages and not failed_actions
                else "partially_succeeded"
            ),
            "changed_pages": changed_pages,
            "failed_pages": failed_pages,
            **values,
        }

    def _notify(self, callback_url: str | None, result: dict[str, Any]) -> None:
        if callback_url and self._result_notifier is not None:
            self._result_notifier.notify(callback_url, result)
