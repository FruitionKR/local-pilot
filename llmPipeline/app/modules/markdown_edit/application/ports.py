from typing import Protocol

from app.modules.markdown_edit.domain.entities import MarkdownEditRequest, MarkdownEditResult


class MarkdownEditorPort(Protocol):
    def generate_edit(self, request: MarkdownEditRequest) -> MarkdownEditResult:
        ...
