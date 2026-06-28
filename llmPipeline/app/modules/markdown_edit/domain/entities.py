from dataclasses import dataclass
from typing import Literal


TargetType = Literal["selection", "current_section"]


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


@dataclass(frozen=True)
class MarkdownEditOperation:
    operation: Literal["replace"]
    target: MarkdownEditTarget
    summary: str
    replacement_markdown: str


@dataclass(frozen=True)
class MarkdownEditResult:
    edit: MarkdownEditOperation
