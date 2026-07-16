import json
import unittest

from app.modules.markdown_edit.domain.entities import MarkdownCreateRequest, MarkdownEditRequest, MarkdownEditTarget
from app.modules.markdown_edit.domain.markdown_output_contract import (
    MarkdownCreateOutputContractError,
    MarkdownOutputContractError,
)
from app.modules.markdown_edit.infrastructure.chat_completions_markdown_editor import ChatCompletionsMarkdownEditor


TARGET = MarkdownEditTarget(type="whole_document", start_line=1, end_line=1)


class SequenceJsonClient:
    def __init__(self, responses: list[dict[str, object]]) -> None:
        self.responses = responses
        self.calls: list[tuple[str, str]] = []

    def complete_json(self, system_prompt: str, user_prompt: str) -> dict[str, object]:
        self.calls.append((system_prompt, user_prompt))
        return self.responses.pop(0)


def response(replacement_markdown: str, operation: str = "replace") -> dict[str, object]:
    return {
        "operation": operation,
        "summary": "수정했습니다.",
        "replacement_markdown": replacement_markdown,
    }


def source_range_response(segment_id: str, replacement: str) -> dict[str, object]:
    return {
        "summary": "수정했습니다.",
        "edits": [{"id": segment_id, "replacement": replacement}],
    }


class ChatCompletionsMarkdownEditorTest(unittest.TestCase):
    def test_generates_insert_after_content_without_repeating_section(self) -> None:
        client = SequenceJsonClient([response("## 문제 해결\n\n로그를 확인합니다.", operation="insert_after")])
        editor = ChatCompletionsMarkdownEditor(client, "system")  # type: ignore[arg-type]
        request = MarkdownEditRequest(
            instruction="이 섹션 아래에 문제 해결 절을 추가해줘.",
            markdown="# 설치\n\n설치 방법입니다.",
            target=MarkdownEditTarget(type="current_section", start_line=1, end_line=3),
            edit_goal="insert_after",
        )

        result = editor.generate_edit(request)

        payload = json.loads(client.calls[0][1])
        self.assertEqual(payload["requested_operation"], "insert_after")
        self.assertEqual(result.edit.operation, "insert_after")
        self.assertEqual(result.edit.replacement_markdown, "## 문제 해결\n\n로그를 확인합니다.")

    def test_retries_markdown_create_with_contract_failures(self) -> None:
        client = SequenceJsonClient(
            [
                {"title": "대화 정리", "summary": "", "markdown": "# 대화 정리"},
                {"title": "대화 정리", "summary": "대화를 정리했습니다.", "markdown": "# 대화 정리"},
            ]
        )
        editor = ChatCompletionsMarkdownEditor(client, "system", create_system_prompt="create")  # type: ignore[arg-type]

        result = editor.generate_markdown(MarkdownCreateRequest(instruction="대화를 문서로 만들어줘."))

        self.assertEqual(result.document.summary, "대화를 정리했습니다.")
        self.assertEqual(len(client.calls), 2)
        retry_payload = json.loads(client.calls[1][1])
        self.assertIn("summary must not be empty", retry_payload["contract_failures"])

    def test_raises_after_second_markdown_create_contract_failure(self) -> None:
        client = SequenceJsonClient(
            [
                {"title": "", "summary": "", "markdown": ""},
                {"title": "", "summary": "", "markdown": ""},
            ]
        )
        editor = ChatCompletionsMarkdownEditor(client, "system", create_system_prompt="create")  # type: ignore[arg-type]

        with self.assertRaises(MarkdownCreateOutputContractError) as raised:
            editor.generate_markdown(MarkdownCreateRequest(instruction="대화를 문서로 만들어줘."))

        self.assertEqual(len(client.calls), 2)
        self.assertIn("title must not be empty", raised.exception.failures)

    def test_edits_only_selected_lines_and_sends_bounded_context(self) -> None:
        client = SequenceJsonClient(
            [source_range_response("text-0001", "선택한 문장입니다.")]
        )
        editor = ChatCompletionsMarkdownEditor(client, "system", context_lines=1)  # type: ignore[arg-type]
        request = MarkdownEditRequest(
            instruction="선택한 문장을 자연스럽게 다듬어줘.",
            markdown="첫 문장입니다.\n선택한 문장이다.\n마지막 문장입니다.",
            target=MarkdownEditTarget(type="selection", start_line=2, end_line=2),
            edit_goal="cleanup",
        )

        result = editor.generate_edit(request)

        payload = json.loads(client.calls[0][1])
        self.assertEqual(payload["markdown_context"], "{{FRUITION_TEXT_0001}}")
        self.assertEqual(payload["read_only_context"]["before"], "첫 문장입니다.")
        self.assertEqual(payload["read_only_context"]["after"], "마지막 문장입니다.")
        self.assertEqual(result.edit.replacement_markdown, "선택한 문장입니다.")
        self.assertEqual(result.edit.target, request.target)

    def test_structure_conversion_receives_only_selected_markdown(self) -> None:
        client = SequenceJsonClient([response("1. 선택한 문장")])
        editor = ChatCompletionsMarkdownEditor(client, "system", context_lines=1)  # type: ignore[arg-type]
        request = MarkdownEditRequest(
            instruction="번호 목록으로 바꿔줘.",
            markdown="앞 문장\n선택한 문장\n뒤 문장",
            target=MarkdownEditTarget(type="selection", start_line=2, end_line=2),
            edit_goal="convert_format",
        )

        result = editor.generate_edit(request)

        payload = json.loads(client.calls[0][1])
        self.assertEqual(payload["markdown"], "선택한 문장")
        self.assertEqual(result.edit.replacement_markdown, "1. 선택한 문장")

    def test_edits_source_range_without_returning_markdown_from_model(self) -> None:
        client = SequenceJsonClient(
            [source_range_response("text-0001", "배포 전에 테스트해야 한다.")]
        )
        editor = ChatCompletionsMarkdownEditor(client, "system")  # type: ignore[arg-type]
        request = MarkdownEditRequest(
            instruction="본문만 자연스럽게 다듬고 frontmatter는 유지해줘.",
            markdown="---\ntitle: 배포 가이드\n---\n\n배포를 하기 전에 테스트해야 한다.",
            target=TARGET,
            edit_goal="style_change",
        )

        result = editor.generate_edit(request)

        self.assertEqual(len(client.calls), 1)
        self.assertIn("plain-text segments", client.calls[0][0])
        sent_payload = json.loads(client.calls[0][1])
        self.assertEqual(sent_payload["mode"], "source_range_text_edit")
        self.assertIn("title: 배포 가이드", sent_payload["markdown_context"])
        self.assertNotIn("replacement_markdown", client.calls[0][1])
        self.assertTrue(result.edit.replacement_markdown.startswith("---\ntitle: 배포 가이드\n---"))

    def test_retries_source_range_edit_with_contract_failures(self) -> None:
        client = SequenceJsonClient(
            [
                source_range_response("text-9999", "잘못된 범위"),
                source_range_response("text-0001", "배포 전에 테스트한다."),
            ]
        )
        editor = ChatCompletionsMarkdownEditor(client, "system")  # type: ignore[arg-type]
        request = MarkdownEditRequest(
            instruction="문장을 자연스럽게 다듬어줘.",
            markdown="배포를 하기 전에 테스트를 한다.",
            target=TARGET,
            edit_goal="cleanup",
        )

        result = editor.generate_edit(request)

        self.assertEqual(result.edit.replacement_markdown, "배포 전에 테스트한다.")
        self.assertEqual(len(client.calls), 2)
        retry_payload = json.loads(client.calls[1][1])
        self.assertIn("unknown source range segment id", retry_payload["contract_failures"][0])

    def test_retries_once_with_contract_failures(self) -> None:
        client = SequenceJsonClient(
            [
                response("- 1. 설치\n- 2. 테스트"),
                response("1. 설치\n2. 테스트"),
            ]
        )
        editor = ChatCompletionsMarkdownEditor(client, "system")  # type: ignore[arg-type]
        request = MarkdownEditRequest(
            instruction="번호 목록으로 바꿔줘.",
            markdown="설치한 다음 테스트한다.",
            target=TARGET,
            edit_goal="convert_format",
        )

        result = editor.generate_edit(request)

        self.assertEqual(result.edit.replacement_markdown, "1. 설치\n2. 테스트")
        self.assertEqual(len(client.calls), 2)
        self.assertEqual(client.calls[0][0], "system")
        retry_payload = json.loads(client.calls[1][1])
        self.assertIn("numbered list items must start directly", retry_payload["contract_failures"][0])
        self.assertEqual(retry_payload["previous_replacement_markdown"], "- 1. 설치\n- 2. 테스트")

    def test_raises_after_second_contract_failure(self) -> None:
        client = SequenceJsonClient(
            [
                response("```markdown\nE = mc^2\n```"),
                response("```markdown\nE = mc^2\n```"),
            ]
        )
        editor = ChatCompletionsMarkdownEditor(client, "system")  # type: ignore[arg-type]
        request = MarkdownEditRequest(
            instruction="display math로 바꿔줘.",
            markdown="E = mc^2",
            target=TARGET,
            edit_goal="convert_format",
        )

        with self.assertRaises(MarkdownOutputContractError) as raised:
            editor.generate_edit(request)

        self.assertEqual(len(client.calls), 2)
        self.assertIn("display math must not be wrapped in a code fence", raised.exception.failures)


if __name__ == "__main__":
    unittest.main()
