from __future__ import annotations

import re

from markdown_it import MarkdownIt


def validate_markdown_syntax(markdown: str) -> list[str]:
    failures: list[str] = []
    try:
        _markdown_parser().parse(markdown)
    except Exception:  # pragma: no cover - markdown-it 내부 오류에 대한 안전 경계
        return ["Markdown parser rejected output"]

    lines = markdown.splitlines()
    content_start, frontmatter_failures = _frontmatter_boundary(lines)
    failures.extend(frontmatter_failures)
    failures.extend(_unclosed_structure_failures(lines, content_start))
    return failures


def _markdown_parser() -> MarkdownIt:
    return MarkdownIt("commonmark").enable("table")


def _frontmatter_boundary(lines: list[str]) -> tuple[int, list[str]]:
    if not lines or lines[0].strip() != "---":
        return 0, []

    first_content_line = next(
        (index for index, line in enumerate(lines[1:], start=1) if line.strip()),
        None,
    )
    if first_content_line is None:
        return 0, []
    if lines[first_content_line].strip() == "---":
        return first_content_line + 1, []
    if not re.match(r"^[A-Za-z_][A-Za-z0-9_-]*\s*:", lines[first_content_line]):
        return 0, []

    closing_line = next(
        (
            index
            for index, line in enumerate(lines[first_content_line + 1 :], start=first_content_line + 1)
            if line.strip() == "---"
        ),
        None,
    )
    if closing_line is None:
        return len(lines), ["frontmatter opened at line 1 must be closed"]
    return closing_line + 1, []


def _unclosed_structure_failures(lines: list[str], start_index: int) -> list[str]:
    fence_character: str | None = None
    fence_length = 0
    fence_start_line = 0
    math_start_line = 0

    for index, line in enumerate(lines[start_index:], start=start_index):
        line_number = index + 1
        if fence_character is not None:
            closing_fence = re.match(r"^ {0,3}(`{3,}|~{3,})[\t ]*$", line)
            if closing_fence:
                marker = closing_fence.group(1)
                if marker[0] == fence_character and len(marker) >= fence_length:
                    fence_character = None
                    fence_length = 0
                    fence_start_line = 0
            continue

        if math_start_line:
            if line.strip() == "$$":
                math_start_line = 0
            continue

        opening_fence = re.match(r"^ {0,3}(`{3,}|~{3,})(.*)$", line)
        if opening_fence:
            marker = opening_fence.group(1)
            info = opening_fence.group(2)
            if marker[0] != "`" or "`" not in info:
                fence_character = marker[0]
                fence_length = len(marker)
                fence_start_line = line_number
            continue

        if line.strip() == "$$":
            math_start_line = line_number

    failures: list[str] = []
    if fence_character is not None:
        failures.append(f"fenced code block opened at line {fence_start_line} must be closed")
    if math_start_line:
        failures.append(f"display math opened at line {math_start_line} must be closed")
    return failures
