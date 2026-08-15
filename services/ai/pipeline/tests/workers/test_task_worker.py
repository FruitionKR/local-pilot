import asyncio
from unittest.mock import AsyncMock, MagicMock, call, patch

import pytest
from pydantic import ValidationError

from app.modules.query.application.answer_query import AnswerQueryUseCase
from app.modules.query.domain.entities import ConversationContext, ConversationMessage
from app.modules.query.infrastructure.in_memory_wiki_repository import InMemoryWikiRepository
from app.workers import task_worker
from tests.modules.query.test_answer_query import (
    EmptyTextSearch,
    RecordingAnswerGenerator,
    ScoreSearch,
    source_page,
)


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


def test_progress_event_uses_stable_sequence_id() -> None:
    command = {
        "run_id": "run-1",
        "kind": "query",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
    }

    event = task_worker._progress_event(
        command,
        sequence=2,
        stage="context_built",
        message="답변 컨텍스트를 구성했습니다.",
        data={"evidence_count": 3},
    )

    assert event["event_id"] == "query:run-1:progress:2:context_built"
    assert event["status"] == "progress"
    assert event["payload"] == {
        "stage": "context_built",
        "message": "답변 컨텍스트를 구성했습니다.",
        "data": {"evidence_count": 3},
    }


def test_kafka_query_event_publisher_sends_progress_immediately() -> None:
    async def run_test() -> None:
        producer = MagicMock()
        producer.send_and_wait = AsyncMock()
        command = {"run_id": "run-1", "kind": "query"}
        publisher = task_worker.KafkaQueryEventPublisher(
            producer,
            asyncio.get_running_loop(),
            command,
        )

        await asyncio.to_thread(
            publisher.publish,
            "wiki_loaded",
            "Wiki 데이터를 불러왔습니다.",
            {"page_count": 3},
        )

        producer.send_and_wait.assert_awaited_once()
        topic, event = producer.send_and_wait.await_args.args
        assert topic == task_worker.RESULT_TOPIC
        assert event["event_id"] == "query:run-1:progress:1:wiki_loaded"
        assert event["payload"]["stage"] == "wiki_loaded"
        assert producer.send_and_wait.await_args.kwargs["key"] == b"run-1"

    asyncio.run(run_test())


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
        "model": "  gpt-5-nano  ",
        "allow_web_search": allow_web_search,
        "recent_messages": [
            {"role": "user", "content": "이전 질문"},
            {"role": "assistant", "content": "이전 답변"},
        ],
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
        model="gpt-5-nano",
        allow_web_search=allow_web_search,
        event_publisher=None,
    )
    use_case.execute.assert_called_once_with(
        "질문",
        workspace_id="workspace-1",
        user_id="user-1",
        conversation_context=ConversationContext(
            recent_messages=(
                ConversationMessage(role="user", content="이전 질문"),
                ConversationMessage(role="assistant", content="이전 답변"),
            )
        ),
        allow_web_search=allow_web_search,
    )
    assert result == {"answer": "ok"}


@pytest.mark.parametrize(
    ("allow_web_search", "expected_telemetry"),
    [
        (False, (False, False, None)),
        (True, (True, False, "web_search_unavailable")),
    ],
)
def test_query_command_preserves_web_search_telemetry_for_low_relevance_without_adapter(
    allow_web_search: bool,
    expected_telemetry: tuple[bool, bool, str | None],
) -> None:
    command = {
        "run_id": "run-1",
        "kind": "query",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "session_id": "session-1",
        "question": "외부 정보가 뭐야?",
        "provider": "openai",
        "model": "gpt-5-nano",
        "allow_web_search": allow_web_search,
    }
    use_case = AnswerQueryUseCase(
        wiki_repository=InMemoryWikiRepository([source_page("source:seed", "Seed Source")], []),
        embedding_search=ScoreSearch({"Seed Source": 0.10}),
        text_search=EmptyTextSearch(),
        answer_generator=RecordingAnswerGenerator(),
        min_internal_relevance_score=0.50,
    )

    with patch.object(task_worker, "build_answer_query_use_case", return_value=use_case):
        result = task_worker._handle_query(command)

    assert (
        result["web_search_requested"],
        result["web_search_executed"],
        result["error_code"],
    ) == expected_telemetry


def test_query_command_requires_boolean_web_search_flag() -> None:
    command = {
        "run_id": "run-1",
        "kind": "query",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "session_id": "session-1",
        "question": "질문",
        "provider": "openai",
        "model": "  gpt-5-nano  ",
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
                "provider": "openai",
                "allow_web_search": False,
            },
        ),
        (
            task_worker._handle_lint,
            {
                "run_id": "run-1",
                "workspace_id": "workspace-1",
                "user_id": "user-1",
                "provider": "openai",
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
        "model": "  gpt-5-nano  ",
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
    assert lint_command.provider == "openai"
    assert lint_command.model == "gpt-5-nano"
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


@pytest.mark.parametrize(
    ("skill_mode", "skill_id"),
    [("auto", None), ("explicit", "skill-1"), ("off", None)],
)
def test_agent_command_copies_skill_draft_fields_into_agent_request(
    skill_mode: str, skill_id: str | None,
) -> None:
    command = {
        "run_id": "agent_0123456789abcdef0123456789abcdef",
        "kind": "agent",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "document_id": "document-1",
        "base_version": 7,
        "apply_operation_id": "op-1",
        "message": "Skill로 만들어줘",
        "provider": "openai",
        "model": "gpt-5-nano",
        "skill_mode": skill_mode,
        "skill_id": skill_id,
        "conversation_context": None,
        "editor_snapshot": {"markdown": "# 제목"},
        "skill_draft_sources": [{
            "run_id": "agent_source",
            "status": "completed",
            "request_summary": "정식 요청",
            "plan_summary": "정식 계획",
            "successful_operations": [{"tool_name": "move_document", "reason": "정식 이유"}],
        }],
        "skill_draft_user_directives": ["일반화해줘"],
        "skill_draft_excluded_literals": ["secret-doc"],
        "skill_scope_type": "team",
    }
    use_case = MagicMock()
    response = MagicMock()
    response.model_dump.return_value = {"ok": True}
    connection = MagicMock()
    context = MagicMock()
    context.__enter__.return_value = connection

    with (
        patch.object(task_worker, "_register_agent_command", return_value=("execute", None)),
        patch.object(task_worker, "build_handle_agent_turn_use_case", return_value=use_case),
        patch.object(task_worker, "agent_to_response", return_value=response),
        patch.object(task_worker.database, "connect_ai", return_value=context),
    ):
        assert task_worker._handle_agent(command) == {"ok": True}

    request = use_case.execute.call_args.args[0]
    assert request.skill_draft_sources[0].request_summary == "정식 요청"
    assert request.skill_draft_sources[0].successful_operations[0].tool_name == "move_document"
    assert request.skill_draft_user_directives == ("일반화해줘",)
    assert request.skill_draft_excluded_literals == ("secret-doc",)
    assert request.skill_scope_type == "team"
    assert request.skill_mode == skill_mode
    assert request.skill_id == skill_id


def test_agent_command_rejects_auto_skill_id() -> None:
    command = {
        "run_id": "agent_0123456789abcdef0123456789abcdef",
        "kind": "agent",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "document_id": "document-1",
        "base_version": 7,
        "apply_operation_id": "op-1",
        "message": "정리해줘",
        "provider": "openai",
        "model": "gpt-5-nano",
        "skill_mode": "auto",
        "skill_id": "skill-1",
        "editor_snapshot": {"markdown": "# 제목"},
    }
    use_case = MagicMock()
    connection = MagicMock()
    context = MagicMock()
    context.__enter__.return_value = connection

    with (
        patch.object(task_worker, "_register_agent_command", return_value=("execute", None)),
        patch.object(task_worker, "build_handle_agent_turn_use_case", return_value=use_case),
        patch.object(task_worker.database, "connect_ai", return_value=context),
        pytest.raises(ValidationError),
    ):
        task_worker._handle_agent(command)

    use_case.execute.assert_not_called()


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


def test_unregistered_agent_failure_is_not_durable() -> None:
    command = {"run_id": "run-1", "kind": "agent"}
    connection = MagicMock()
    connection.execute.return_value.fetchone.return_value = None
    context = MagicMock()
    context.__enter__.return_value = connection

    with patch.object(task_worker.database, "connect_ai", return_value=context):
        assert task_worker._failure_is_durable(command) is False


@pytest.mark.parametrize(
    ("provider", "model", "error_match"),
    [
        (None, "gpt-5-nano", "provider and model are required"),
        ("openai", "unsupported-model", "Unsupported model"),
        ("gemini", "gpt-5-nano", "Expected gemini-3.1-flash-lite"),
    ],
)
def test_invalid_agent_selection_is_terminally_registered(
    provider: str | None,
    model: str | None,
    error_match: str,
) -> None:
    command = {
        "run_id": "agent_invalid_selection",
        "kind": "agent",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "document_id": "document-1",
        "base_version": 7,
        "apply_operation_id": "op-1",
        "message": "문서를 정리해줘",
        "provider": provider,
        "model": model,
        "editor_snapshot": {"markdown": "# 제목"},
    }
    connection = MagicMock()
    inserted = MagicMock()
    inserted.fetchone.return_value = {"id": command["run_id"]}
    locked = MagicMock()
    locked.fetchone.return_value = {
        "status": "failed",
        "result": None,
        "command_envelope_hash": task_worker._agent_command_hash(command),
    }
    connection.execute.side_effect = [inserted, MagicMock(), MagicMock(), MagicMock(), locked]
    context = MagicMock()
    context.__enter__.return_value = connection

    with (
        patch.object(task_worker.database, "connect_ai", return_value=context),
        pytest.raises(ValueError, match=error_match),
    ):
        task_worker._handle_agent(command)

    queries = [call.args[0] for call in connection.execute.call_args_list]
    assert any("UPDATE agent_runs" in query and "status = 'failed'" in query for query in queries)
    assert any("UPDATE agent_jobs" in query and "status = 'failed'" in query for query in queries)
    assert context.__exit__.call_args.args[:3] == (None, None, None)

    durability_connection = MagicMock()
    durability_connection.execute.return_value.fetchone.return_value = {"status": "failed"}
    durability_context = MagicMock()
    durability_context.__enter__.return_value = durability_connection
    with patch.object(task_worker.database, "connect_ai", return_value=durability_context):
        assert task_worker._failure_is_durable(command) is True


def test_replayed_invalid_agent_selection_reuses_failed_run_without_new_job() -> None:
    command = {
        "run_id": "agent_invalid_replay",
        "kind": "agent",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "document_id": "document-1",
        "base_version": 7,
        "apply_operation_id": "op-1",
        "message": "문서를 정리해줘",
        "provider": "openai",
        "model": "unsupported-model",
        "editor_snapshot": {"markdown": "# 제목"},
    }

    first_connection = MagicMock()
    first_inserted = MagicMock()
    first_inserted.fetchone.return_value = {"id": command["run_id"]}
    first_locked = MagicMock()
    first_locked.fetchone.return_value = {
        "status": "failed",
        "result": None,
        "command_envelope_hash": task_worker._agent_command_hash(command),
    }
    first_connection.execute.side_effect = [
        first_inserted,
        MagicMock(),
        MagicMock(),
        MagicMock(),
        first_locked,
    ]
    first_context = MagicMock()
    first_context.__enter__.return_value = first_connection

    second_connection = MagicMock()
    second_inserted = MagicMock()
    second_inserted.fetchone.return_value = None
    second_locked = MagicMock()
    second_locked.fetchone.return_value = {
        "status": "failed",
        "result": None,
        "command_envelope_hash": task_worker._agent_command_hash(command),
    }
    second_connection.execute.side_effect = [second_inserted, second_locked]
    second_context = MagicMock()
    second_context.__enter__.return_value = second_connection

    with patch.object(
        task_worker.database,
        "connect_ai",
        side_effect=[first_context, second_context],
    ):
        with pytest.raises(ValueError, match="Unsupported model"):
            task_worker._register_agent_command(command)
        with pytest.raises(ValueError, match="Unsupported model"):
            task_worker._register_agent_command(command)

    assert second_connection.execute.call_count == 2


def test_invalid_agent_payload_is_terminally_registered_after_validation_failure() -> None:
    command = {
        "run_id": "agent_invalid_payload",
        "kind": "agent",
        "workspace_id": "workspace-1",
        "user_id": "user-1",
        "document_id": "document-1",
        "base_version": 7,
        "apply_operation_id": "op-1",
        "message": "문서를 정리해줘",
        "provider": "openai",
        "model": "gpt-5-nano",
        "editor_snapshot": {
            "markdown": "# 제목",
            "target": {"type": "selection", "start_line": 0, "end_line": 1},
        },
    }
    registration_connection = MagicMock()
    inserted = MagicMock()
    inserted.fetchone.return_value = {"id": command["run_id"]}
    queued = MagicMock()
    queued.fetchone.return_value = {
        "status": "queued",
        "result": None,
        "command_envelope_hash": task_worker._agent_command_hash(command),
    }
    registration_connection.execute.side_effect = [
        inserted,
        MagicMock(),
        queued,
        MagicMock(),
        MagicMock(),
    ]
    registration_context = MagicMock()
    registration_context.__enter__.return_value = registration_connection

    failure_connection = MagicMock()
    failure_context = MagicMock()
    failure_context.__enter__.return_value = failure_connection

    durability_connection = MagicMock()
    durability_connection.execute.return_value.fetchone.return_value = {"status": "failed"}
    durability_context = MagicMock()
    durability_context.__enter__.return_value = durability_connection

    with (
        patch.object(
            task_worker.database,
            "connect_ai",
            side_effect=[registration_context, failure_context, durability_context],
        ),
        pytest.raises(ValidationError),
    ):
        task_worker._handle_agent(command)

    failure_queries = [call.args[0] for call in failure_connection.execute.call_args_list]
    assert any("UPDATE agent_runs" in query and "status = 'failed'" in query for query in failure_queries)
    assert any("UPDATE agent_jobs" in query and "status = 'failed'" in query for query in failure_queries)
    assert registration_context.__exit__.call_args.args[:3] == (None, None, None)
    assert failure_context.__exit__.call_args.args[:3] == (None, None, None)
    with patch.object(task_worker.database, "connect_ai", return_value=durability_context):
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
        "provider": "gemini",
        "model": " gemini-3.1-flash-lite ",
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
    run_insert = connection.execute.call_args_list[0]
    assert "provider, model" in run_insert.args[0]
    assert run_insert.args[1][4:6] == ("gemini", "gemini-3.1-flash-lite")
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
        "provider": "openai",
        "model": "gpt-5-nano",
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
        "provider": "openai",
        "model": "gpt-5-nano",
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
