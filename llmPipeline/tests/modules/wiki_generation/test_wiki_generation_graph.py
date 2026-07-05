import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from run_lab import PipelineLog, _prepare_concept_section_polish, _prepare_source_page_polish, _run_wiki_generation_graph


@dataclass
class FakeDocument:
    document_id: str
    title: str


@dataclass
class FakeBlock:
    block_id: str
    text: str


class FakeNormalizer:
    def normalize_notes(self, notes: list[dict[str, object]]) -> dict[str, object]:
        return {
            "attempt": notes[0]["attempt"],
            "concept_ledger": [],
            "evidence_units": [],
        }


class FakeSectionPolisher:
    def __init__(self, raw: dict[str, object]) -> None:
        self.raw = raw
        self.payloads: list[dict[str, object]] = []

    def polish(self, payload: dict[str, object], blocks: list[FakeBlock]) -> dict[str, object]:
        self.payloads.append(payload)
        return self.raw


class WikiGenerationGraphTest(unittest.TestCase):
    def test_evaluator_feedback_retries_semantic_extraction_until_passed(self) -> None:
        prompts: list[str] = []
        evaluations = [
            {
                "passed": False,
                "retry_recommended": True,
                "retry_feedback": "누락된 source anchor를 보강하세요.",
                "scores": {"overall": 0.4},
                "issues": [],
            },
            {
                "passed": True,
                "retry_recommended": False,
                "retry_feedback": "",
                "scores": {"overall": 0.95},
                "issues": [],
            },
        ]

        def fake_semantic_extraction(**kwargs):
            prompts.append(kwargs["system_prompt"])
            return [{"attempt": kwargs["attempt"]}]

        def fake_evaluate_generation(**kwargs):
            return evaluations.pop(0)

        with tempfile.TemporaryDirectory() as tmp_dir:
            with patch("run_lab._run_semantic_extraction", side_effect=fake_semantic_extraction):
                with patch("run_lab._evaluate_generation", side_effect=fake_evaluate_generation):
                    notes, normalized, generation_evaluations = _run_wiki_generation_graph(
                        api_client=SimpleNamespace(),
                        semantic_system_prompt="기본 semantic prompt",
                        wiki_evaluator_system_prompt="evaluator prompt",
                        packets=[SimpleNamespace(chunk_id="chunk-1")],
                        raw_dir=None,
                        log=PipelineLog(Path(tmp_dir) / "pipeline.log"),
                        normalizer=FakeNormalizer(),
                        document=FakeDocument(document_id="doc-1", title="테스트 문서"),
                        blocks=[FakeBlock(block_id="B0001", text="본문")],
                        out=Path(tmp_dir),
                        save_debug_json=False,
                        wiki_evaluation_loop=True,
                        max_eval_attempts=2,
                    )

        self.assertEqual([note["attempt"] for note in notes], [2])
        self.assertEqual(normalized["attempt"], 2)
        self.assertEqual(len(generation_evaluations), 2)
        self.assertEqual(len(prompts), 2)
        self.assertIn("누락된 source anchor를 보강하세요.", prompts[1])

    def test_source_page_polish_helper_keeps_skeleton_mode_without_llm_call(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            normalized = {
                "document": {"document_id": "doc-1"},
                "concept_ledger": [{"slug": "concept-a"}],
                "semantic_notes": [{"key_points": [{"text": "원본 핵심", "anchor_block_ids": ["B0001"]}]}],
                "evidence_units": [],
            }
            polisher = FakeSectionPolisher({})

            source_polish, source_key_points, mode = _prepare_source_page_polish(
                SimpleNamespace(source_page_mode="skeleton", save_debug_json=False, mode="api"),
                normalized,
                [FakeBlock(block_id="B0001", text="본문")],
                polisher,  # type: ignore[arg-type]
                raw_polish_dir=None,
                invalid_polish_dir=Path(tmp_dir) / "invalid",
                log=PipelineLog(Path(tmp_dir) / "pipeline.log"),
            )

        self.assertEqual(mode, "skeleton")
        self.assertEqual(source_polish, {})
        self.assertEqual(source_key_points, [{"text": "원본 핵심", "anchor_block_ids": ["B0001"]}])
        self.assertEqual(polisher.payloads, [])

    def test_source_page_polish_helper_maps_polished_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            normalized = {
                "document": {"document_id": "doc-1"},
                "concept_ledger": [{"slug": "concept-a"}],
                "semantic_notes": [{"semantic_summary": "요약", "key_points": [{"text": "원본 핵심", "anchor_block_ids": ["B0001"]}]}],
                "evidence_units": [],
            }
            polisher = FakeSectionPolisher(
                {
                    "section": "source_summary_and_key_points",
                    "text": "다듬은 요약 [B0001]",
                    "anchor_block_ids": ["B0001"],
                    "items": [{"text": "다듬은 핵심 [B0001]", "anchor_block_ids": ["B0001"]}],
                    "confidence": 0.8,
                }
            )

            source_polish, source_key_points, mode = _prepare_source_page_polish(
                SimpleNamespace(source_page_mode="section-polish", save_debug_json=False, mode="api"),
                normalized,
                [FakeBlock(block_id="B0001", text="본문")],
                polisher,  # type: ignore[arg-type]
                raw_polish_dir=None,
                invalid_polish_dir=Path(tmp_dir) / "invalid",
                log=PipelineLog(Path(tmp_dir) / "pipeline.log"),
            )

        self.assertEqual(mode, "section-polish")
        self.assertEqual(source_polish["summary"]["text"], "다듬은 요약")
        self.assertEqual(source_polish["key_points"]["items"][0]["text"], "다듬은 핵심")
        self.assertEqual(source_key_points[0]["text"], "다듬은 핵심")
        self.assertEqual(source_key_points[1]["text"], "원본 핵심")

    def test_concept_section_polish_helper_builds_polished_concept_page(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            normalized = {
                "document": {"document_id": "doc-1"},
                "concept_ledger": [
                    {
                        "slug": "concept-a",
                        "title": "Concept A",
                        "definition": "원본 정의",
                        "source_document_ids": ["doc-1"],
                        "display_reference_ids": ["B0001"],
                    }
                ],
                "evidence_units": [
                    {
                        "related_concept_slugs": ["concept-a"],
                        "claim": "근거",
                        "anchor_reference_ids": ["B0001"],
                        "source_document_id": "doc-1",
                    }
                ],
                "concept_resolutions": [{"canonical_slug": "concept-a", "link_targets": ["concept-b"]}],
                "warnings": [],
            }
            polisher = FakeSectionPolisher(
                {
                    "section": "concept_definition_key_points_and_related",
                    "text": "다듬은 정의 [B0001]",
                    "anchor_block_ids": ["B0001"],
                    "items": [{"text": "다듬은 핵심 [B0001]", "anchor_block_ids": ["B0001"]}],
                    "related_concept_hints": ["Concept B"],
                    "confidence": 0.7,
                }
            )

            concept_pages, generated_pages = _prepare_concept_section_polish(
                SimpleNamespace(save_debug_json=False),
                normalized,
                {"concept-a": [FakeBlock(block_id="B0001", text="본문")]},
                [{"text": "source 핵심", "anchor_reference_ids": ["B0001"]}],
                polisher,  # type: ignore[arg-type]
                raw_polish_dir=None,
                invalid_polish_dir=Path(tmp_dir) / "invalid",
                log=PipelineLog(Path(tmp_dir) / "pipeline.log"),
            )

        self.assertEqual(polisher.payloads[0]["context"]["resolution_link_targets"], ["concept-b"])
        self.assertEqual(generated_pages[0]["confidence"], 0.7)
        self.assertIn("다듬은 정의", concept_pages[0]["markdown"])
        self.assertIn("다듬은 핵심", concept_pages[0]["markdown"])


if __name__ == "__main__":
    unittest.main()
