import os
from collections.abc import Iterable
from dataclasses import dataclass


SUPPORTED_LLM_PROVIDERS = ("openai", "gemini", "claude", "upstage", "generic")
_PROVIDER_BASE_URLS = {
    "openai": "https://api.openai.com/v1",
    "gemini": "https://generativelanguage.googleapis.com/v1beta/openai",
    "claude": "https://api.anthropic.com/v1",
    "upstage": "https://api.upstage.ai/v1",
    "generic": "https://api.openai.com/v1",
}
_PROVIDER_DEFAULT_MODELS = {
    "upstage": "solar-pro2",
}


@dataclass(frozen=True)
class LlmProviderDefaults:
    provider: str
    base_url: str
    api_key_env: str
    api_key: str | None
    model: str | None


def resolve_llm_provider(provider: str | None = None) -> str:
    resolved = (provider or os.environ.get("LLM_PROVIDER") or "upstage").strip().lower()
    if resolved not in SUPPORTED_LLM_PROVIDERS:
        supported = ", ".join(SUPPORTED_LLM_PROVIDERS)
        raise ValueError(f"Unsupported LLM_PROVIDER: {resolved}. Expected one of: {supported}")
    return resolved


def provider_base_url(provider: str | None = None) -> str:
    return _PROVIDER_BASE_URLS[resolve_llm_provider(provider)]


def provider_api_endpoint(
    base_url: str,
    provider: str | None = None,
) -> str:
    suffix = "/messages" if resolve_llm_provider(provider) == "claude" else "/chat/completions"
    return base_url.rstrip("/") + suffix


def resolve_llm_provider_defaults(
    *,
    provider: str | None = None,
    base_url: str | None = None,
    api_key_env: str | None = None,
    api_key: str | None = None,
    model: str | None = None,
) -> LlmProviderDefaults:
    resolved_provider = resolve_llm_provider(provider)
    resolved_key_env = api_key_env or "LLM_API_KEY"
    resolved_key = api_key or os.environ.get(resolved_key_env)
    resolved_model = (
        model
        or os.environ.get("LLM_MODEL")
        or _PROVIDER_DEFAULT_MODELS.get(resolved_provider)
    )
    return LlmProviderDefaults(
        provider=resolved_provider,
        base_url=base_url
        or os.environ.get("LLM_BASE_URL")
        or provider_base_url(resolved_provider),
        api_key_env=resolved_key_env,
        api_key=resolved_key,
        model=resolved_model,
    )


def chat_completions_endpoint(
    *,
    endpoint_env_names: Iterable[str],
    base_url_env_names: Iterable[str],
    default_base_url: str,
) -> str:
    endpoint = first_env(endpoint_env_names)
    if endpoint:
        return endpoint
    base_url = first_env(base_url_env_names) or default_base_url
    return provider_api_endpoint(base_url)


def api_key_from_env(
    *,
    key_env_name: str,
    key_env_names: Iterable[str],
    strip: bool = False,
) -> str | None:
    key_env = os.environ.get(key_env_name)
    if key_env and os.environ.get(key_env):
        return _normalize(os.environ[key_env], strip)
    return first_env(key_env_names, strip=strip)


def model_from_env(env_names: Iterable[str], default: str) -> str:
    return first_env(env_names) or default


def first_env(env_names: Iterable[str], *, strip: bool = False) -> str | None:
    for name in env_names:
        value = os.environ.get(name)
        if value:
            return _normalize(value, strip)
    return None


def float_env(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def int_env(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def optional_int_env(name: str) -> int | None:
    raw = os.environ.get(name)
    if not raw:
        return None
    try:
        return int(raw)
    except ValueError:
        return None


def _normalize(value: str, strip: bool) -> str | None:
    if not strip:
        return value
    stripped = value.strip()
    return stripped or None
