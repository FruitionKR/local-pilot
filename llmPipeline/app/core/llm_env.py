import os
from collections.abc import Iterable


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
    return base_url.rstrip("/") + "/chat/completions"


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
