from app.core.langsmith_tracing import langsmith_tracing_enabled


def test_langsmith_tracing_requires_api_key(monkeypatch) -> None:
    monkeypatch.setenv("LANGSMITH_TRACING", "true")
    monkeypatch.delenv("LANGSMITH_API_KEY", raising=False)

    assert langsmith_tracing_enabled() is False


def test_langsmith_tracing_is_enabled_with_flag_and_api_key(monkeypatch) -> None:
    monkeypatch.setenv("LANGSMITH_TRACING", "true")
    monkeypatch.setenv("LANGSMITH_API_KEY", "test-key")

    assert langsmith_tracing_enabled() is True
