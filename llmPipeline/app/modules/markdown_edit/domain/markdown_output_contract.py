from __future__ import annotations

import re
from dataclasses import dataclass

from app.modules.markdown_edit.domain.entities import GeneratedMarkdownDocument, MarkdownEditRequest


PROTECTED_EDIT_GOALS = {"cleanup", "style_change", "shorten"}
PLAIN_TEXT_EDIT_GOALS = {"cleanup", "style_change", "translate", "shorten"}
PRESERVE_WORDS = ("유지", "보존", "그대로", "preserve", "keep", "unchanged")
CHANGE_WORDS = ("수정", "변경", "바꿔", "교체", "change", "edit", "replace")
STRUCTURE_WORDS = ("frontmatter", "이미지", "image", "표", "table", "각주", "footnote", "링크", "link", "code")


@dataclass(frozen=True)
class ProtectedFragment:
    kind: str
    token: str
    markdown: str


@dataclass(frozen=True)
class ProtectedMarkdown:
    markdown: str
    fragments: tuple[ProtectedFragment, ...]

    def restore(self, replacement: str) -> tuple[str, list[str]]:
        restored = replacement
        failures: list[str] = []
        for fragment in self.fragments:
            count = restored.count(fragment.token)
            if count == 0 and fragment.kind == "frontmatter":
                restored = f"{fragment.markdown}\n\n{restored.lstrip()}"
                continue
            if count != 1:
                failures.append(f"protected token count mismatch: {fragment.token}={count}")
                continue
            if fragment.kind == "footnote_definition":
                before, _, after = restored.partition(fragment.token)
                suffix = f"\n\n{after.lstrip()}" if after.strip() else ""
                restored = f"{before.rstrip()}\n\n{fragment.markdown}{suffix}"
                continue
            restored = restored.replace(fragment.token, fragment.markdown)
        return restored, failures


class MarkdownOutputContractError(ValueError):
    def __init__(self, failures: list[str], replacement_markdown: str) -> None:
        super().__init__("Markdown output contract failed: " + "; ".join(failures))
        self.failures = failures
        self.replacement_markdown = replacement_markdown


class MarkdownCreateOutputContractError(ValueError):
    def __init__(self, failures: list[str], output: dict[str, object]) -> None:
        super().__init__("Markdown create output contract failed: " + "; ".join(failures))
        self.failures = failures
        self.output = output


def validate_markdown_create_output(document: GeneratedMarkdownDocument) -> list[str]:
    failures: list[str] = []
    if not document.title.strip():
        failures.append("title must not be empty")
    if not document.summary.strip():
        failures.append("summary must not be empty")
    if not document.markdown.strip():
        failures.append("markdown must not be empty")
    return failures


def protect_markdown(request: MarkdownEditRequest) -> ProtectedMarkdown:
    if request.edit_goal not in PROTECTED_EDIT_GOALS or _explicit_structure_change(request.instruction):
        return ProtectedMarkdown(markdown=request.markdown, fragments=())

    markdown = request.markdown
    fragments: list[ProtectedFragment] = []
    patterns = (
        ("frontmatter", r"(?s)\A---\r?\n.*?\r?\n---(?=\r?\n|\Z)"),
        ("code_fence", r"(?ms)^(`{3,}|~{3,})[^\n]*\n.*?^\1\s*$"),
        ("indented_code", r"(?m)(?:^(?: {4}|\t)[^\n]*(?:\r?\n|$))+"),
        ("display_math", r"(?s)\$\$.*?\$\$"),
        ("inline_code", r"(?<!`)(`+)[^`\n]+\1(?!`)"),
        (
            "table",
            r"(?m)^(?=[^\n]*\|)[^\n]+\r?\n"
            r"^[ \t]*\|?[ \t]*:?-{3,}:?[ \t]*(?:\|[ \t]*:?-{3,}:?[ \t]*)+\|?[ \t]*"
            r"(?:\r?\n(?=[^\n]*\|)[^\n]*)*",
        ),
        ("image", r"!\[[^\]\n]*\]\([^\)\n]+\)"),
        ("link", r"(?<!!)\[[^\]\n]+\]\([^\)\n]+\)"),
        ("footnote_definition", r"(?m)^\[\^[^\]\n]+\]:[^\n]*(?:\r?\n(?: {2,}|\t)[^\n]*)*"),
        ("divider", r"(?m)^(?:---|\*\*\*)$"),
    )
    for kind, pattern in patterns:
        markdown = re.sub(
            pattern,
            lambda match, kind=kind: _protect_match(kind, match.group(0), fragments),
            markdown,
        )
    return ProtectedMarkdown(markdown=markdown, fragments=tuple(fragments))


def validate_markdown_output(request: MarkdownEditRequest, replacement: str) -> list[str]:
    failures: list[str] = []
    instruction = request.instruction.lower()
    nonempty_lines = [line for line in replacement.splitlines() if line.strip()]

    if request.edit_goal == "checklist":
        if not nonempty_lines or not all(re.match(r"^- \[ \] ", line) for line in nonempty_lines):
            failures.append("checklist items must all start with `- [ ] `")

    if request.edit_goal == "insert_after":
        source_heading = next((line.strip() for line in request.markdown.splitlines() if line.strip()), "")
        replacement_lines = {line.strip() for line in replacement.splitlines() if line.strip()}
        if source_heading.startswith("#") and source_heading in replacement_lines:
            failures.append("insert_after output must not repeat the current section heading")

    if request.edit_goal == "bullet_list":
        if not nonempty_lines or not all(re.match(r"^\s*[-*+]\s+", line) for line in nonempty_lines):
            failures.append("bullet list items must use plain bullet markers")
        if any(re.match(r"^\s*[-*+]\s+\[[ xX]\]\s+", line) for line in nonempty_lines):
            failures.append("plain bullet list must not contain checkboxes")

    if _asks_for_numbered_list(instruction):
        if not nonempty_lines or not all(re.match(r"^\d+\.\s+", line) for line in nonempty_lines):
            failures.append("numbered list items must start directly with `1.`, `2.`, and so on")

    if _asks_for_blockquote(instruction) and not any(line.startswith("> ") for line in nonempty_lines):
        failures.append("blockquote marker `> ` must start its line")

    if _asks_for_heading(instruction) and not any(re.match(r"^#{1,6}\s+", line) for line in nonempty_lines):
        failures.append("heading must start with `# ` through `###### `")

    if _asks_for_bold(instruction) and not re.search(r"\*\*[^*\n]+\*\*", replacement):
        failures.append("bold text must use `**` delimiters")

    if _asks_for_italic(instruction) and not re.search(r"(?<!\*)\*[^*\n]+\*(?!\*)", replacement):
        failures.append("italic text must use `*` delimiters")

    if _asks_for_strikethrough(instruction) and not re.search(r"~~[^~\n]+~~", replacement):
        failures.append("strikethrough text must use `~~` delimiters")

    if _asks_for_display_math(instruction):
        if not re.search(r"\$\$[\s\S]+\$\$", replacement):
            failures.append("display math must use `$$` delimiters")
        if "```" in replacement or "~~~" in replacement:
            failures.append("display math must not be wrapped in a code fence")

    if "mermaid" in instruction:
        if not re.search(r"```mermaid\s+[\s\S]+```", replacement):
            failures.append("Mermaid output must use a `mermaid` code fence")
        if "flowchart" not in replacement or "-->" not in replacement:
            failures.append("Mermaid output must contain flowchart edges")
        if _asks_for_linear_sequence(instruction) and ("{" in replacement or "}" in replacement):
            failures.append("linear Mermaid sequence must not invent a decision branch")

    if _asks_for_meeting_notes(instruction):
        for heading in ("## 논의 사항", "## 결정 사항", "## 다음 작업"):
            if heading not in replacement:
                failures.append(f"meeting notes must contain `{heading}`")

    if request.edit_goal in PLAIN_TEXT_EDIT_GOALS and not _source_starts_with_list(request.markdown):
        if re.match(r"^\s*(?:[-*+]\s+|\d+\.\s+)", replacement):
            failures.append("plain-text edit must not add a list marker")

    if request.edit_goal in PROTECTED_EDIT_GOALS:
        for marker in set(re.findall(r"\[\^[^\]\n]+\]", request.markdown)):
            if replacement.count(marker) < request.markdown.count(marker):
                failures.append(f"footnote reference count must be preserved: {marker}")
        if not re.search(r"[\u3400-\u4dbf\u4e00-\u9fff]", request.markdown) and re.search(
            r"[\u3400-\u4dbf\u4e00-\u9fff]", replacement
        ):
            failures.append("Korean text edit must not introduce Han characters absent from the source")

    if request.edit_goal == "shorten":
        if _asks_for_one_sentence(instruction) and "\n" not in request.markdown and "\n" in replacement.strip():
            failures.append("one-sentence shortening must stay on one line")
        if _asks_to_shorten(instruction) and len(replacement.strip()) >= len(request.markdown.strip()):
            failures.append("shortening result must be shorter than the source")
        for anchor in _literal_anchors(request.markdown):
            if anchor not in replacement:
                failures.append(f"shortening must preserve literal anchor: {anchor}")

    return failures


def repair_markdown_output(request: MarkdownEditRequest, replacement: str) -> str:
    if _asks_for_display_math(request.instruction.lower()):
        fenced = re.fullmatch(r"```(?:markdown)?\s*\n([\s\S]*?)\n```", replacement.strip())
        if fenced and "$$" in fenced.group(1):
            replacement = fenced.group(1).strip()

    if request.edit_goal not in PROTECTED_EDIT_GOALS:
        return replacement

    repaired = replacement
    for marker in set(re.findall(r"\[\^[^\]\n]+\]", request.markdown)):
        deficit = request.markdown.count(marker) - repaired.count(marker)
        if deficit <= 0:
            continue
        label = marker[2:-1]
        for malformed in (f"[[{label}]]", f"[{label}]"):
            replacements = min(deficit, repaired.count(malformed))
            if replacements:
                repaired = repaired.replace(malformed, marker, replacements)
                deficit -= replacements
            if deficit == 0:
                break
    return repaired


def _protect_match(kind: str, markdown: str, fragments: list[ProtectedFragment]) -> str:
    token = f"{{{{FRUITION_PROTECTED_{len(fragments) + 1:04d}}}}}"
    fragments.append(ProtectedFragment(kind=kind, token=token, markdown=markdown))
    return token


def _explicit_structure_change(instruction: str) -> bool:
    lowered = instruction.lower()
    if any(word in lowered for word in PRESERVE_WORDS):
        return False
    return any(word in lowered for word in STRUCTURE_WORDS) and any(word in lowered for word in CHANGE_WORDS)


def _asks_for_numbered_list(instruction: str) -> bool:
    return any(marker in instruction for marker in ("번호 목록", "번호 리스트", "numbered list", "ordered list"))


def _asks_for_blockquote(instruction: str) -> bool:
    return "인용문" in instruction or "blockquote" in instruction


def _asks_for_heading(instruction: str) -> bool:
    return "제목" in instruction or "heading" in instruction


def _asks_for_bold(instruction: str) -> bool:
    return "굵게" in instruction or "bold" in instruction


def _asks_for_italic(instruction: str) -> bool:
    return "기울임" in instruction or "italic" in instruction


def _asks_for_strikethrough(instruction: str) -> bool:
    return "취소선" in instruction or "strikethrough" in instruction


def _asks_for_display_math(instruction: str) -> bool:
    return "display math" in instruction or "블록 수식" in instruction


def _asks_for_linear_sequence(instruction: str) -> bool:
    return any(marker in instruction for marker in ("순서", "sequence", "linear"))


def _asks_for_meeting_notes(instruction: str) -> bool:
    return any(marker in instruction for marker in ("회의록", "meeting notes", "minutes"))


def _asks_for_one_sentence(instruction: str) -> bool:
    return "한 문장" in instruction or "one sentence" in instruction


def _asks_to_shorten(instruction: str) -> bool:
    return any(marker in instruction for marker in ("짧게", "줄여", "축약", "shorten", "concise"))


def _literal_anchors(markdown: str) -> set[str]:
    patterns = (
        r"https?://[^\s)\]>]+",
        r"(?<![\w.])\d+(?:[.,]\d+)*(?:%|초|분|시간|일|주|개월|년|명|개|건|KB|MB|GB|TB)?",
        r"\b[A-Z][A-Z0-9_]{1,}\b",
        r"\b[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+\b",
        r"\b[a-z]+(?:[A-Z][A-Za-z0-9]*)+\b",
    )
    return {match.group(0) for pattern in patterns for match in re.finditer(pattern, markdown)}


def _source_starts_with_list(markdown: str) -> bool:
    return bool(re.match(r"^\s*(?:[-*+]\s+|\d+\.\s+)", markdown))
