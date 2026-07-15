from app.modules.markdown_edit.application.ports import MarkdownEditorPort
from app.modules.markdown_edit.domain.entities import MarkdownEditRequest, MarkdownEditResult
from app.modules.markdown_edit.domain.markdown_target_scope import markdown_line_count


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
        line_count = markdown_line_count(request.markdown)
        if request.target.end_line > line_count:
            raise ValueError("target.end_line must not exceed the Markdown line count.")
        if request.target.type == "whole_document" and (
            request.target.start_line != 1 or request.target.end_line != line_count
        ):
            raise ValueError("whole_document target must cover the entire Markdown document.")
        if request.edit_goal == "insert_after" and request.target.type != "current_section":
            raise ValueError("insert_after operation requires a current_section target.")

        result = self._editor.generate_edit(request)
        expected_operation = "insert_after" if request.edit_goal == "insert_after" else "replace"
        if result.edit.operation != expected_operation:
            raise ValueError(f"Edit operation must be {expected_operation}.")
        if result.edit.target != request.target:
            raise ValueError("Edit target must match the requested target.")
        if not result.edit.replacement_markdown.strip():
            raise ValueError("replacement_markdown must not be empty.")
        return result
