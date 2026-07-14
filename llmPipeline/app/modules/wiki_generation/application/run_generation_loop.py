from __future__ import annotations

from app.modules.wiki_generation.application.evaluation_guards import (
    repair_normalized_from_evaluation,
)
from app.modules.wiki_generation.application.ports import (
    EvaluationArtifactPort,
    GenerationEvaluatorPort,
    GenerationRepairPort,
    JsonDict,
    PipelineEventPort,
    SemanticGenerationPort,
    SemanticNormalizerPort,
)


class EvaluationGuardRepairer:
    def repair(self, normalized: JsonDict, evaluation: JsonDict) -> tuple[JsonDict, list[JsonDict]]:
        return repair_normalized_from_evaluation(normalized, evaluation)


class RunGenerationLoopUseCase:
    def __init__(
        self,
        semantic_generation: SemanticGenerationPort,
        normalizer: SemanticNormalizerPort,
        evaluator: GenerationEvaluatorPort,
        repairer: GenerationRepairPort,
        events: PipelineEventPort,
        evaluation_artifacts: EvaluationArtifactPort,
    ) -> None:
        self.semantic_generation = semantic_generation
        self.normalizer = normalizer
        self.evaluator = evaluator
        self.repairer = repairer
        self.events = events
        self.evaluation_artifacts = evaluation_artifacts

    def execute(
        self,
        *,
        semantic_system_prompt: str,
        source_context: JsonDict | None,
        evaluation_enabled: bool,
        max_attempts: int,
    ) -> tuple[list[JsonDict], JsonDict, list[JsonDict]]:
        attempt = 1
        prompt = semantic_system_prompt
        evaluations: list[JsonDict] = []

        while True:
            notes = self.semantic_generation.generate(prompt, attempt, source_context)
            self.events.emit(
                "3. 의미 추출 완료",
                "의미 노트 목록을 정규화 단계 입력으로 전달합니다.",
                {"시도": attempt, "노트 수": len(notes)},
            )
            normalized = self.normalizer.normalize_notes(notes)
            if not evaluation_enabled:
                return notes, normalized, evaluations

            evaluation = self.evaluator.evaluate(normalized)
            evaluations.append(evaluation)
            self.evaluation_artifacts.write(attempt, "evaluation", evaluation)
            self._emit_evaluation(attempt, evaluation)

            repaired_normalized, repair_operations = self.repairer.repair(normalized, evaluation)
            if repair_operations:
                normalized = repaired_normalized
                evaluation = self.evaluator.evaluate(normalized)
                evaluation["repair_operations"] = repair_operations
                evaluations.append(evaluation)
                self.evaluation_artifacts.write(attempt, "repair", evaluation)
                self._emit_repair(attempt, repair_operations, evaluation)

            if self._is_finished(evaluation, attempt, max_attempts):
                return notes, normalized, evaluations

            feedback = str(evaluation.get("retry_feedback") or "")
            prompt = (
                semantic_system_prompt
                + "\n\nEvaluator feedback for retry:\n"
                + feedback
                + "\nApply this feedback strictly. Keep source anchors exact. Return the same JSON schema."
            )
            attempt += 1

    def _emit_evaluation(self, attempt: int, evaluation: JsonDict) -> None:
        self.events.emit(
            "3-평가. Wiki 생성 평가",
            "정규화된 의미 구조를 평가했습니다.",
            {
                "시도": attempt,
                "passed": evaluation.get("passed"),
                "retry": evaluation.get("retry_recommended"),
                "overall": (evaluation.get("scores") or {}).get("overall"),
                "issue 수": len(evaluation.get("issues", [])),
            },
        )

    def _emit_repair(
        self,
        attempt: int,
        repair_operations: list[JsonDict],
        evaluation: JsonDict,
    ) -> None:
        self.events.emit(
            "3-평가-보정. Wiki 생성 보정",
            "평가 issue를 바탕으로 명확한 observation 문제를 자동 보정하고 다시 평가했습니다.",
            {
                "시도": attempt,
                "보정 수": len(repair_operations),
                "passed": evaluation.get("passed"),
                "retry": evaluation.get("retry_recommended"),
                "overall": (evaluation.get("scores") or {}).get("overall"),
                "issue 수": len(evaluation.get("issues", [])),
            },
        )

    @staticmethod
    def _is_finished(evaluation: JsonDict, attempt: int, max_attempts: int) -> bool:
        return bool(
            not evaluation.get("retry_recommended")
            or evaluation.get("passed")
            or attempt >= max_attempts
        )
