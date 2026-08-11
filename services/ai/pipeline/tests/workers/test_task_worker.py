from unittest.mock import MagicMock, patch

import pytest

from app.workers import task_worker


def test_event_uses_common_command_envelope() -> None:
    command = {
        "run_id": "run-1",
        "kind": "query",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "operation_id": None,
        "api_key": "api-secret",
        "tavily_api_key": "tavily-secret",
        "access_token": "access-secret",
        "db_password": "password-secret",
        "client_secret": "client-secret",
        "apiKey": "camel-api-secret",
        "accessToken": "camel-access-secret",
        "dbPassword": "camel-password-secret",
        "clientSecret": "camel-client-secret",
        "metadata": {
            "api_key": "nested-api-secret",
            "ordinary": {"value": "keep"},
        },
        "items": [
            {"secret": "list-secret", "ordinary": "keep"},
            {"ordinary": {"value": "keep-too"}},
        ],
        "max_tokens": 1024,
    }

    event = task_worker._event(command, "succeeded", {"answer": "ok"})

    assert event["event_id"] == "query:run-1:succeeded"
    assert event["request"] == {
        "run_id": "run-1",
        "kind": "query",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "operation_id": None,
        "metadata": {"ordinary": {"value": "keep"}},
        "items": [
            {"ordinary": "keep"},
            {"ordinary": {"value": "keep-too"}},
        ],
        "max_tokens": 1024,
    }
    assert event["payload"] == {"answer": "ok"}


@pytest.mark.parametrize("allow_web_search", [False, True])
def test_query_command_passes_runtime_model_and_web_search_flag(
    allow_web_search: bool,
) -> None:
    command = {
        "run_id": "run-1",
        "kind": "query",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "session_id": "session-1",
        "question": "질문",
        "provider": "openai",
        "model": "  gpt-5.6-terra  ",
        "allow_web_search": allow_web_search,
        "api_key": "command-secret",
        "api_base_url": "https://command.example/v1",
        "tavily_api_key": "command-tavily-secret",
    }
    use_case = MagicMock()

    with (
        patch.object(
            task_worker,
            "build_answer_query_use_case",
            return_value=use_case,
        ) as build_use_case,
        patch.object(task_worker, "query_to_response") as to_response,
    ):
        to_response.return_value.model_dump.return_value = {"answer": "ok"}
        result = task_worker._handle_query(command)

    build_use_case.assert_called_once_with(
        provider="openai",
        model="gpt-5.6-terra",
        allow_web_search=allow_web_search,
    )
    assert result == {"answer": "ok"}


def test_query_command_requires_boolean_web_search_flag() -> None:
    command = {
        "run_id": "run-1",
        "kind": "query",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "session_id": "session-1",
        "question": "질문",
        "provider": "openai",
        "model": "  gpt-5.6-terra  ",
        "allow_web_search": "false",
    }

    with pytest.raises(ValueError, match="must be a boolean"):
        task_worker._handle_query(command)


@pytest.mark.parametrize(
    ("handler", "command"),
    [
        (
            task_worker._handle_query,
            {
                "run_id": "run-1",
                "workspace_id": "workspace-1",
                "user_id": "user-1",
                "session_id": "session-1",
                "question": "질문",
                "allow_web_search": False,
            },
        ),
        (
            task_worker._handle_lint,
            {
                "run_id": "run-1",
                "workspace_id": "workspace-1",
                "user_id": "user-1",
            },
        ),
    ],
)
def test_commands_require_runtime_model(handler, command: dict) -> None:
    with pytest.raises(ValueError, match="model"):
        handler(command)


def test_lint_command_passes_runtime_model_without_command_overrides() -> None:
    command = {
        "run_id": "run-1",
        "kind": "lint",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "provider": "openai",
        "model": "  gpt-5.6-terra  ",
        "dry_run": True,
        "api_key": "command-secret",
        "api_base_url": "https://command.example/v1",
    }
    maintenance = MagicMock()
    maintenance.lint.return_value = {"ok": True}

    with (
        patch.object(task_worker.database, "get_pipeline_run", return_value=None),
        patch.object(task_worker.database, "create_pipeline_run"),
        patch.object(task_worker.database, "finish_pipeline_run"),
        patch.object(task_worker, "get_wiki_maintenance", return_value=maintenance),
        patch.object(task_worker.WikiLintOut, "model_validate") as validate_out,
    ):
        validate_out.return_value.model_dump.return_value = {"ok": True}
        result = task_worker._handle_lint(command)

    lint_command = maintenance.lint.call_args.args[0]
    assert lint_command.provider is None
    assert lint_command.model == "gpt-5.6-terra"
    assert lint_command.api_key is None
    assert lint_command.api_base_url is None
    assert result == {"ok": True}


def test_restore_rejects_changed_contribution_manifest() -> None:
    command = {
        "workspace_id": "workspace-1",
        "expected_contributions": {"page-1": ["op-1:1:1"]},
    }
    rows = [{"page_id": "page-1", "ingest_operation_id": "op-2", "sequence_revision": 2, "active": True}]

    with (
        patch.object(task_worker, "read_contributions", return_value=rows),
        pytest.raises(ValueError, match="manifest is stale"),
    ):
        task_worker._validate_restore_contributions(command)


def test_unknown_command_is_rejected() -> None:
    with pytest.raises(ValueError, match="unsupported AI command kind"):
        task_worker._handle({"run_id": "run-1", "kind": "unknown"})


def test_maintenance_failure_requires_terminal_run() -> None:
    command = {"run_id": "run-1", "kind": "lint"}

    with patch.object(task_worker.database, "get_pipeline_run", return_value=None):
        assert task_worker._failure_is_durable(command) is False
    with patch.object(
        task_worker.database,
        "get_pipeline_run",
        return_value={"status": "failed"},
    ):
        assert task_worker._failure_is_durable(command) is True


def test_agent_command_registers_supplied_run_and_deterministic_job_once() -> None:
    command = {
        "run_id": "agent_0123456789abcdef0123456789abcdef",
        "kind": "agent",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "document_id": "document-1",
        "base_version": 7,
        "apply_operation_id": "op-1",
        "message": "문서를 정리해줘",
        "editor_snapshot": {"markdown": "# 제목"},
    }
    connection = MagicMock()
    inserted = MagicMock()
    inserted.fetchone.return_value = {"id": command["run_id"]}
    locked = MagicMock()
    locked.fetchone.return_value = {
        **command,
        "id": command["run_id"],
        "status": "queued",
        "result": None,
        "command_envelope_hash": task_worker._agent_command_hash(command),
    }
    connection.execute.side_effect = [inserted, MagicMock(), locked, MagicMock(), MagicMock()]
    context = MagicMock()
    context.__enter__.return_value = connection

    with patch.object(task_worker.database, "connect_ai", return_value=context):
        state, result = task_worker._register_agent_command(command)

    assert state == "execute"
    assert result is None
    job_insert = connection.execute.call_args_list[1]
    assert job_insert.args[1][0] == f"{command['run_id']}:markdown_turn"


def test_repeated_agent_command_rejects_changed_envelope() -> None:
    command = {
        "run_id": "agent_0123456789abcdef0123456789abcdef",
        "kind": "agent",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "document_id": "document-1",
        "base_version": 7,
        "apply_operation_id": "op-1",
        "message": "변경된 요청",
        "editor_snapshot": {"markdown": "# 제목"},
    }
    connection = MagicMock()
    inserted = MagicMock()
    inserted.fetchone.return_value = None
    locked = MagicMock()
    locked.fetchone.return_value = {
        "id": command["run_id"],
        "status": "completed",
        "result": {"ok": True},
        "command_envelope_hash": "different",
    }
    connection.execute.side_effect = [inserted, locked]
    context = MagicMock()
    context.__enter__.return_value = connection

    with (
        patch.object(task_worker.database, "connect_ai", return_value=context),
        pytest.raises(ValueError, match="envelope"),
    ):
        task_worker._register_agent_command(command)


def test_repeated_identical_agent_command_reuses_completed_result() -> None:
    command = {
        "run_id": "agent_0123456789abcdef0123456789abcdef",
        "kind": "agent",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "document_id": "document-1",
        "base_version": 7,
        "apply_operation_id": "op-1",
        "message": "문서를 정리해줘",
        "editor_snapshot": {"markdown": "# 제목"},
    }
    connection = MagicMock()
    inserted = MagicMock()
    inserted.fetchone.return_value = None
    locked = MagicMock()
    locked.fetchone.return_value = {
        "status": "completed",
        "result": {"edit": {"changed": True}},
        "command_envelope_hash": task_worker._agent_command_hash(command),
    }
    connection.execute.side_effect = [inserted, locked]
    context = MagicMock()
    context.__enter__.return_value = connection

    with patch.object(task_worker.database, "connect_ai", return_value=context):
        state, result = task_worker._register_agent_command(command)

    assert state == "completed"
    assert result == {"edit": {"changed": True}}
    assert connection.execute.call_count == 2
