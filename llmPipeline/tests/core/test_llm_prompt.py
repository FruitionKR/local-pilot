from app.core.llm_prompt import with_schema_prompt


def test_keeps_system_prompt_when_schema_prompt_is_empty() -> None:
    assert with_schema_prompt("system\n", "  ") == "system\n"


def test_appends_trimmed_schema_prompt() -> None:
    assert with_schema_prompt("system\n", " schema ") == "system\n\nschema\n"
