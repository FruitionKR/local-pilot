import unittest

from app.modules.markdown_edit.infrastructure.markdown_syntax_validation import validate_markdown_syntax


class MarkdownSyntaxValidationTest(unittest.TestCase):
    def test_accepts_supported_markdown_structures(self) -> None:
        markdown = (
            "---\ntitle: 배포 가이드\n---\n\n"
            "###### 확인\n\n"
            "| 항목 | 상태 |\n| --- | --- |\n| API \\| SDK | 완료 |\n\n"
            "- [x] 검증\n\n"
            "[문서](https://example.com)\n\n"
            "![구조도](https://example.com/image.png)\n\n"
            "~~~~python\nmarker = '```'\n~~~~\n\n"
            "$$\nE = mc^2\n$$"
        )

        self.assertEqual(validate_markdown_syntax(markdown), [])

    def test_rejects_unclosed_backtick_and_tilde_fences(self) -> None:
        cases = (
            ("```python\nprint(1)", "fenced code block opened at line 1 must be closed"),
            ("본문\n\n~~~python\nprint(1)", "fenced code block opened at line 3 must be closed"),
        )

        for markdown, failure in cases:
            with self.subTest(markdown=markdown):
                self.assertIn(failure, validate_markdown_syntax(markdown))

    def test_does_not_close_long_fence_with_short_marker(self) -> None:
        markdown = "````markdown\n```\n````"

        self.assertEqual(validate_markdown_syntax(markdown), [])

    def test_rejects_unclosed_frontmatter(self) -> None:
        markdown = "---\ntitle: 배포 가이드\nstatus: draft\n\n# 본문"

        self.assertIn(
            "frontmatter opened at line 1 must be closed",
            validate_markdown_syntax(markdown),
        )

    def test_allows_document_starting_with_divider(self) -> None:
        markdown = "---\n\n본문"

        self.assertEqual(validate_markdown_syntax(markdown), [])

    def test_rejects_unclosed_display_math_outside_code_fence(self) -> None:
        markdown = "```text\n$$\n```\n\n$$\nE = mc^2"

        self.assertEqual(
            validate_markdown_syntax(markdown),
            ["display math opened at line 5 must be closed"],
        )


if __name__ == "__main__":
    unittest.main()
