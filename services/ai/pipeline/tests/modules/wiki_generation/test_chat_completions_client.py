import json
import unittest
from unittest.mock import patch

from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ChatClientConfig,
    ChatCompletionsJsonClient,
)


class _Response:
    def __init__(self, result: str = "ok", provider: str = "openai") -> None:
        self.result = result
        self.provider = provider

    def __enter__(self):
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def read(self) -> bytes:
        content = {
            "content": [
                {"type": "text", "text": '{"result": '},
                {"type": "text", "text": json.dumps(self.result) + "}"},
            ]
        }
        if self.provider != "claude":
            content = {
                "choices": [{"message": {"content": '{"result": ' + json.dumps(self.result) + "}"}}]
            }
        return json.dumps(content).encode()


class ChatCompletionsJsonClientTest(unittest.TestCase):
    def test_normalizes_transport_and_response_decode_errors(self) -> None:
        client = ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint="https://example.test/chat",
                api_key="test-key",
                model="gpt-5-nano",
                provider="openai",
            )
        )

        with patch("urllib.request.urlopen", side_effect=TimeoutError("timed out")):
            with self.assertRaisesRegex(RuntimeError, "transport or response error"):
                client.complete_text("system prompt", "user prompt")

        with (
            patch("urllib.request.urlopen", return_value=_Response()),
            patch(
                "app.modules.wiki_generation.infrastructure.chat_completions_llm.json.loads",
                return_value={"choices": []},
            ),
        ):
            with self.assertRaisesRegex(RuntimeError, "Unexpected chat-completions response"):
                client.complete_text("system prompt", "user prompt")

        with (
            patch("urllib.request.urlopen", return_value=_Response()),
            patch(
                "app.modules.wiki_generation.infrastructure.chat_completions_llm.json.loads",
                side_effect=json.JSONDecodeError("invalid JSON", "", 0),
            ),
        ):
            with self.assertRaisesRegex(RuntimeError, "transport or response error"):
                client.complete_text("system prompt", "user prompt")

    def test_converts_claude_messages_request_and_response(self) -> None:
        client = ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint="https://api.anthropic.com/v1/messages",
                api_key="secret",
                model="claude-haiku-4-5-20251001",
                json_mode=True,
                provider="claude",
            )
        )

        with patch(
            "urllib.request.urlopen",
            return_value=_Response(provider="claude"),
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
                model="claude-haiku-4-5-20251001",
                json_mode=True,
                provider="claude",
            )
        )

        with patch("urllib.request.urlopen", return_value=_Response(provider="claude")) as urlopen:
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
                model="claude-haiku-4-5-20251001",
                json_mode=True,
                provider="claude",
            )
        )

        with patch(
            "urllib.request.urlopen",
            return_value=_Response("연락처는 010-1234-5678입니다.", provider="claude"),
        ):
            result = client.complete_json("system prompt", "user prompt")

        self.assertEqual(result, {"result": "연락처는 [NUMERIC_PERSONAL_DATA]입니다."})

    def test_provider_reasoning_parameters_are_sent_without_claude_thinking(self) -> None:
        cases = (
            ("openai", "gpt-5-nano", {"reasoning_effort": "minimal"}),
            ("gemini", "gemini-3.1-flash-lite", {"reasoning_effort": "low"}),
            ("claude", "claude-haiku-4-5-20251001", {}),
        )
        for provider, model, expected_profile in cases:
            with self.subTest(provider=provider):
                endpoint = (
                    "https://api.anthropic.com/v1/messages"
                    if provider == "claude"
                    else "https://example.test/chat"
                )
                client = ChatCompletionsJsonClient(
                    ChatClientConfig(
                        endpoint=endpoint,
                        api_key="secret",
                        model=model,
                        provider=provider,
                    )
                )
                with patch(
                    "urllib.request.urlopen",
                    return_value=_Response(provider=provider),
                ) as urlopen:
                    client.complete_text("system prompt", "user prompt")
                body = json.loads(urlopen.call_args.args[0].data)
                self.assertEqual(body["model"], model)
                self.assertEqual(
                    {key: body[key] for key in body if key == "reasoning_effort"},
                    expected_profile,
                )
                self.assertNotIn("thinking", body)

    def test_langsmith_metadata_excludes_endpoint(self) -> None:
        client = ChatCompletionsJsonClient(
            ChatClientConfig(
                endpoint="https://example.test/chat",
                api_key="secret",
                model="gpt-5-nano",
                provider="openai",
            )
        )
        captured: dict[str, object] = {}

        def fake_traceable(**kwargs):
            captured.update(kwargs)
            return lambda function: function

        with (
            patch(
                "app.modules.wiki_generation.infrastructure.chat_completions_llm.traceable",
                side_effect=fake_traceable,
            ),
            patch(
                "app.modules.wiki_generation.infrastructure.chat_completions_llm.langsmith_tracing_enabled",
                return_value=True,
            ),
            patch.object(client, "_send_chat_completion", return_value='{"ok": true}'),
        ):
            client.complete_text("system", "user")

        assert captured["metadata"] == {
            "provider": "openai",
            "model": "gpt-5-nano",
            "json_mode": False,
        }


if __name__ == "__main__":
    unittest.main()
