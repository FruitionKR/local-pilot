import os
from collections.abc import Iterator
from contextlib import contextmanager

try:
    from langsmith import tracing_context
except ImportError:  # pragma: no cover - optional tracing dependency
    tracing_context = None


def langsmith_tracing_enabled() -> bool:
    tracing = os.environ.get("LANGSMITH_TRACING", os.environ.get("LANGCHAIN_TRACING_V2", ""))
    api_key = os.environ.get("LANGSMITH_API_KEY", "")
    return tracing.strip().lower() in {"1", "true", "yes", "on"} and bool(api_key.strip())


@contextmanager
def configured_langsmith_tracing() -> Iterator[None]:
    if tracing_context is None:
        yield
        return
    with tracing_context(enabled=langsmith_tracing_enabled()):
        yield
