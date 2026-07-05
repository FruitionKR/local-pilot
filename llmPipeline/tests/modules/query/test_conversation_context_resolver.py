import unittest

from app.modules.query.application.conversation_context_resolver import contextualize_question, evidence_question
from app.modules.query.domain.entities import ConversationContext


class ConversationContextResolverTest(unittest.TestCase):
    def test_returns_original_question_without_conversation_context(self) -> None:
        question = "RAG가 뭐야?"

        self.assertEqual(contextualize_question(question, None), question)
        self.assertEqual(evidence_question(question, None, question), question)

    def test_contextualizes_follow_up_question_with_referents_and_reference_context(self) -> None:
        question = "그거랑 RAG 차이는?"
        context = ConversationContext(
            recent_conversation_summary="사용자는 Persistent Wiki와 RAG의 차이를 이어서 묻고 있다.",
            reference_context={
                "active_topic": {"canonical": "Persistent Wiki", "aliases": ["지속적 위키"]},
                "recent_concepts": ["Persistent Wiki", "RAG"],
                "referents": {"그거": {"canonical": "Persistent Wiki", "aliases": ["지속적 위키"]}},
            },
        )

        resolved = contextualize_question(question, context)

        self.assertEqual(
            resolved,
            (
                "Persistent Wiki 지속적 위키\n"
                "RAG\n"
                "사용자는 Persistent Wiki와 RAG의 차이를 이어서 묻고 있다.\n"
                "그거랑 RAG 차이는?"
            ),
        )
        self.assertEqual(
            evidence_question(question, context, resolved),
            "Persistent Wiki 지속적 위키 그거랑 RAG 차이는?",
        )

    def test_uses_original_question_as_evidence_question_when_no_referent_matches(self) -> None:
        question = "RAG 차이는?"
        context = ConversationContext(
            recent_conversation_summary="사용자는 Persistent Wiki와 RAG의 차이를 이어서 묻고 있다.",
            reference_context={"referents": {"그거": "Persistent Wiki"}},
        )

        self.assertEqual(evidence_question(question, context, contextualize_question(question, context)), question)


if __name__ == "__main__":
    unittest.main()
