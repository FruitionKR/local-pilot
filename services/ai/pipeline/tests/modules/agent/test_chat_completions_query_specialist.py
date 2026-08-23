import json
import unittest

from app.modules.agent.domain.entities import ActiveMarkdownContext, AgentTurnRequest
from app.modules.agent.infrastructure.chat_completions_query_specialist import (
    DEFAULT_QUERY_SPECIALIST_PROMPT,
    ChatCompletionsQuerySpecialist,
)
from app.modules.markdown_edit.domain.entities import MarkdownEditTarget


class SequenceJsonClient:
    def __init__(self, responses: list[dict[str, object]]) -> None:
        self.responses = responses
        self.calls: list[tuple[str, str]] = []

    def complete_json(self, system_prompt: str, user_prompt: str) -> dict[str, object]:
        self.calls.append((system_prompt, user_prompt))
        return self.responses.pop(0)


class ChatCompletionsQuerySpecialistTest(unittest.TestCase):
    def test_accepts_question_and_selects_workspace_retrieval(self) -> None:
        client = SequenceJsonClient(
            [{
                "action": "chat_answer",
                "retrieval_source": "workspace",
                "reason": "워크스페이스 문서에 관한 질문입니다.",
                "message": None,
            }]
        )
        specialist = ChatCompletionsQuerySpecialist(client, "system")  # type: ignore[arg-type]

        decision = specialist.decide(
            AgentTurnRequest(message="MongoDB를 사용하지 않는 이유가 뭐야?"),
            retrieval_source="workspace",
        )

        self.assertEqual(decision.action, "chat_answer")
        self.assertEqual(decision.retrieval_source, "workspace")

    def test_hands_explicit_document_change_to_edit_specialist(self) -> None:
        client = SequenceJsonClient(
            [{
                "action": "markdown_edit",
                "retrieval_source": "none",
                "reason": "활성 문서를 바꾸라는 요청입니다.",
                "message": None,
            }]
        )
        specialist = ChatCompletionsQuerySpecialist(client, "system")  # type: ignore[arg-type]

        decision = specialist.decide(
            AgentTurnRequest(
                message="선택한 문장을 자연스럽게 고쳐줘.",
                active_markdown_context=ActiveMarkdownContext(
                    markdown="# 제목\n\n어색한 문장",
                    target=MarkdownEditTarget(
                        type="selection",
                        start_line=3,
                        end_line=3,
                    ),
                ),
            ),
            retrieval_source="workspace",
        )

        self.assertEqual(decision.action, "markdown_edit")
        payload = json.loads(client.calls[0][1])
        self.assertTrue(payload["active_markdown_context"]["has_markdown"])
        self.assertEqual(payload["active_markdown_context"]["target"]["type"], "selection")

    def test_clarifies_search_request_without_subject(self) -> None:
        client = SequenceJsonClient(
            [{
                "action": "clarify",
                "retrieval_source": "none",
                "reason": "검색할 대상이 없습니다.",
                "message": "무엇을 검색할까요?",
            }]
        )
        specialist = ChatCompletionsQuerySpecialist(client, "system")  # type: ignore[arg-type]

        decision = specialist.decide(
            AgentTurnRequest(message="검색해줘."),
            retrieval_source="workspace",
        )

        self.assertEqual(decision.action, "clarify")
        self.assertEqual(decision.message, "무엇을 검색할까요?")

    def test_prompt_uses_semantic_role_boundaries(self) -> None:
        prompt = DEFAULT_QUERY_SPECIALIST_PROMPT.read_text(encoding="utf-8")

        self.assertIn("semantic intent", prompt)
        self.assertIn("Do not classify from isolated keywords", prompt)
        self.assertIn("검색해줘", prompt)


if __name__ == "__main__":
    unittest.main()
