import unittest

from app.modules.query.domain.entities import GraphContext, QueryContext
from app.modules.query.infrastructure.query_chat_answer_generator import (
    QUERY_ANSWER_SYSTEM_PROMPT,
    QueryChatAnswerGenerator,
)


class FakeChatClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    def complete_text(self, system_prompt: str, user_prompt: str) -> str:
        self.calls.append((system_prompt, user_prompt))
        return "  한국어 답변입니다.  "


class QueryChatAnswerGeneratorTest(unittest.TestCase):
    def test_generates_answer_from_query_context(self) -> None:
        client = FakeChatClient()
        generator = QueryChatAnswerGenerator(client)
        context = QueryContext(
            question="질문",
            graph_context=GraphContext(),
            traversal_paths=[],
            related_pages=[],
            evidence_snippets=[],
            answer_context="# User Question\n질문",
        )

        answer = generator.generate_answer(context)

        self.assertEqual(answer.content, "한국어 답변입니다.")
        self.assertEqual(client.calls[0][1], "# User Question\n질문")
        self.assertIn("Answer in Korean.", client.calls[0][0])
        self.assertIn("citation markers like [1]", client.calls[0][0])
        self.assertEqual(client.calls[0][0], QUERY_ANSWER_SYSTEM_PROMPT)

    def test_adds_trusted_language_and_length_preferences_to_system_prompt(
        self,
    ) -> None:
        client = FakeChatClient()
        generator = QueryChatAnswerGenerator(client)
        context = QueryContext(
            question="Explain it",
            graph_context=GraphContext(),
            traversal_paths=[],
            related_pages=[],
            evidence_snippets=[],
            answer_context="# User Question\nExplain it",
            output_language="en",
            response_length="detailed",
        )

        generator.generate_answer(context)

        system_prompt = client.calls[0][0]
        self.assertIn("# Response Preferences", system_prompt)
        self.assertIn("Write the response in English.", system_prompt)
        self.assertIn("Give a detailed explanation", system_prompt)
        self.assertNotIn("output_language", client.calls[0][1])


if __name__ == "__main__":
    unittest.main()
