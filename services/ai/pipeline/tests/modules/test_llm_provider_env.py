import pytest

from app.core.llm_env import provider_api_key_env
from app.modules.agent.infrastructure.chat_completions_turn_router import (
    _api_key as agent_api_key,
    _endpoint as agent_endpoint,
    build_agent_turn_router,
)
from app.modules.agent_run.infrastructure.chat_completions_execution_decider import (
    build_execution_decider,
)
from app.modules.agent_run.infrastructure.chat_completions_plan_generator import (
    build_plan_generator,
)
from app.modules.markdown_edit.infrastructure.chat_completions_markdown_editor import (
    _api_key as markdown_api_key,
    _endpoint as markdown_endpoint,
    build_markdown_editor,
)
from app.modules.query.infrastructure.query_chat_answer_generator import (
    _api_key as query_api_key,
    _endpoint as query_endpoint,
    _config_from_env,
    build_query_chat_answer_generator,
)
from app.modules.skill.infrastructure.chat_completions_skill_authoring_generator import (
    build_skill_authoring_generator,
)
from app.modules.wiki_schema.infrastructure.chat_completions_schema_organizer import (
    build_schema_organizer,
)


EXPECTED = {
    "openai": ("https://api.openai.com/v1/chat/completions", "OPENAI_API_KEY", "gpt-5-nano"),
    "gemini": (
        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        "GEMINI_API_KEY",
        "gemini-3.1-flash-lite",
    ),
    "claude": ("https://api.anthropic.com/v1/messages", "ANTHROPIC_API_KEY", "claude-sonnet-5"),
}


@pytest.mark.parametrize("provider", EXPECTED)
def test_runtime_config_uses_fixed_provider_endpoint_key_and_model(
    monkeypatch: pytest.MonkeyPatch,
    provider: str,
) -> None:
    endpoint, key_env, model = EXPECTED[provider]
    monkeypatch.setenv(key_env, "provider-key")
    monkeypatch.setenv("LLM_API_KEY", "legacy-key")
    monkeypatch.setenv("LLM_BASE_URL", "https://legacy.example/v1")
    monkeypatch.setenv("LLM_MODEL", "legacy-model")

    assert query_endpoint(provider) == endpoint
    assert query_api_key(provider) == "provider-key"
    config = _config_from_env(provider=provider, model=model)
    assert config.endpoint == endpoint
    assert config.api_key == "provider-key"
    assert config.model == model
    assert config.provider == provider


@pytest.mark.parametrize(
    ("provider", "endpoint_reader", "api_key_reader"),
    (
        ("openai", agent_endpoint, agent_api_key),
        ("gemini", markdown_endpoint, markdown_api_key),
        ("claude", query_endpoint, query_api_key),
    ),
)
def test_feature_readers_use_selected_provider(
    monkeypatch: pytest.MonkeyPatch,
    provider: str,
    endpoint_reader,
    api_key_reader,
) -> None:
    endpoint, key_env, _ = EXPECTED[provider]
    monkeypatch.setenv(key_env, "provider-key")
    assert endpoint_reader(provider) == endpoint
    assert api_key_reader(provider) == "provider-key"


@pytest.mark.parametrize(
    ("provider", "builder"),
    (
        ("openai", build_agent_turn_router),
        ("gemini", build_markdown_editor),
        ("openai", build_skill_authoring_generator),
        ("claude", build_query_chat_answer_generator),
    ),
)
def test_missing_provider_api_key_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
    provider: str,
    builder,
) -> None:
    model = EXPECTED[provider][2]
    for key_env in ("OPENAI_API_KEY", "GEMINI_API_KEY", "ANTHROPIC_API_KEY"):
        monkeypatch.delenv(key_env, raising=False)

    with pytest.raises(RuntimeError, match=provider_api_key_env(provider)):
        builder(provider=provider, model=model)


@pytest.mark.parametrize("provider", EXPECTED)
@pytest.mark.parametrize("builder", (build_execution_decider, build_plan_generator))
def test_agent_llm_paths_use_request_provider_and_model(
    monkeypatch: pytest.MonkeyPatch,
    provider: str,
    builder,
) -> None:
    _, key_env, model = EXPECTED[provider]
    monkeypatch.setenv(key_env, "provider-key")
    client = builder(provider=provider, model=model)._client  # type: ignore[attr-defined]

    assert client.provider == provider
    assert client.config.model == model
    assert client.config.endpoint == EXPECTED[provider][0]


@pytest.mark.parametrize("builder", (build_execution_decider, build_plan_generator))
def test_agent_llm_paths_reject_unsupported_provider_or_model(
    monkeypatch: pytest.MonkeyPatch,
    builder,
) -> None:
    monkeypatch.setenv("OPENAI_API_KEY", "openai-key")

    with pytest.raises(ValueError, match="Unsupported provider"):
        builder(provider="upstage", model="solar-pro2")
    with pytest.raises(ValueError, match="Unsupported model"):
        builder(provider="openai", model="unsupported-model")
