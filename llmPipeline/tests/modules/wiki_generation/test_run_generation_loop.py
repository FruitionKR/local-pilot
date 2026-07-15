from __future__ import annotations

import unittest

from app.modules.wiki_generation.application.run_generation_loop import (
    RunGenerationLoopUseCase,
)


class FakeSemanticGeneration:
    def __init__(self) -> None:
        self.prompts: list[str] = []

    def generate(
        self,
        system_prompt: str,
        attempt: int,
        source_context: dict[str, object] | None,
    ) -> list[dict[str, object]]:
        self.prompts.append(system_prompt)
        return [{"attempt": attempt, "source_context": source_context}]


class FakeNormalizer:
    def normalize_notes(self, notes: list[dict[str, object]]) -> dict[str, object]:
        return {"attempt": notes[0]["attempt"]}


class FakeEvaluator:
    def __init__(self, evaluations: list[dict[str, object]]) -> None:
        self.evaluations = evaluations

    def evaluate(self, normalized: dict[str, object]) -> dict[str, object]:
        return self.evaluations.pop(0)


class FakeRepairer:
    def __init__(self, operations: list[dict[str, object]] | None = None) -> None:
        self.operations = operations or []

    def repair(
        self,
        normalized: dict[str, object],
        evaluation: dict[str, object],
    ) -> tuple[dict[str, object], list[dict[str, object]]]:
        if not self.operations:
            return normalized, []
        return {**normalized, "repaired": True}, self.operations


class FakeEvents:
    def __init__(self) -> None:
        self.stages: list[str] = []

    def emit(self, stage: str, message: str, data: dict[str, object] | None = None) -> None:
        self.stages.append(stage)


class FakeEvaluationArtifacts:
    def __init__(self) -> None:
        self.items: list[tuple[int, str, dict[str, object]]] = []

    def write(self, attempt: int, kind: str, evaluation: dict[str, object]) -> None:
        self.items.append((attempt, kind, evaluation))


class RunGenerationLoopUseCaseTest(unittest.TestCase):
    def build_use_case(
        self,
        evaluations: list[dict[str, object]],
        operations: list[dict[str, object]] | None = None,
    ) -> tuple[RunGenerationLoopUseCase, FakeSemanticGeneration, FakeEvaluationArtifacts]:
        semantic_generation = FakeSemanticGeneration()
        artifacts = FakeEvaluationArtifacts()
        use_case = RunGenerationLoopUseCase(
            semantic_generation=semantic_generation,
            normalizer=FakeNormalizer(),
            evaluator=FakeEvaluator(evaluations),
            repairer=FakeRepairer(operations),
            events=FakeEvents(),
            evaluation_artifacts=artifacts,
        )
        return use_case, semantic_generation, artifacts

    def test_returns_after_normalization_when_evaluation_is_disabled(self) -> None:
        use_case, semantic_generation, artifacts = self.build_use_case([])

        notes, normalized, evaluations = use_case.execute(
            semantic_system_prompt="기본 prompt",
            source_context={"source": "기존"},
            evaluation_enabled=False,
            max_attempts=2,
        )

        self.assertEqual(notes[0]["source_context"], {"source": "기존"})
        self.assertEqual(normalized, {"attempt": 1})
        self.assertEqual(evaluations, [])
        self.assertEqual(semantic_generation.prompts, ["기본 prompt"])
        self.assertEqual(artifacts.items, [])

    def test_retries_with_evaluator_feedback(self) -> None:
        use_case, semantic_generation, artifacts = self.build_use_case(
            [
                {"passed": False, "retry_recommended": True, "retry_feedback": "근거를 보강하세요.", "scores": {}, "issues": []},
                {"passed": True, "retry_recommended": False, "scores": {}, "issues": []},
            ]
        )

        notes, normalized, evaluations = use_case.execute(
            semantic_system_prompt="기본 prompt",
            source_context=None,
            evaluation_enabled=True,
            max_attempts=2,
        )

        self.assertEqual(notes[0]["attempt"], 2)
        self.assertEqual(normalized, {"attempt": 2})
        self.assertEqual(len(evaluations), 2)
        self.assertIn("근거를 보강하세요.", semantic_generation.prompts[1])
        self.assertEqual([item[:2] for item in artifacts.items], [(1, "evaluation"), (2, "evaluation")])

    def test_uses_repaired_normalized_result(self) -> None:
        use_case, _, artifacts = self.build_use_case(
            [
                {"passed": False, "retry_recommended": True, "scores": {}, "issues": []},
                {"passed": True, "retry_recommended": False, "scores": {}, "issues": []},
            ],
            operations=[{"operation": "replace"}],
        )

        _, normalized, evaluations = use_case.execute(
            semantic_system_prompt="기본 prompt",
            source_context=None,
            evaluation_enabled=True,
            max_attempts=2,
        )

        self.assertTrue(normalized["repaired"])
        self.assertEqual(len(evaluations), 2)
        self.assertEqual([item[:2] for item in artifacts.items], [(1, "evaluation"), (1, "repair")])
