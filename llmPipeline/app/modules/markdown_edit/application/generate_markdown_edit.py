from app.modules.markdown_edit.application.ports import MarkdownEditorPort
from app.modules.markdown_edit.domain.entities import MarkdownEditRequest, MarkdownEditResult


class GenerateMarkdownEditUseCase:
    def __init__(self, editor: MarkdownEditorPort) -> None:
        self._editor = editor

    def execute(self, request: MarkdownEditRequest) -> MarkdownEditResult:
        if not request.instruction.strip():
            raise ValueError("Edit instruction is required.")
        if not request.markdown.strip():
            raise ValueError("Markdown is required.")
        if request.target.start_line < 1:
            raise ValueError("target.start_line must be greater than 0.")
        if request.target.end_line < request.target.start_line:
            raise ValueError("target.end_line must be greater than or equal to target.start_line.")

        result = self._editor.generate_edit(request)
        if result.edit.operation != "replace":
            raise ValueError("Only replace operation is supported.")
        if result.edit.target != request.target:
            raise ValueError("Edit target must match the requested target.")
        if not result.edit.replacement_markdown.strip():
            raise ValueError("replacement_markdown must not be empty.")
        return result
