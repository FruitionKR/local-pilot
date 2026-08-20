import unittest

from app.modules.query.application.evidence_selector import EvidenceSelector
from app.modules.query.domain.entities import (
    RetrievedPage,
    WikiEmbeddingUnit,
    WikiPage,
)
from app.modules.wiki_ingestion.infrastructure.postgres_wiki_writer import (
    persist_embedding_units,
)


class RecordingConnection:
    def __init__(self) -> None:
        self.batches: list[list[tuple]] = []
        self._current_batch: list[tuple] | None = None

    def execute(self, query: str, params: tuple) -> None:
        if "DELETE FROM wiki_embedding_units" in query:
            self._current_batch = []
            self.batches.append(self._current_batch)
        elif "INSERT INTO wiki_embedding_units" in query:
            assert self._current_batch is not None
            self._current_batch.append(params)


class ContainsSearch:
    def score(self, query: str, documents: list[str]) -> list[float]:
        return [1.0 if query.lower() in document.lower() else 0.0 for document in documents]


def _units(rows: list[tuple]) -> list[WikiEmbeddingUnit]:
    return [
        WikiEmbeddingUnit(
            id=row[0],
            page_id=row[2],
            source_document_id=row[3],
            unit_type=row[4],
            source_block_ids=row[5],
            text=row[6],
            weight=row[7],
        )
        for row in rows
    ]


class PersistEmbeddingUnitsTest(unittest.TestCase):
    def test_raw_marker_is_returned_as_source_page_evidence(self) -> None:
        conn = RecordingConnection()
        persist_embedding_units(
            conn,
            "page-1",
            "doc-1",
            "## Key Points\n- semantic claim [B0001]",
            [{"block_id": "B0001", "text": "UNIQUE_RAW_MARKER"}],
        )

        evidence = EvidenceSelector(
            embedding_search=ContainsSearch(),
            text_search=ContainsSearch(),
        ).select(
            "UNIQUE_RAW_MARKER",
            [
                RetrievedPage(
                    page=WikiPage(
                        id="page-1",
                        page_type="source",
                        title="Source",
                        slug="source",
                        summary="Summary",
                    ),
                    score=1.0,
                    role="seed_source",
                )
            ],
            {"page-1": _units(conn.batches[0])},
        )

        self.assertEqual(evidence[0].text, "UNIQUE_RAW_MARKER")
        self.assertEqual(evidence[0].source_document_id, "doc-1")
        self.assertEqual(evidence[0].source_block_ids, ["B0001"])

    def test_reingest_replaces_old_raw_block_unit(self) -> None:
        conn = RecordingConnection()
        persist_embedding_units(
            conn,
            "page-1",
            "doc-1",
            "# Source",
            [{"block_id": "B0001", "text": "OLD_MARKER"}],
        )
        persist_embedding_units(
            conn,
            "page-1",
            "doc-1",
            "# Source",
            [{"block_id": "B0001", "text": "NEW_MARKER"}],
        )

        self.assertEqual([row[6] for row in conn.batches[0]], ["OLD_MARKER"])
        self.assertEqual([row[6] for row in conn.batches[1]], ["NEW_MARKER"])

    def test_raw_units_do_not_replace_semantic_units(self) -> None:
        conn = RecordingConnection()
        persist_embedding_units(
            conn,
            "page-1",
            "doc-1",
            "## Key Points\n- semantic claim [B0001]",
            [{"block_id": "B0001", "text": "raw source text"}],
        )

        self.assertEqual(
            {unit.unit_type for unit in _units(conn.batches[0])},
            {"key_point", "source_block"},
        )


if __name__ == "__main__":
    unittest.main()
