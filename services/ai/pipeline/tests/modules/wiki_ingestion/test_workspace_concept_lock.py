from app.modules.wiki_ingestion.infrastructure import workspace_concept_lock


def test_concept_lock_releases_only_its_token(monkeypatch) -> None:
    calls: list[tuple] = []

    class Redis:
        def set(self, *args, **kwargs):
            calls.append(("set", args, kwargs))
            return True

        def eval(self, *args):
            calls.append(("eval", args))

    monkeypatch.setattr(workspace_concept_lock, "_client", lambda: Redis())

    with workspace_concept_lock.concept_write_lock("workspace-1", "run-1"):
        pass

    lock_token = calls[0][1][1]
    assert calls[0][1][0] == "wiki:concept-lock:workspace-1"
    assert calls[1][1][-1] == lock_token


def test_concept_index_cache_is_scoped_by_user_and_workspace(monkeypatch) -> None:
    calls: list[tuple] = []

    class Redis:
        def get(self, key):
            calls.append(("get", key))
            return None

        def setex(self, *args):
            calls.append(("setex", *args))

        def delete(self, key):
            calls.append(("delete", key))

    monkeypatch.setattr(workspace_concept_lock, "_client", lambda: Redis())

    workspace_concept_lock.get_concept_index("user-1", "workspace-1")
    workspace_concept_lock.put_concept_index("user-1", "workspace-1", [])
    workspace_concept_lock.invalidate_concept_index("user-1", "workspace-1")

    assert [call[1] for call in calls] == [
        "wiki:concept-index:user-1:workspace-1",
        "wiki:concept-index:user-1:workspace-1",
        "wiki:concept-index:user-1:workspace-1",
    ]
