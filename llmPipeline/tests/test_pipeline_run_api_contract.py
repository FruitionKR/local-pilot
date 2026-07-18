from pathlib import Path

from fastapi import BackgroundTasks, HTTPException
from fastapi.testclient import TestClient
from pydantic import ValidationError

import api


def test_pipeline_run_rejects_chat_selection_mode() -> None:
    try:
        api.PipelineRunIn(document_id="chat_document_1", selection_mode="full")
    except ValidationError as exc:
        assert "selection_mode" in str(exc)
    else:
        raise AssertionError("document pipeline request should reject chat selection_mode")


def test_pipeline_run_requires_document_id() -> None:
    try:
        api.PipelineRunIn()
    except ValidationError as exc:
        assert "document_id" in str(exc)
    else:
        raise AssertionError("document pipeline request should require document_id")


def test_pipeline_run_rejects_direct_input() -> None:
    for field in ("input_markdown", "input_path"):
        try:
            api.PipelineRunIn(document_id="document_1", **{field: "direct-input"})
        except ValidationError as exc:
            assert field in str(exc)
        else:
            raise AssertionError(f"document pipeline request should reject {field}")


def test_pipeline_run_accepts_legacy_document_scope_fields() -> None:
    payload = api.PipelineRunIn(
        document_id="document_1",
        user_id="request-user",
        workspace_id="request-workspace",
    )

    assert payload.user_id == "request-user"
    assert payload.workspace_id == "request-workspace"
    assert payload.wiki_evaluation_loop is True


def test_chat_wiki_run_accepts_selection_mode() -> None:
    payload = api.ChatWikiRunIn(document_id="chat_document_1", selection_mode="full")

    assert payload.document_id == "chat_document_1"
    assert payload.selection_mode == "full"
    assert payload.wiki_evaluation_loop is True


def test_chat_wiki_run_accepts_optional_input_markdown() -> None:
    payload = api.ChatWikiRunIn(
        document_id="chat_document_1",
        selection_mode="full",
        input_markdown="# Chat Export\n\n[session_1:pair_2]Q : 새 질문\nA : 새 답변",
    )

    assert payload.document_id == "chat_document_1"
    assert payload.input_markdown.startswith("# Chat Export")


def test_chat_wiki_inline_markdown_rejects_partial(monkeypatch) -> None:
    monkeypatch.setattr(
        api.database,
        "get_document",
        lambda document_id: {
            "id": document_id,
            "user_id": "user_1",
            "workspace_id": "workspace_1",
        },
    )
    monkeypatch.setattr(
        api.database,
        "latest_source_page_context",
        lambda document_id, user_id, workspace_id: {
            "artifact": {"document_id": document_id},
            "source_markdown": "# Existing Source",
        },
    )
    payload = api.ChatWikiRunIn(
        document_id="chat_document_1",
        selection_mode="partial",
        input_markdown="# Chat Export\n\n[session_1:pair_2]Q : 새 질문\nA : 새 답변",
    )

    try:
        api._run_pipeline_request(payload, BackgroundTasks())
    except HTTPException as exc:
        assert exc.status_code == 422
        assert "only allowed for full" in str(exc.detail)
    else:
        raise AssertionError("partial chat run should reject inline input_markdown")


def test_chat_wiki_inline_markdown_rejects_full_without_existing_source(monkeypatch) -> None:
    monkeypatch.setattr(
        api.database,
        "get_document",
        lambda document_id: {
            "id": document_id,
            "user_id": "user_1",
            "workspace_id": "workspace_1",
        },
    )
    monkeypatch.setattr(api.database, "latest_source_page_context", lambda _document_id, _user_id, _workspace_id: None)
    payload = api.ChatWikiRunIn(
        document_id="chat_document_1",
        selection_mode="full",
        input_markdown="# Chat Export\n\n[session_1:pair_2]Q : 새 질문\nA : 새 답변",
    )

    try:
        api._run_pipeline_request(payload, BackgroundTasks())
    except HTTPException as exc:
        assert exc.status_code == 422
        assert "requires an existing source page" in str(exc.detail)
    else:
        raise AssertionError("first full chat run should reject inline input_markdown")


def test_chat_wiki_run_rejects_unknown_selection_mode() -> None:
    try:
        api.ChatWikiRunIn(document_id="chat_document_1", selection_mode="append")
    except ValidationError as exc:
        assert "selection_mode" in str(exc)
    else:
        raise AssertionError("selection_mode validation should reject unknown values")


def test_chat_wiki_run_requires_document_id() -> None:
    try:
        api.ChatWikiRunIn(selection_mode="full")
    except ValidationError as exc:
        assert "document_id" in str(exc)
    else:
        raise AssertionError("chat wiki run should require document_id")


def test_pipeline_args_include_selection_mode(monkeypatch) -> None:
    monkeypatch.setattr(api, "_load_existing_concept_index_for_run", lambda _user_id, _workspace_id: [])
    payload = api.ChatWikiRunIn(document_id="chat_document_1", selection_mode="partial")

    args = api._build_pipeline_args(
        payload,
        run_id="run_1",
        input_path=None,
        input_markdown="# Chat Export",
        input_name="chat.md",
        out=Path("runs/test"),
        log_path=Path("runs/test/pipeline.log"),
        source_document_id="chat_document_1",
        user_id="user_1",
        workspace_id="workspace_1",
    )

    assert args.selection_mode == "partial"
    assert args.system_prompt == api.CHAT_SEMANTIC_PROMPT


def test_pipeline_args_load_existing_source_context_for_full(monkeypatch) -> None:
    monkeypatch.setattr(api, "_load_existing_concept_index_for_run", lambda _user_id, _workspace_id: [])
    monkeypatch.setattr(
        api.database,
        "latest_source_page_context",
        lambda document_id, user_id, workspace_id: {
            "artifact": {"document_id": document_id},
            "source_markdown": "# Existing Source",
        },
    )
    payload = api.ChatWikiRunIn(document_id="chat_document_1", selection_mode="full")

    args = api._build_pipeline_args(
        payload,
        run_id="run_1",
        input_path=None,
        input_markdown="# Chat Export",
        input_name="chat.md",
        out=Path("runs/test"),
        log_path=Path("runs/test/pipeline.log"),
        source_document_id="chat_document_1",
        user_id="user_1",
        workspace_id="workspace_1",
    )

    assert args.existing_source_artifact == {"document_id": "chat_document_1"}
    assert args.existing_source_markdown == "# Existing Source"
    assert args.system_prompt == api.CHAT_APPEND_SEMANTIC_PROMPT


def test_pipeline_endpoint_rejects_selection_mode() -> None:
    client = TestClient(api.app)

    response = client.post("/pipeline/runs", json={"document_id": "chat_document_1", "selection_mode": "full"})

    assert response.status_code == 422


def test_chat_wiki_endpoint_requires_selection_mode() -> None:
    client = TestClient(api.app)

    response = client.post("/chat-wiki/runs", json={"document_id": "chat_document_1"})

    assert response.status_code == 422


def test_chat_wiki_inline_markdown_uses_document_id_as_source_key(monkeypatch) -> None:
    captured: dict[str, object] = {}

    monkeypatch.setattr(
        api.database,
        "latest_source_page_context",
        lambda document_id, user_id, workspace_id: {
            "artifact": {"document_id": document_id},
            "source_markdown": "# Existing Source",
        },
    )
    monkeypatch.setattr(
        api.database,
        "get_document",
        lambda document_id: {
            "id": document_id,
            "user_id": "user_1",
            "workspace_id": "workspace_1",
        },
    )
    monkeypatch.setattr(
        api,
        "_load_document_markdown",
        lambda _document: (_ for _ in ()).throw(AssertionError("stored document should not be loaded")),
    )
    monkeypatch.setattr(
        api.database,
        "create_pipeline_run",
        lambda run_id, document_id, input_source, output_dir, mode: captured.update(
            {
                "run_id": run_id,
                "document_id": document_id,
                "input_source": input_source,
                "output_dir": output_dir,
                "mode": mode,
            }
        ),
    )

    def fake_build_args(
        payload,
        run_id,
        input_path,
        input_markdown,
        input_name,
        out,
        log_path,
        source_document_id,
        user_id,
        workspace_id,
    ):
        captured.update(
            {
                "input_path": input_path,
                "input_markdown": input_markdown,
                "input_name": input_name,
                "source_document_id": source_document_id,
                "user_id": user_id,
                "workspace_id": workspace_id,
            }
        )
        return object()

    monkeypatch.setattr(api, "_build_pipeline_args", fake_build_args)

    payload = api.ChatWikiRunIn(
        document_id="chat_document_1",
        selection_mode="full",
        input_markdown="# Chat Export\n\n[session_1:pair_2]Q : 새 질문\nA : 새 답변",
        input_name="filtered-chat.md",
        user_id="request-user",
        workspace_id="request-workspace",
    )

    response = api._run_pipeline_request(payload, BackgroundTasks())

    assert response.status == "running"
    assert captured["document_id"] == "chat_document_1"
    assert captured["source_document_id"] == "chat_document_1"
    assert captured["user_id"] == "user_1"
    assert captured["workspace_id"] == "workspace_1"
    assert captured["input_source"] == "inline:filtered-chat.md"
    assert captured["input_path"] is None
    assert captured["input_markdown"] == payload.input_markdown


def test_chat_wiki_inline_markdown_requires_existing_document(monkeypatch) -> None:
    monkeypatch.setattr(
        api.database,
        "latest_source_page_context",
        lambda document_id, user_id, workspace_id: {
            "artifact": {"document_id": document_id},
            "source_markdown": "# Existing Source",
        },
    )
    monkeypatch.setattr(api.database, "get_document", lambda _document_id: None)
    payload = api.ChatWikiRunIn(
        document_id="missing_document",
        selection_mode="full",
        input_markdown="# Chat Export\n\n[session_1:pair_2]Q : 새 질문\nA : 새 답변",
    )

    try:
        api._run_pipeline_request(payload, BackgroundTasks())
    except HTTPException as exc:
        assert exc.status_code == 404
        assert exc.detail == "Document not found"
    else:
        raise AssertionError("chat wiki inline input should require an existing backend document")
