from __future__ import annotations

import json
from dataclasses import asdict
from typing import Any

from app.modules.wiki_generation.application.evaluation_guards import (
    apply_generation_evaluation_guards,
)
from app.modules.wiki_generation.application.ports import JsonCompletionPort


def evaluate_generation(
    *,
    completion: JsonCompletionPort,
    evaluator_prompt: str,
    document: Any,
    blocks: list[Any],
    normalized: dict[str, Any],
) -> dict[str, Any]:
    payload = {
        "document": asdict(document),
        "source_blocks": [
            {"block_id": block.block_id, "text": block.text}
            for block in blocks
        ],
        "normalized": {
            "semantic_notes": normalized.get("semantic_notes", []),
            "concept_ledger": normalized.get("concept_ledger", []),
            "categories": normalized.get("categories", []),
            "section_candidates": normalized.get("section_candidates", []),
            "mentions": normalized.get("mentions", []),
            "observations": normalized.get("observations", []),
            "evidence_units": normalized.get("evidence_units", []),
            "warnings": normalized.get("warnings", []),
        },
    }
    evaluation = completion.complete_json(
        evaluator_prompt,
        json.dumps(payload, ensure_ascii=False, indent=2),
    )
    evaluation.setdefault("scores", {})
    evaluation.setdefault("passed", False)
    evaluation.setdefault("retry_recommended", not bool(evaluation.get("passed")))
    evaluation.setdefault("issues", [])
    evaluation.setdefault("retry_feedback", "")
    apply_generation_evaluation_guards(evaluation, normalized)
    return evaluation
