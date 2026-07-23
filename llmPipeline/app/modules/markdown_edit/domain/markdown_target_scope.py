from __future__ import annotations

import re
from dataclasses import dataclass

from app.modules.markdown_edit.domain.entities import MarkdownEditTarget


@dataclass(frozen=True)
class MarkdownTargetScope:
    markdown: str
    context_before: str
    context_after: str
    start_line: int
    end_line: int


class MarkdownTargetBoundaryError(ValueError):
    def __init__(self, structure: str, start_line: int, end_line: int) -> None:
        super().__init__(f"target partially overlaps {structure} at lines {start_line}-{end_line}")
        self.structure = structure
        self.start_line = start_line
        self.end_line = end_line


def build_markdown_target_scope(
    markdown: str,
    target: MarkdownEditTarget,
    context_lines: int,
) -> MarkdownTargetScope:
    lines = re.split(r"\r\n|\r|\n", markdown)
    if target.end_line > len(lines):
        raise ValueError("target.end_line must not exceed the Markdown line count.")

    if target.type == "whole_document":
        return MarkdownTargetScope(
            markdown=markdown,
            context_before="",
            context_after="",
            start_line=1,
            end_line=len(lines),
        )

    start_index = target.start_line - 1
    end_index = target.end_line
    context_lines = max(0, context_lines)
    context_start = max(0, start_index - context_lines)
    context_end = min(len(lines), end_index + context_lines)
    return MarkdownTargetScope(
        markdown="\n".join(lines[start_index:end_index]),
        context_before="\n".join(lines[context_start:start_index]),
        context_after="\n".join(lines[end_index:context_end]),
        start_line=context_start + 1,
        end_line=context_end,
    )


def markdown_line_count(markdown: str) -> int:
    return len(re.split(r"\r\n|\r|\n", markdown))
