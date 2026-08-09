import re


HEADING_PATTERN = re.compile(r"^ {0,3}#{1,6}\s+\S")
LIST_ITEM_PATTERN = re.compile(r"^(\s*)(?:[-+*]|\d+[.)])\s+\S")
TABLE_SEPARATOR_PATTERN = re.compile(r"^\s*\|?(?:\s*:?-+:?\s*\|)+\s*:?-+:?\s*\|?\s*$")
FIXED_TEMPLATE_START = "# 고정 출력 템플릿\n\n```markdown\n"
FIXED_TEMPLATE_END = "\n```"


def extract_markdown_structure(markdown: str) -> str:
    lines = markdown.splitlines()
    structure: list[str] = []
    fence_marker: str | None = None
    for index, line in enumerate(lines):
        stripped = line.lstrip()
        if stripped.startswith(("```", "~~~")):
            marker = stripped[:3]
            if fence_marker is None:
                fence_marker = marker
            elif fence_marker == marker:
                fence_marker = None
            continue
        if fence_marker is not None:
            continue
        if HEADING_PATTERN.match(line):
            structure.append(line.rstrip())
            continue
        list_item = LIST_ITEM_PATTERN.match(line)
        if list_item:
            marker = line[len(list_item.group(1)) :].split(maxsplit=1)[0]
            structure.append(f"{list_item.group(1)}{marker} [item]")
            continue
        if index + 1 < len(lines) and "|" in line and TABLE_SEPARATOR_PATTERN.match(lines[index + 1]):
            structure.extend((line.rstrip(), lines[index + 1].rstrip()))
    return "\n".join(structure)


def build_reference_template_instructions(template: str) -> str:
    return (
        "# 작성 규칙\n\n"
        "- 입력 내용을 아래 템플릿 구조에 맞춰 작성한다.\n"
        "- 제목, 섹션 순서, 목록과 표 구조를 변경하지 않는다.\n"
        "- 제공되지 않은 내용을 추측하지 않는다.\n\n"
        f"{FIXED_TEMPLATE_START}{template}{FIXED_TEMPLATE_END}"
    )


def extract_fixed_reference_template(instructions: str) -> str | None:
    start = instructions.find(FIXED_TEMPLATE_START)
    if start < 0:
        return None
    start += len(FIXED_TEMPLATE_START)
    end = instructions.find(FIXED_TEMPLATE_END, start)
    if end < 0:
        return None
    template = instructions[start:end]
    return template if template.strip() else None
