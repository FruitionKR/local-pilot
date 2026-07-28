import pytest

from app.modules.agent.infrastructure.chat_completions_turn_router import (
    _api_key as agent_api_key,
)
from app.modules.agent.infrastructure.chat_completions_turn_router import (
    _endpoint as agent_endpoint,
)
from app.modules.agent.infrastructure.chat_completions_turn_router import (
    _model as agent_model,
)
from app.modules.markdown_edit.infrastructure.chat_completions_markdown_editor import (
    _api_key as markdown_api_key,
)
from app.modules.markdown_edit.infrastructure.chat_completions_markdown_editor import (
    _endpoint as markdown_endpoint,
)
from app.modules.markdown_edit.infrastructure.chat_completions_markdown_editor import (
    _model as markdown_model,
)
from app.modules.query.infrastructure.query_answer_evaluator import (
    _api_key as evaluator_api_key,
)
from app.modules.query.infrastructure.query_answer_evaluator import (
    _endpoint as evaluator_endpoint,
)
from app.modules.query.infrastructure.query_answer_evaluator import (
    _model as evaluator_model,
)
from app.modules.query.infrastructure.query_chat_answer_generator import (
    _api_key as query_api_key,
)
from app.modules.query.infrastructure.query_chat_answer_generator import (
    _endpoint as query_endpoint,
)
from app.modules.query.infrastructure.query_chat_answer_generator import (
    _model as query_model,
)
from app.modules.wiki_schema.infrastructure.chat_completions_schema_organizer import (
    _api_key as schema_api_key,
)
from app.modules.wiki_schema.infrastructure.chat_completions_schema_organizer import (
    _endpoint as schema_endpoint,
)
from app.modules.wiki_schema.infrastructure.chat_completions_schema_organizer import (
    _model as schema_model,
)


RUNTIME_CONFIG_READERS = (
    (query_endpoint, query_api_key, query_model),
    (evaluator_endpoint, evaluator_api_key, evaluator_model),
    (agent_endpoint, agent_api_key, agent_model),
    (markdown_endpoint, markdown_api_key, markdown_model),
    (schema_endpoint, schema_api_key, schema_model),
)


@pytest.mark.parametrize(
    ("endpoint_reader", "api_key_reader", "model_reader"),
    RUNTIME_CONFIG_READERS,
)
def test_runtime_config_ignores_legacy_upstage_env(
    monkeypatch: pytest.MonkeyPatch,
    endpoint_reader,
    api_key_reader,
    model_reader,
) -> None:
    monkeypatch.setenv("LLM_PROVIDER", "openai")
    monkeypatch.setenv("LLM_API_KEY", "openai-key")
    monkeypatch.setenv("LLM_BASE_URL", "https://api.openai.example/v1")
    monkeypatch.setenv("LLM_MODEL", "openai-model")
    monkeypatch.setenv("UPSTAGE_API_KEY", "legacy-key")
    monkeypatch.setenv("UPSTAGE_BASE_URL", "https://api.upstage.example/v1")
    monkeypatch.setenv("UPSTAGE_MODEL", "legacy-model")

    assert endpoint_reader() == "https://api.openai.example/v1/chat/completions"
    assert api_key_reader() == "openai-key"
    assert model_reader() == "openai-model"


@pytest.mark.parametrize(
    ("endpoint_reader", "api_key_reader", "model_reader"),
    RUNTIME_CONFIG_READERS,
)
def test_runtime_config_does_not_fallback_to_legacy_upstage_env(
    monkeypatch: pytest.MonkeyPatch,
    endpoint_reader,
    api_key_reader,
    model_reader,
) -> None:
    monkeypatch.setenv("LLM_PROVIDER", "openai")
    monkeypatch.delenv("LLM_API_KEY", raising=False)
    monkeypatch.delenv("LLM_BASE_URL", raising=False)
    monkeypatch.delenv("LLM_MODEL", raising=False)
    monkeypatch.setenv("UPSTAGE_API_KEY", "legacy-key")
    monkeypatch.setenv("UPSTAGE_BASE_URL", "https://api.upstage.example/v1")
    monkeypatch.setenv("UPSTAGE_MODEL", "legacy-model")

    assert endpoint_reader() == "https://api.openai.com/v1/chat/completions"
    assert api_key_reader() is None
    assert model_reader() == ""
