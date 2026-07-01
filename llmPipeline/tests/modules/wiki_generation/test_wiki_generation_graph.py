import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from run_lab import PipelineLog, _run_wiki_generation_graph


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


if __name__ == "__main__":
    unittest.main()
