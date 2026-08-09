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
