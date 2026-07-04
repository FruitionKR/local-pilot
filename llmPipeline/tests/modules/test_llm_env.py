import unittest
from unittest.mock import patch

from app.core.llm_env import (
    api_key_from_env,
    chat_completions_endpoint,
    float_env,
    int_env,
    model_from_env,
    optional_int_env,
)


class LlmEnvTest(unittest.TestCase):
    def test_resolves_chat_completions_endpoint_from_endpoint_or_base_url(self) -> None:
        with patch.dict("os.environ", {"PRIMARY_ENDPOINT": "https://example.com/chat"}, clear=True):
            self.assertEqual(
                chat_completions_endpoint(
                    endpoint_env_names=("PRIMARY_ENDPOINT",),
                    base_url_env_names=("PRIMARY_BASE_URL",),
                    default_base_url="https://default.example/v1",
                ),
                "https://example.com/chat",
            )

        with patch.dict("os.environ", {"PRIMARY_BASE_URL": "https://example.com/v1/"}, clear=True):
            self.assertEqual(
                chat_completions_endpoint(
                    endpoint_env_names=("PRIMARY_ENDPOINT",),
                    base_url_env_names=("PRIMARY_BASE_URL",),
                    default_base_url="https://default.example/v1",
                ),
                "https://example.com/v1/chat/completions",
            )

    def test_resolves_api_key_from_indirect_or_direct_env(self) -> None:
        with patch.dict("os.environ", {"KEY_ENV": "REAL_KEY", "REAL_KEY": "secret"}, clear=True):
            self.assertEqual(api_key_from_env(key_env_name="KEY_ENV", key_env_names=("FALLBACK_KEY",)), "secret")

        with patch.dict("os.environ", {"FALLBACK_KEY": "fallback"}, clear=True):
            self.assertEqual(api_key_from_env(key_env_name="KEY_ENV", key_env_names=("FALLBACK_KEY",)), "fallback")

    def test_strips_api_key_only_when_requested(self) -> None:
        with patch.dict("os.environ", {"PRIMARY_KEY": "  key  "}, clear=True):
            self.assertEqual(api_key_from_env(key_env_name="KEY_ENV", key_env_names=("PRIMARY_KEY",)), "  key  ")
            self.assertEqual(api_key_from_env(key_env_name="KEY_ENV", key_env_names=("PRIMARY_KEY",), strip=True), "key")

    def test_resolves_model_and_numeric_env_values(self) -> None:
        with patch.dict(
            "os.environ",
            {"MODEL": "solar-pro2", "FLOAT_VALUE": "0.4", "INT_VALUE": "7", "OPTIONAL_INT": "9"},
            clear=True,
        ):
            self.assertEqual(model_from_env(("MODEL",), "fallback-model"), "solar-pro2")
            self.assertEqual(float_env("FLOAT_VALUE", 0.0), 0.4)
            self.assertEqual(int_env("INT_VALUE", 0), 7)
            self.assertEqual(optional_int_env("OPTIONAL_INT"), 9)

        with patch.dict("os.environ", {"FLOAT_VALUE": "bad", "INT_VALUE": "bad", "OPTIONAL_INT": "bad"}, clear=True):
            self.assertEqual(float_env("FLOAT_VALUE", 0.2), 0.2)
            self.assertEqual(int_env("INT_VALUE", 3), 3)
            self.assertIsNone(optional_int_env("OPTIONAL_INT"))


if __name__ == "__main__":
    unittest.main()
