import unittest
from pathlib import Path

from app.modules.wiki_ingestion.infrastructure.wiki_persistence_payload import (
    markdown_title,
    page_payload,
    resolve_page_id,
    source_summary,
    stored_manifest,
)


class WikiPersistencePayloadTest(unittest.TestCase):
    def test_builds_page_payload_from_dict_or_path(self) -> None:
        self.assertEqual(
            page_payload({"slug": "source-a", "markdown": "# Source A\n"}),
            {"slug": "source-a", "markdown": "# Source A\n"},
        )

        path = Path(self._tmp_dir) / "concept-a.md"
        path.write_text("# Concept A\n\n본문", encoding="utf-8")

        payload = page_payload(path)

        self.assertEqual(payload["slug"], "concept-a")
        self.assertEqual(payload["title"], "Concept A")
        self.assertEqual(payload["markdown"], "# Concept A\n\n본문")

    def test_stored_manifest_removes_large_runtime_payloads(self) -> None:
        manifest = {
            "source_page": {"slug": "source-a", "markdown": "# Source", "source_extraction_artifact": {}},
            "concept_pages": [{"slug": "concept-a", "markdown": "# Concept"}],
            "normalized": {"large": True},
            "source_blocks": [{"block_id": "B0001"}],
            "meaning_clusters": {
                "active_markdown": "# Active",
                "log_markdown": "# Log",
                "clusters": [{"id": "a"}],
                "active_uri": "s3://active",
            },
        }

        stored = stored_manifest(manifest)

        self.assertNotIn("normalized", stored)
        self.assertNotIn("source_blocks", stored)
        self.assertEqual(stored["source_page"], {"slug": "source-a"})
        self.assertEqual(stored["concept_pages"], [{"slug": "concept-a"}])
        self.assertEqual(stored["meaning_clusters"], {"active_uri": "s3://active"})

    def test_resolves_summary_title_and_link_page_ids(self) -> None:
        self.assertEqual(
            source_summary({"semantic_notes": [{"semantic_summary": "첫 요약"}], "document": {"title": "문서 제목"}}),
            "첫 요약",
        )
        self.assertEqual(
            source_summary(
                {"semantic_notes": [{"semantic_summary": "첫 요약"}], "document": {"title": "문서 제목"}},
                {"source_extraction_artifact": {"summary": "평가 후 전체 요약"}},
            ),
            "평가 후 전체 요약",
        )
        self.assertEqual(markdown_title("본문\n# 제목\n내용"), "제목")
        self.assertEqual(resolve_page_id("source:doc", "source-id", {}), "source-id")
        self.assertEqual(resolve_page_id("concept:test", "source-id", {"test": "concept-id"}), "concept-id")
        self.assertIsNone(resolve_page_id("concept:missing", "source-id", {}))

    def setUp(self) -> None:
        import tempfile

        self._tmp = tempfile.TemporaryDirectory()
        self._tmp_dir = self._tmp.name

    def tearDown(self) -> None:
        self._tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
