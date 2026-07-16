from dataclasses import dataclass
from typing import Literal


TargetType = Literal["selection", "current_section", "whole_document"]
EditOperationType = Literal["replace", "insert_after"]


def operation_for_edit_goal(edit_goal: str | None) -> EditOperationType:
    return "insert_after" if edit_goal == "insert_after" else "replace"


@dataclass(frozen=True)
class MarkdownEditTarget:
    type: TargetType
    start_line: int
    end_line: int


@dataclass(frozen=True)
class MarkdownEditRequest:
    instruction: str
    markdown: str
    target: MarkdownEditTarget
    conversation_summary: str | None = None
    edit_goal: str | None = None


@dataclass(frozen=True)
class MarkdownEditOperation:
    operation: EditOperationType
    target: MarkdownEditTarget
    summary: str
    replacement_markdown: str


@dataclass(frozen=True)
class MarkdownEditResult:
    edit: MarkdownEditOperation


@dataclass(frozen=True)
class MarkdownCreateRequest:
    instruction: str
    conversation_summary: str | None = None
    reference_context: dict[str, object] | None = None


@dataclass(frozen=True)
class GeneratedMarkdownDocument:
    title: str
    summary: str
    markdown: str


@dataclass(frozen=True)
class MarkdownCreateResult:
    document: GeneratedMarkdownDocument
