from __future__ import annotations

import hashlib
from collections.abc import Callable
from typing import Any

from app.modules.wiki_ingestion.application.models import RebuildPageCommand
from app.modules.wiki_ingestion.application.ports import WikiPageRestorePort
from app.modules.wiki_ingestion.domain.operation_recovery import PageRebuildError
from app.modules.wiki_ingestion.infrastructure.concept_contribution_rebuild import (
    load_concept_contributions,
    rebuild_concept_page,
)


class ObjectStorageWikiPageRestore(WikiPageRestorePort):
    def __init__(
        self,
        read_text: Callable[[str], str],
        write_text: Callable[[str, str, str], str],
    ) -> None:
        self._read_text = read_text
        self._write_text = write_text

    def rebuild_page(
        self,
        operation_id: str,
        workspace_id: str,
        page: RebuildPageCommand,
    ) -> dict[str, Any]:
        try:
            contributions = load_concept_contributions(
                workspace_id=workspace_id,
                page_id=page.page_id,
                keep_contributions=[
                    {
                        "operation_id": item.operation_id,
                        "sequence": sequence,
                    }
                    for sequence, item in enumerate(
                        page.keep_contributions,
                        start=1,
                    )
                ],
                read_text=self._read_text,
            )
            rebuilt = rebuild_concept_page(contributions)
            markdown_key = (
                f"wiki/{workspace_id}/pages/{page.page_id}/ops/"
                f"{operation_id}.md"
            )
            self._write_text(
                markdown_key,
                rebuilt.markdown,
                "text/markdown; charset=utf-8",
            )
        except PageRebuildError:
            raise
        except Exception as exc:
            raise PageRebuildError(
                f"failed to rebuild page:{page.page_id}"
            ) from exc
        digest = hashlib.sha256(rebuilt.markdown.encode("utf-8")).hexdigest()
        return {
            "page_id": page.page_id,
            "markdown_key": markdown_key,
            "content_hash": f"sha256:{digest}",
            "supported_links": list(rebuilt.supported_links),
        }
