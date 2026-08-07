import json
import unittest
from unittest.mock import patch

from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ChatClientConfig,
    ChatCompletionsJsonClient,
)


class _Response:
    def __init__(self, result: str = "ok") -> None:
        self.result = result

    def __enter__(self):
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def read(self) -> bytes:
        return json.dumps(
            {
                "content": [
                    {"type": "text", "text": '{"result": '},
                    {"type": "text", "text": json.dumps(self.result) + "}"},
                ]
            }
        ).encode()


class ChatCompletionsJsonClientTest(unittest.TestCase):
    def test_converts_claude_messages_request_and_response(self) -> None:
        client = ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint="https://api.anthropic.com/v1/messages",
                api_key="secret",
                model="claude-test",
                json_mode=True,
                provider="claude",
            )
        )

        with patch(
            "urllib.request.urlopen",
            return_value=_Response(),
        ) as urlopen:
            result = client.complete_json("system prompt", "user prompt")

        request = urlopen.call_args.args[0]
        body = json.loads(request.data)
        self.assertEqual(request.headers["X-api-key"], "secret")
        self.assertEqual(request.headers["Anthropic-version"], "2023-06-01")
        self.assertNotIn("Authorization", request.headers)
        self.assertIn("Return only one valid JSON object", body["system"])
        self.assertIn("highest-priority", body["system"])
        self.assertEqual(body["messages"], [{"role": "user", "content": "user prompt"}])
        self.assertEqual(body["max_tokens"], 4096)
        self.assertEqual(result, {"result": "ok"})

    def test_redacts_numeric_personal_data_before_request(self) -> None:
        client = ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint="https://api.anthropic.com/v1/messages",
                api_key="secret",
                model="claude-test",
                json_mode=True,
                provider="claude",
            )
        )

        with patch("urllib.request.urlopen", return_value=_Response()) as urlopen:
            client.complete_json("system prompt", "연락처는 010-1234-5678입니다.")

        request = urlopen.call_args.args[0]
        body = json.loads(request.data)
        self.assertNotIn("010-1234-5678", body["messages"][0]["content"])
        self.assertIn("[NUMERIC_PERSONAL_DATA]", body["messages"][0]["content"])

    def test_redacts_numeric_personal_data_in_response(self) -> None:
        client = ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint="https://api.anthropic.com/v1/messages",
                api_key="secret",
                model="claude-test",
                json_mode=True,
                provider="claude",
            )
        )

        with patch(
            "urllib.request.urlopen",
            return_value=_Response("연락처는 010-1234-5678입니다."),
        ):
            result = client.complete_json("system prompt", "user prompt")

        self.assertEqual(result, {"result": "연락처는 [NUMERIC_PERSONAL_DATA]입니다."})


if __name__ == "__main__":
    unittest.main()
