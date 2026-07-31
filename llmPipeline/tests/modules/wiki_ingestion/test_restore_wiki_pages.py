import json

from app.modules.wiki_ingestion.application.models import (
    RebuildPageCommand,
    RestoreContributionCommand,
    RestoreWikiCommand,
)
from app.modules.wiki_ingestion.application.restore_wiki_pages import (
    RestoreWikiPagesUseCase,
)
from app.modules.wiki_ingestion.infrastructure.wiki_page_restore import (
    ObjectStorageWikiPageRestore,
)


def _payload(operation_id: str, page_id: str, evidence: str) -> str:
    return json.dumps(
        {
            "schema_version": 1,
            "operation_id": operation_id,
            "document_id": f"doc-{operation_id}",
            "page_id": page_id,
            "concept": {
                "slug": page_id.lower(),
                "title": page_id,
                "definition": evidence,
                "source_document_ids": [f"doc-{operation_id}"],
                "evidence_claim_ids": [f"ev-{operation_id}"],
            },
            "evidence_units": [
                {
                    "evidence_id": f"ev-{operation_id}",
                    "claim": evidence,
                    "anchor_reference_ids": [],
                    "related_concept_slugs": [page_id.lower()],
                    "source_document_id": f"doc-{operation_id}",
                }
            ],
            "source_blocks": [],
            "links": [],
        },
        ensure_ascii=False,
    )


def _lint_payload(
    operation_id: str,
    page_id: str,
    evidence: str,
    *,
    added_links: list[dict] | None = None,
    removed_links: list[dict] | None = None,
) -> str:
    return json.dumps(
        {
            "schema_version": 1,
            "artifact_type": "lint",
            "operation_id": operation_id,
            "document_id": f"lint:{operation_id}",
            "page_id": page_id,
            "content_action": "append_evidence",
            "concept": {
                "slug": page_id.lower(),
                "title": page_id,
                "definition": "",
                "source_document_ids": [],
                "evidence_claim_ids": [f"ev-{operation_id}"],
            },
            "evidence_units": [
                {
                    "evidence_id": f"ev-{operation_id}",
                    "claim": evidence,
                    "anchor_reference_ids": [],
                    "related_concept_slugs": [page_id.lower()],
                    "source_document_id": "",
                }
            ],
            "source_blocks": [],
            "source_key_points": [],
            "added_links": added_links or [],
            "removed_links": removed_links or [],
        },
        ensure_ascii=False,
    )


class ArtifactStore:
    def __init__(self, objects: dict[str, str]) -> None:
        self.objects = objects
        self.writes: list[tuple[str, str, str]] = []

    def read_text(self, key: str) -> str:
        return self.objects[key]

    def write_text(self, key: str, text: str, content_type: str) -> str:
        self.writes.append((key, text, content_type))
        return f"s3://bucket/{key}"


class StorageReadError(Exception):
    pass


class Notifier:
    def __init__(self) -> None:
        self.calls = []

    def notify(self, callback_url: str, payload: dict) -> None:
        self.calls.append((callback_url, payload))


def test_restore_rebuilds_page_from_selected_contribution_json() -> None:
    store = ArtifactStore(
        {
            "wiki/ws-1/pages/C3/ops/A.json": _payload("A", "C3", "A 근거"),
            "wiki/ws-1/pages/C3/ops/B.json": _payload("B", "C3", "B 근거"),
        }
    )
    notifier = Notifier()
    use_case = RestoreWikiPagesUseCase(
        ObjectStorageWikiPageRestore(store.read_text, store.write_text),
        notifier,
    )

    result = use_case.execute(
        RestoreWikiCommand(
            operation_id="restore-1",
            workspace_id="ws-1",
            result_callback_url="http://backend/result",
            rebuild_pages=(
                RebuildPageCommand(
                    page_id="C3",
                    keep_contributions=(
                        RestoreContributionCommand("A", "doc-A"),
                        RestoreContributionCommand("B", "doc-B"),
                    ),
                ),
            ),
        )
    )

    assert result["status"] == "succeeded"
    assert result["failed_pages"] == []
    assert result["changed_pages"][0]["page_id"] == "C3"
    assert store.writes[0][0] == (
        "wiki/ws-1/pages/C3/ops/restore-1.md"
    )
    assert "A 근거" in store.writes[0][1]
    assert "B 근거" in store.writes[0][1]
    assert notifier.calls[0][1] == result


def test_restore_replays_ingest_and_lint_artifacts_in_operation_order() -> None:
    first_link = {
        "source": "concept:c3",
        "target": "concept:first",
        "relation": "related_to",
    }
    lint_link = {
        "source": "concept:c3",
        "target": "concept:lint",
        "relation": "related_to",
    }
    ingest_a = json.loads(_payload("A", "C3", "A 근거"))
    ingest_a["links"] = [first_link]
    store = ArtifactStore(
        {
            "wiki/ws-1/pages/C3/ops/A.json": json.dumps(ingest_a),
            "wiki/ws-1/pages/C3/ops/L.json": _lint_payload(
                "L",
                "C3",
                "lint 근거",
                added_links=[lint_link],
                removed_links=[first_link],
            ),
            "wiki/ws-1/pages/C3/ops/B.json": _payload("B", "C3", "B 근거"),
        }
    )
    use_case = RestoreWikiPagesUseCase(
        ObjectStorageWikiPageRestore(store.read_text, store.write_text)
    )

    result = use_case.execute(
        RestoreWikiCommand(
            operation_id="restore-1",
            workspace_id="ws-1",
            rebuild_pages=(
                RebuildPageCommand(
                    page_id="C3",
                    keep_contributions=(
                        RestoreContributionCommand("A", "doc-A"),
                        RestoreContributionCommand("L", "lint:L"),
                        RestoreContributionCommand("B", "doc-B"),
                    ),
                ),
            ),
        )
    )

    assert result["status"] == "succeeded"
    assert "A 근거" in store.writes[0][1]
    assert "lint 근거" in store.writes[0][1]
    assert "B 근거" in store.writes[0][1]
    assert result["changed_pages"][0]["supported_links"] == [lint_link]


def test_restore_reports_page_failure_and_keeps_other_success() -> None:
    store = ArtifactStore(
        {
            "wiki/ws-1/pages/C3/ops/A.json": _payload("A", "C3", "A 근거"),
        }
    )
    use_case = RestoreWikiPagesUseCase(
        ObjectStorageWikiPageRestore(store.read_text, store.write_text)
    )

    result = use_case.execute(
        RestoreWikiCommand(
            operation_id="restore-1",
            workspace_id="ws-1",
            rebuild_pages=(
                RebuildPageCommand(
                    page_id="C3",
                    keep_contributions=(
                        RestoreContributionCommand("A", "doc-A"),
                    ),
                ),
                RebuildPageCommand(
                    page_id="C6",
                    keep_contributions=(
                        RestoreContributionCommand("D", "doc-D"),
                    ),
                ),
            ),
        )
    )

    assert result["status"] == "partially_succeeded"
    assert [item["page_id"] for item in result["changed_pages"]] == ["C3"]
    assert result["failed_pages"] == [
        {
            "page_id": "C6",
            "reason": "contribution_missing",
        }
    ]


def test_restore_treats_object_storage_error_as_page_failure() -> None:
    class FailingStore(ArtifactStore):
        def read_text(self, key: str) -> str:
            raise StorageReadError(key)

    store = FailingStore({})
    use_case = RestoreWikiPagesUseCase(
        ObjectStorageWikiPageRestore(store.read_text, store.write_text)
    )

    result = use_case.execute(
        RestoreWikiCommand(
            operation_id="restore-1",
            workspace_id="ws-1",
            rebuild_pages=(
                RebuildPageCommand(
                    page_id="C3",
                    keep_contributions=(
                        RestoreContributionCommand("A", "doc-A"),
                    ),
                ),
            ),
        )
    )

    assert result["status"] == "partially_succeeded"
    assert result["failed_pages"] == [
        {"page_id": "C3", "reason": "contribution_missing"}
    ]


def test_restore_result_keeps_source_snapshot_and_deleted_page_notifications() -> None:
    use_case = RestoreWikiPagesUseCase(
        ObjectStorageWikiPageRestore(
            lambda _key: "",
            lambda *_args: "",
        )
    )

    result = use_case.execute(
        RestoreWikiCommand(
            operation_id="restore-1",
            workspace_id="ws-1",
            rebuild_pages=(),
            restored_pages=("S_A", "C2"),
            deleted_pages=("C1", "C5"),
        )
    )

    assert result["restored_pages"] == ["S_A", "C2"]
    assert result["deleted_pages"] == ["C1", "C5"]
