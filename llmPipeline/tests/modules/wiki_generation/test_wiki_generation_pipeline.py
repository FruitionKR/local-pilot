import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from app.modules.wiki_generation.infrastructure.chat_source_accumulation import (
    apply_chat_source_accumulation_result,
    build_chat_source_accumulation_payload,
)
from run_lab import (
    PipelineLog,
    _extract_pipeline_source,
    _load_pipeline_prompts,
    _prepare_concept_section_polish,
    _prepare_source_page_polish,
    _run_wiki_generation_loop,
)


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


class WikiGenerationPipelineTest(unittest.TestCase):
    def test_load_pipeline_prompts_returns_named_prompt_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            prompt_paths = {}
            for name in (
                "system_prompt",
                "concept_system_prompt",
                "concept_resolution_system_prompt",
                "section_polish_system_prompt",
                "source_accumulation_system_prompt",
                "wiki_evaluator_system_prompt",
                "wiki_patch_system_prompt",
            ):
                path = Path(tmp_dir) / f"{name}.md"
                path.write_text(name, encoding="utf-8")
                prompt_paths[name] = str(path)

            prompts = _load_pipeline_prompts(
                SimpleNamespace(**prompt_paths),
                PipelineLog(Path(tmp_dir) / "pipeline.log"),
            )

        self.assertEqual(prompts.semantic, "system_prompt")
        self.assertEqual(prompts.concept, "concept_system_prompt")
        self.assertEqual(prompts.wiki_patch, "wiki_patch_system_prompt")

    def test_extract_pipeline_source_applies_requested_document_id(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            document, blocks, source_block_records = _extract_pipeline_source(
                SimpleNamespace(
                    selection_mode=None,
                    source_document_id="requested-document",
                    save_debug_json=False,
                ),
                input_text="# 문서\n\n본문입니다.",
                input_source_name="inline.md",
                input_path=Path("inline.md"),
                out=Path(tmp_dir),
                log=PipelineLog(Path(tmp_dir) / "pipeline.log"),
            )

        self.assertEqual(document.document_id, "requested-document")
        self.assertTrue(blocks)
        self.assertTrue(all(block.document_id == "requested-document" for block in blocks))
        self.assertEqual(source_block_records[0]["document_id"], "requested-document")

    def test_build_chat_source_accumulation_payload_uses_existing_source_context(self) -> None:
        normalized = {
            "document": {"document_id": "chat-doc-1"},
            "existing_source_context": {"summary": "기존 요약"},
            "semantic_notes": [
                {
                    "semantic_summary": "신규 요약",
                    "key_points": [{"text": "신규 핵심", "anchor_reference_ids": ["pair_2"]}],
                }
            ],
            "observations": [{"summary": "관찰"}],
            "categories": [{"name": "운영"}],
        }

        payload = build_chat_source_accumulation_payload(normalized, "# Existing Source")

        self.assertEqual(payload["context"]["document"], {"document_id": "chat-doc-1"})
        self.assertEqual(payload["context"]["existing_source_summary"], "기존 요약")
        self.assertEqual(payload["context"]["existing_source_markdown"], "# Existing Source")
        self.assertEqual(payload["draft"]["accumulated_summary_candidates"], ["신규 요약"])
        self.assertEqual(payload["draft"]["key_points"][0]["text"], "신규 핵심")

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

        def fake_semantic_extraction(
            _self,
            system_prompt,
            attempt,
            _source_context,
            _previous_notes=None,
            _target_block_ids=None,
        ):
            prompts.append(system_prompt)
            return [{"attempt": attempt}]

        def fake_evaluate_generation(_self, _normalized):
            return evaluations.pop(0)

        with tempfile.TemporaryDirectory() as tmp_dir:
            with patch("run_lab.SemanticGenerationAdapter.generate", new=fake_semantic_extraction):
                with patch("run_lab.GenerationEvaluatorAdapter.evaluate", new=fake_evaluate_generation):
                    notes, normalized, generation_evaluations = _run_wiki_generation_loop(
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
                "existing_source_context": {
                    "summary": "기존 전체 요약",
                    "source_markdown": "# 기존 source\n\n## Summary\n기존 전체 요약",
                },
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
        self.assertEqual(polisher.payloads[0]["context"]["existing_source_summary"], "기존 전체 요약")
        self.assertIn("기존 source", polisher.payloads[0]["context"]["existing_source_markdown"])
        self.assertEqual(polisher.payloads[0]["draft"]["new_summary_candidates"], ["요약"])
        self.assertNotIn("summary_candidates", polisher.payloads[0]["draft"])

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

    def test_apply_source_accumulation_result_replaces_source_sections_with_validated_refs(self) -> None:
        normalized = {
            "warnings": [],
            "observations": [{"summary": "기존 observation", "anchor_reference_ids": ["B0001"]}],
            "categories": [{"name": "기존"}],
        }
        raw = {
            "passed": True,
            "issues": [],
            "revised_source": {
                "summary": {"text": "전체 요약", "anchor_block_ids": ["B0001", "B9999"]},
                "key_points": [{"text": "중복 의미를 합친 핵심", "anchor_block_ids": ["B0001", "B0002"]}],
                "observations": [
                    {
                        "type": "qa_episode",
                        "title": "합친 observation",
                        "query_text": None,
                        "summary": "기존과 신규 observation을 합친다.",
                        "claims": ["기존과 신규 근거를 모두 유지한다."],
                        "related_concept_hints": ["concept-a"],
                        "anchor_block_ids": ["B0001", "B0002"],
                    }
                ],
                "categories": [{"name": "운영"}],
            },
        }

        result = apply_chat_source_accumulation_result(
            normalized,
            raw,
            [FakeBlock(block_id="B0001", text="기존"), FakeBlock(block_id="B0002", text="신규")],
        )

        self.assertEqual(result["source_accumulation_polish"]["summary"]["text"], "전체 요약")
        self.assertEqual(result["source_accumulation_polish"]["summary"]["anchor_reference_ids"], ["B0001"])
        self.assertEqual(result["source_accumulation_polish"]["key_points"]["items"][0]["anchor_reference_ids"], ["B0001", "B0002"])
        self.assertEqual(result["observations"][0]["summary"], "기존과 신규 observation을 합친다.")
        self.assertEqual(result["categories"], [{"name": "운영"}])
        self.assertEqual(result["warnings"], ["source_accumulation.summary: unknown accumulation anchor_block_id B9999"])


if __name__ == "__main__":
    unittest.main()
