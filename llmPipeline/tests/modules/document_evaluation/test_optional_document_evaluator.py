from __future__ import annotations

import os
import unittest
from unittest import mock

from app.modules.document_evaluation.infrastructure.chat_completions_document_evaluator import (
    build_optional_document_evaluator,
)


ENV_NAMES = (
    "DOCUMENT_EVALUATOR_LLM_ENDPOINT",
    "DOCUMENT_EVALUATOR_LLM_API_KEY",
    "DOCUMENT_EVALUATOR_LLM_MODEL",
)


class OptionalDocumentEvaluatorTest(unittest.TestCase):
    def test_returns_none_when_api_is_not_configured(self) -> None:
        clean_env = {name: "" for name in ENV_NAMES}

        with mock.patch.dict(os.environ, clean_env):
            evaluator = build_optional_document_evaluator()

        self.assertIsNone(evaluator)

    def test_rejects_partial_api_configuration(self) -> None:
        clean_env = {name: "" for name in ENV_NAMES}
        clean_env["DOCUMENT_EVALUATOR_LLM_ENDPOINT"] = "https://example.test/v1/chat/completions"

        with mock.patch.dict(os.environ, clean_env):
            with self.assertRaisesRegex(RuntimeError, "모두 설정"):
                build_optional_document_evaluator()


if __name__ == "__main__":
    unittest.main()
