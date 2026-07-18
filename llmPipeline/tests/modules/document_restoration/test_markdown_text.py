from app.modules.document_restoration.domain.markdown_text import (
    is_valid_markdown_table,
    strip_markdown_fence,
)


def test_strips_complete_markdown_fence() -> None:
    assert strip_markdown_fence("```markdown\n# 제목\n```") == "# 제목"


def test_keeps_incomplete_markdown_fence() -> None:
    text = "```markdown\n# 제목"

    assert strip_markdown_fence(text) == text


def test_validates_markdown_table_shape() -> None:
    assert is_valid_markdown_table("| 이름 | 값 |\n| --- | --- |\n| A | 1 |")
    assert not is_valid_markdown_table("| 이름 | 값 |\n| --- |\n| A | 1 |")
