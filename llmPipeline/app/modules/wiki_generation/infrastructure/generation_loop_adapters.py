from __future__ import annotations

from pathlib import Path
from typing import Any

from app.modules.wiki_generation.application.evaluate_generation import (
    evaluate_generation,
)
from app.modules.wiki_generation.application.ports import (
    JsonCompletionPort,
    PipelineEventPort,
)
from app.modules.wiki_generation.infrastructure.chat_completions_llm import (
    ApiSemanticExtractor,
)
from app.modules.wiki_ingestion.infrastructure.file_io import ensure_dir, write_json


class SemanticGenerationAdapter:
    def __init__(
        self,
        completion: JsonCompletionPort,
        packets: list[Any],
        raw_dir: Path | None,
        events: PipelineEventPort,
    ) -> None:
        self.completion = completion
        self.packets = packets
        self.raw_dir = raw_dir
        self.events = events

    def generate(
        self,
        system_prompt: str,
        attempt: int,
        source_context: dict[str, Any] | None,
    ) -> list[dict[str, Any]]:
        extractor = ApiSemanticExtractor(
            self.completion,
            system_prompt,
            source_context=source_context,
        )
        notes = []
        for packet in self.packets:
            note = extractor.extract(packet)
            notes.append(note)
            if self.raw_dir is not None:
                suffix = "" if attempt == 1 else f".attempt{attempt}"
                write_json(self.raw_dir / f"{packet.chunk_id}{suffix}.json", note)
            self.events.emit(
                "3. 의미 추출",
                "패킷에서 의미 노트를 추출했고, 노트 객체를 메모리에 추가했습니다.",
                {
                    "시도": attempt,
                    "패킷": packet.chunk_id,
                    "핵심 포인트 수": len(note.get("key_points", [])),
                    "core concept 수": len(note.get("core_concepts") or note.get("concept_candidates", [])),
                    "section/mention/category 수": f"{len(note.get('section_candidates', []))}/{len(note.get('mentions', []))}/{len(note.get('categories', []))}",
                    "근거 주장 수": len(note.get("evidence_claims", [])),
                },
            )
        return notes


class GenerationEvaluatorAdapter:
    def __init__(
        self,
        completion: JsonCompletionPort,
        evaluator_prompt: str,
        document: Any,
        blocks: list[Any],
    ) -> None:
        self.completion = completion
        self.evaluator_prompt = evaluator_prompt
        self.document = document
        self.blocks = blocks

    def evaluate(self, normalized: dict[str, Any]) -> dict[str, Any]:
        return evaluate_generation(
            completion=self.completion,
            evaluator_prompt=self.evaluator_prompt,
            document=self.document,
            blocks=self.blocks,
            normalized=normalized,
        )


class EvaluationArtifactAdapter:
    def __init__(self, out: Path, enabled: bool) -> None:
        self.out = out
        self.enabled = enabled

    def write(self, attempt: int, kind: str, evaluation: dict[str, Any]) -> None:
        if not self.enabled:
            return
        suffix = ".repair" if kind == "repair" else ""
        path = ensure_dir(self.out / "raw_llm_outputs" / "wiki_evaluation") / f"attempt_{attempt:02d}{suffix}.json"
        write_json(path, evaluation)
