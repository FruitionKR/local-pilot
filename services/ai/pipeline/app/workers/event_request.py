from typing import Any


_SECRET_FIELD_PARTS = {"token", "password", "secret"}


def without_top_level_secrets(command: dict[str, Any]) -> dict[str, Any]:
    return {
        key: value
        for key, value in command.items()
        if not _is_secret_field(key)
    }


def _is_secret_field(name: str) -> bool:
    normalized = name.lower().replace("-", "_")
    parts = set(normalized.split("_"))
    return (
        normalized == "api_key"
        or normalized.endswith("_api_key")
        or not parts.isdisjoint(_SECRET_FIELD_PARTS)
    )
