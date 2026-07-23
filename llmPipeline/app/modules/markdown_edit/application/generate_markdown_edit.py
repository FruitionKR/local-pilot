import re
from dataclasses import replace

from app.modules.markdown_edit.application.ports import MarkdownEditorPort
from app.modules.markdown_edit.domain.entities import MarkdownEditRequest, MarkdownEditResult, operation_for_edit_goal
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
        expected_operation = operation_for_edit_goal(request.edit_goal)
        if result.edit.operation != expected_operation:
            raise ValueError(f"Edit operation must be {expected_operation}.")
        actual_target = result.edit.actual_target
        if actual_target.start_line < 1:
            raise ValueError("actual_target.start_line must be greater than 0.")
        if actual_target.end_line < actual_target.start_line:
            raise ValueError("actual_target.end_line must be greater than or equal to actual_target.start_line.")
        if actual_target.end_line > line_count:
            raise ValueError("actual_target.end_line must not exceed the Markdown line count.")
        if actual_target.type == "whole_document" and (
            actual_target.start_line != 1 or actual_target.end_line != line_count
        ):
            raise ValueError("whole_document actual_target must cover the entire Markdown document.")
        if expected_operation == "insert_after" and actual_target.type != "current_section":
            raise ValueError("insert_after operation requires a current_section actual_target.")
        if not result.edit.replacement_markdown.strip():
            raise ValueError("replacement_markdown must not be empty.")

        actual_markdown = _markdown_line_range(
            request.markdown,
            actual_target.start_line,
            actual_target.end_line,
        )
        changed = expected_operation == "insert_after" or result.edit.replacement_markdown != actual_markdown
        return replace(
            result,
            edit=replace(
                result.edit,
                requested_target=request.target,
                changed=changed,
            ),
        )


def _markdown_line_range(markdown: str, start_line: int, end_line: int) -> str:
    separators = list(re.finditer(r"\r\n|\r|\n", markdown))
    line_starts = [0, *(match.end() for match in separators)]
    start_index = line_starts[start_line - 1]
    end_index = separators[end_line - 1].start() if end_line <= len(separators) else len(markdown)
    return markdown[start_index:end_index]
