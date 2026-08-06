import json
import unittest
from unittest.mock import patch

from app.modules.query.infrastructure.query_event_publisher import HttpQueryEventPublisher, build_query_event_publisher


class QueryEventPublisherTest(unittest.TestCase):
    def test_http_publisher_posts_request_scoped_event_payload(self) -> None:
        publisher = HttpQueryEventPublisher(
            callback_url="http://backend:8080/api/query/runs/query_123/events/callback",
            request_id="query_123",
        )

        with (
            patch.dict("os.environ", {"INTERNAL_CALLBACK_TOKEN": "test-internal-token"}),
            patch("urllib.request.urlopen") as urlopen,
        ):
            publisher.publish("query_started", "질의 처리를 시작했습니다.", {"question": "LLM Wiki가 뭐야?"})
            publisher.publish("answer_generated", "답변 생성을 완료했습니다.", {"answer_chars": 12})

        first_request = urlopen.call_args_list[0].args[0]
        second_request = urlopen.call_args_list[1].args[0]
        first_body = json.loads(first_request.data.decode("utf-8"))
        second_body = json.loads(second_request.data.decode("utf-8"))

        self.assertEqual(first_body["request_id"], "query_123")
        self.assertEqual(first_body["event_type"], "query.log")
        self.assertEqual(first_body["stage"], "query_started")
        self.assertEqual(first_body["message"], "질의 처리를 시작했습니다.")
        self.assertEqual(first_body["sequence"], 1)
        self.assertTrue(first_body["timestamp"].endswith("Z"))
        self.assertEqual(first_body["data"], {"question": "LLM Wiki가 뭐야?"})
        self.assertEqual(first_request.get_header("Content-type"), "application/json; charset=utf-8")
        self.assertEqual(first_request.get_header("X-internal-token"), "test-internal-token")
        self.assertEqual(first_request.get_method(), "POST")
        self.assertEqual(second_body["sequence"], 2)

    def test_build_query_event_publisher_uses_request_callback_before_env(self) -> None:
        with patch.dict("os.environ", {"QUERY_LOG_CALLBACK_URL": "http://env-callback"}, clear=False):
            publisher = build_query_event_publisher(
                callback_url="http://request-callback",
                request_id="query_123",
            )

        self.assertIsInstance(publisher, HttpQueryEventPublisher)
        with (
            patch.dict("os.environ", {"INTERNAL_CALLBACK_TOKEN": "test-internal-token"}),
            patch("urllib.request.urlopen") as urlopen,
        ):
            publisher.publish("query_started", "질의 처리를 시작했습니다.")

        request = urlopen.call_args.args[0]
        body = json.loads(request.data.decode("utf-8"))

        self.assertEqual(request.full_url, "http://request-callback")
        self.assertEqual(body["request_id"], "query_123")


if __name__ == "__main__":
    unittest.main()
