import unittest

from pydantic import ValidationError

from app.modules.query.interfaces.http.schemas import QueryRequest


class QueryRequestTest(unittest.TestCase):
    def test_workspace_id_is_required(self) -> None:
        with self.assertRaises(ValidationError):
            QueryRequest(question="질문")

    def test_accepts_workspace_scoped_query(self) -> None:
        request = QueryRequest(workspace_id="ws_target", question="질문")

        self.assertEqual(request.workspace_id, "ws_target")


if __name__ == "__main__":
    unittest.main()
