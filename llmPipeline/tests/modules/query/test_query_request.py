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


if __name__ == "__main__":
    unittest.main()
