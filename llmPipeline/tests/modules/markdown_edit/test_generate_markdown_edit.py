import unittest

from app.modules.markdown_edit.application.generate_markdown_edit import GenerateMarkdownEditUseCase
from app.modules.markdown_edit.domain.entities import (
    MarkdownEditOperation,
    MarkdownEditRequest,
    MarkdownEditResult,
    MarkdownEditTarget,
)


class FakeMarkdownEditor:
    def __init__(self, result: MarkdownEditResult) -> None:
        self.result = result
        self.requests: list[MarkdownEditRequest] = []

    def generate_edit(self, request: MarkdownEditRequest) -> MarkdownEditResult:
        self.requests.append(request)
        return self.result


class GenerateMarkdownEditUseCaseTest(unittest.TestCase):
    def test_returns_replace_operation_for_requested_target(self) -> None:
        target = MarkdownEditTarget(type="selection", start_line=2, end_line=4)
        editor = FakeMarkdownEditor(
            MarkdownEditResult(
                edit=MarkdownEditOperation(
                    operation="replace",
                    target=target,
                    summary="선택 영역을 표로 바꿨습니다.",
                    replacement_markdown="| 항목 | 내용 |\n| --- | --- |\n| A | B |",
                )
            )
        )
        use_case = GenerateMarkdownEditUseCase(editor)

        result = use_case.execute(
            MarkdownEditRequest(
                instruction="표로 바꿔줘",
                markdown="# 제목\n\nA: B",
                target=target,
            )
        )

        self.assertEqual(result.edit.target, target)
        self.assertEqual(result.edit.operation, "replace")
        self.assertIn("| 항목 | 내용 |", result.edit.replacement_markdown)
        self.assertEqual(editor.requests[0].instruction, "표로 바꿔줘")

    def test_rejects_empty_replacement_markdown(self) -> None:
        target = MarkdownEditTarget(type="selection", start_line=1, end_line=1)
        use_case = GenerateMarkdownEditUseCase(
            FakeMarkdownEditor(
                MarkdownEditResult(
                    edit=MarkdownEditOperation(
                        operation="replace",
                        target=target,
                        summary="빈 결과입니다.",
                        replacement_markdown="",
                    )
                )
            )
        )

        with self.assertRaises(ValueError):
            use_case.execute(
                MarkdownEditRequest(
                    instruction="줄여줘",
                    markdown="긴 문장입니다.",
                    target=target,
                )
            )


if __name__ == "__main__":
    unittest.main()
