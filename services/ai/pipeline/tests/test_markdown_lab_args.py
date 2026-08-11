import sys
from unittest.mock import patch

import markdown_agent_http_lab
import markdown_edit_gfm_lab


def test_markdown_labs_default_to_openai_contract(monkeypatch) -> None:
    monkeypatch.setenv("OPENAI_API_KEY", "test-openai-key")

    for module in (markdown_agent_http_lab, markdown_edit_gfm_lab):
        with patch.object(sys, "argv", [module.__name__]):
            args = module.parse_args()

        assert args.endpoint == "https://api.openai.com/v1/chat/completions"
        assert args.api_key == "test-openai-key"
        assert args.model == "gpt-5-nano"
