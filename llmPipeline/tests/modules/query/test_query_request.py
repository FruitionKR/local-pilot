import unittest

from pydantic import ValidationError

from app.modules.query.interfaces.http.schemas import QueryRequest


class QueryRequestTest(unittest.TestCase):
    def test_workspace_id_is_required(self) -> None:
        with self.assertRaises(ValidationError):
            QueryRequest(question="질문")

    def test_accepts_workspace_scoped_query(self) -> None:
        request = QueryRequest(
            workspace_id="ws_target",
            question="질문",
            output_language="en",
            response_length="concise",
            allow_web_search=False,
        )

        self.assertEqual(request.workspace_id, "ws_target")
        self.assertEqual(request.output_language, "en")
        self.assertEqual(request.response_length, "concise")
        self.assertFalse(request.allow_web_search)

    def test_rejects_unsupported_response_preference(self) -> None:
        with self.assertRaises(ValidationError):
            QueryRequest(
                workspace_id="ws_target",
                question="질문",
                output_language="fr",
            )

    def test_accepts_at_most_three_recent_conversation_pairs(self) -> None:
        request = QueryRequest(
            workspace_id="ws_target",
            question="후속 질문",
            recent_messages=[
                {"role": "user" if index % 2 == 0 else "assistant", "content": str(index)}
                for index in range(6)
            ],
        )

        self.assertEqual(len(request.recent_messages), 6)
        with self.assertRaises(ValidationError):
            QueryRequest(
                workspace_id="ws_target",
                question="후속 질문",
                recent_messages=[
                    {"role": "user", "content": str(index)} for index in range(7)
                ],
            )


if __name__ == "__main__":
    unittest.main()
