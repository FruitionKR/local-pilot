import json
import unittest
from datetime import datetime, timezone

from app.modules.agent.domain.entities import AgentConversationContext, AgentTurnRequest
from app.modules.agent.domain.exceptions import ConversationHandoffError
from app.modules.agent.infrastructure.chat_completions_conversation_replier import (
    DEFAULT_CONVERSATION_REPLY_PROMPT,
    ChatCompletionsConversationReplier,
    _current_date,
)
from app.modules.query.domain.entities import ConversationMessage


class FakeChatClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, tuple[str, ...]]] = []

    def complete_json(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        trusted_identifiers: tuple[str, ...] = (),
    ) -> dict[str, object]:
        self.calls.append((system_prompt, user_prompt, trusted_identifiers))
        return {
            "action": "conversation_reply",
            "reason": "검색 없이 완성할 수 있는 대화 요청입니다.",
            "message": "  2026-08-17-덥고 습함-🥵  ",
        }


class ChatCompletionsConversationReplierTest(unittest.TestCase):
    def test_uses_korean_product_date_at_utc_day_boundary(self) -> None:
        utc_time = datetime(2026, 8, 16, 15, 0, tzinfo=timezone.utc)

        self.assertEqual(_current_date(utc_time), "2026-08-17")

    def test_prompt_requires_recent_values_instead_of_placeholders(self) -> None:
        prompt = DEFAULT_CONVERSATION_REPLY_PROMPT.read_text(encoding="utf-8")

        self.assertIn("Reuse the most recent user-provided value", prompt)
        self.assertIn("Never replace a value", prompt)

    def test_replies_from_current_and_recent_conversation_without_retrieval(self) -> None:
        client = FakeChatClient()
        replier = ChatCompletionsConversationReplier(client, "system")  # type: ignore[arg-type]

        reply = replier.reply(
            AgentTurnRequest(
                message="오늘날짜-날씨-이모지 한 개 형식으로 만들어줘",
                conversation_context=AgentConversationContext(
                    recent_messages=(
                        ConversationMessage(role="user", content="여름이어서 덥고 습했다"),
                        ConversationMessage(
                            role="assistant",
                            content="제목을 만들어 드릴게요.",
                            action="conversation_reply",
                        ),
                    ),
                ),
            )
        )

        self.assertEqual(reply, "2026-08-17-덥고 습함-🥵")
        system_prompt, user_prompt, trusted_identifiers = client.calls[0]
        payload = json.loads(user_prompt)
        self.assertIn("Current date:", system_prompt)
        self.assertEqual(payload["recent_messages"][1]["action"], "conversation_reply")
        self.assertEqual(len(trusted_identifiers), 1)

    def test_hands_grounded_question_to_query_specialist(self) -> None:
        client = FakeChatClient()
        client.complete_json = lambda *args, **kwargs: {  # type: ignore[method-assign]
            "action": "chat_answer",
            "reason": "외부 정보 검색이 필요합니다.",
            "message": None,
        }
        replier = ChatCompletionsConversationReplier(client, "system")  # type: ignore[arg-type]

        with self.assertRaises(ConversationHandoffError) as raised:
            replier.reply(AgentTurnRequest(message="오늘 서울 날씨를 찾아줘."))

        self.assertEqual(raised.exception.action, "chat_answer")


if __name__ == "__main__":
    unittest.main()
