from __future__ import annotations

import re
from typing import Any


def apply_generation_evaluation_guards(evaluation: dict[str, Any], normalized: dict[str, Any]) -> None:
    core_slugs = {str(item.get("slug")) for item in normalized.get("concept_ledger", [])}
    metadata_fragments = {
        "citation-marker",
        "citation-rank",
        "retrieval-rank",
        "source-block",
        "web-url",
        "confidence",
    }
    fragmented = sorted(core_slugs.intersection(metadata_fragments))
    if len(fragmented) >= 3:
        _append_eval_issue(
            evaluation,
            {
                "metric": "concept_groundedness",
                "type": "over_fragmented_concept",
                "severity": "medium",
                "target": fragmented,
                "reason": "citation/source metadata가 독립 core concept로 과하게 분리됨",
                "feedback": "citation marker, citation_rank, retrieval_rank, source block, web URL, confidence는 독립 core concept가 아니라 citation/provenance metadata의 section_candidate 또는 mention으로 낮추세요.",
            },
        )
    _apply_observation_evaluation_guards(evaluation, normalized)
    if evaluation.get("issues"):
        evaluation["passed"] = False
        evaluation["retry_recommended"] = True
        scores = evaluation.setdefault("scores", {})
        if isinstance(scores.get("overall"), int | float):
            scores["overall"] = min(float(scores["overall"]), 0.74)
        feedbacks = [str(issue.get("feedback")) for issue in evaluation.get("issues", []) if issue.get("feedback")]
        evaluation["retry_feedback"] = " ".join(_unique(feedbacks))


def repair_normalized_from_evaluation(normalized: dict[str, Any], evaluation: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    repairable_types = {"observation_missing_ref", "broken_observation", "duplicate_observation"}
    issues = [issue for issue in evaluation.get("issues", []) if issue.get("type") in repairable_types]
    if not issues:
        return normalized, []
    repaired = {**normalized}
    observations = [dict(item) for item in normalized.get("observations", [])]
    if not observations:
        return normalized, []

    remove_ids: set[str] = set()
    duplicate_groups: list[list[str]] = []
    for issue in issues:
        targets = [str(target) for target in issue.get("target", []) if str(target)]
        if issue.get("type") in {"observation_missing_ref", "broken_observation"}:
            remove_ids.update(targets)
        elif issue.get("type") == "duplicate_observation" and len(targets) > 1:
            duplicate_groups.append(targets)

    operations: list[str] = []
    observations_by_id = {str(item.get("observation_id")): item for item in observations}
    for ids in duplicate_groups:
        candidates = [observations_by_id[item_id] for item_id in ids if item_id in observations_by_id and item_id not in remove_ids]
        if len(candidates) < 2:
            continue
        keeper = _select_observation_keeper(candidates)
        for candidate in candidates:
            if candidate is keeper:
                continue
            _merge_observation(keeper, candidate)
            remove_ids.add(str(candidate.get("observation_id")))
        operations.append(f"merged duplicate observations {ids} into {keeper.get('observation_id')}")

    before_count = len(observations)
    observations = [item for item in observations if str(item.get("observation_id")) not in remove_ids]
    if len(observations) != before_count:
        operations.append(f"removed {before_count - len(observations)} broken or duplicate observations")
    observations = _renumber_observations(observations)
    repaired["observations"] = observations

    repaired_notes = []
    valid_signatures = {_observation_content_signature(item) for item in observations}
    for note in normalized.get("semantic_notes", []):
        note_copy = {**note}
        note_observations = []
        for observation in note.get("observations", []):
            if _observation_content_signature(observation) in valid_signatures:
                note_observations.append(observation)
        note_copy["observations"] = note_observations
        repaired_notes.append(note_copy)
    repaired["semantic_notes"] = repaired_notes
    return repaired, operations


def _select_observation_keeper(observations: list[dict[str, Any]]) -> dict[str, Any]:
    return max(
        observations,
        key=lambda item: (
            len(item.get("anchor_reference_ids", []) or []),
            len(str(item.get("summary") or "")),
            len(item.get("claims", []) or []),
        ),
    )


def _merge_observation(target: dict[str, Any], incoming: dict[str, Any]) -> None:
    target["anchor_reference_ids"] = _unique((target.get("anchor_reference_ids", []) or []) + (incoming.get("anchor_reference_ids", []) or []))
    target["claims"] = _unique([str(item).strip() for item in (target.get("claims", []) or []) + (incoming.get("claims", []) or []) if str(item).strip()])
    target["related_concept_hints"] = _unique(
        [str(item).strip() for item in (target.get("related_concept_hints", []) or []) + (incoming.get("related_concept_hints", []) or []) if str(item).strip()]
    )
    for field in ("summary", "title", "query_text"):
        if len(str(incoming.get(field) or "")) > len(str(target.get(field) or "")):
            target[field] = incoming.get(field)


def _renumber_observations(observations: list[dict[str, Any]]) -> list[dict[str, Any]]:
    renumbered = []
    for idx, observation in enumerate(observations, start=1):
        renumbered.append({**observation, "observation_id": f"O{idx:03d}"})
    return renumbered


def _observation_content_signature(observation: dict[str, Any]) -> str:
    return "\n".join(
        [
            str(observation.get("type") or ""),
            str(observation.get("title") or ""),
            str(observation.get("query_text") or ""),
            str(observation.get("summary") or ""),
        ]
    )


def _apply_observation_evaluation_guards(evaluation: dict[str, Any], normalized: dict[str, Any]) -> None:
    observations = normalized.get("observations", [])
    for observation in observations:
        observation_id = str(observation.get("observation_id") or "unknown")
        refs = observation.get("anchor_reference_ids", []) or []
        summary = str(observation.get("summary") or "").strip()
        title = str(observation.get("title") or "").strip()
        claims = [str(claim).strip() for claim in observation.get("claims", []) if str(claim).strip()]
        if not refs:
            _append_eval_issue(
                evaluation,
                {
                    "metric": "source_faithfulness",
                    "type": "observation_missing_ref",
                    "severity": "medium",
                    "target": [observation_id],
                    "reason": "Observation이 원문 source block anchor 없이 생성되어 검색 근거로 신뢰하기 어렵습니다.",
                    "feedback": "모든 observation은 직접 사용한 anchor_block_ids를 포함해야 합니다. 근거가 없으면 해당 observation을 제거하세요.",
                },
            )
        if _is_broken_observation_text(summary) or (not summary and not claims):
            _append_eval_issue(
                evaluation,
                {
                    "metric": "source_faithfulness",
                    "type": "broken_observation",
                    "severity": "medium",
                    "target": [observation_id],
                    "reason": "Observation summary가 중간에서 끊겼거나 검색 단위로 쓸 수 있을 만큼 완성되지 않았습니다.",
                    "feedback": "chunk 경계에서 깨진 observation은 제거하고, 같은 의미의 정상 observation만 남기세요.",
                },
            )
        if not title and not summary and not claims:
            _append_eval_issue(
                evaluation,
                {
                    "metric": "source_faithfulness",
                    "type": "broken_observation",
                    "severity": "medium",
                    "target": [observation_id],
                    "reason": "Observation에 title, summary, claims가 모두 없어 검색 단위로 사용할 수 없습니다.",
                    "feedback": "빈 observation을 생성하지 마세요.",
                },
            )

    buckets: dict[str, list[str]] = {}
    for observation in observations:
        signature = _observation_signature(observation)
        if not signature:
            continue
        buckets.setdefault(signature, []).append(str(observation.get("observation_id") or "unknown"))
    duplicates = [ids for ids in buckets.values() if len(ids) > 1]
    for ids in duplicates:
        _append_eval_issue(
            evaluation,
            {
                "metric": "source_coverage",
                "type": "duplicate_observation",
                "severity": "medium",
                "target": ids,
                "reason": "서로 다른 chunk나 registry 섹션에서 같은 의미의 observation이 중복 생성되었습니다.",
                "feedback": "query_text/resolved intent와 summary가 같은 observation은 하나로 병합하고 가장 직접적인 source block refs를 유지하세요.",
            },
        )


def _is_broken_observation_text(text: str) -> bool:
    if not text:
        return False
    stripped = text.strip()
    if stripped in {"-", "없음", "N/A"}:
        return True
    openers = {"(": ")", "[": "]", "{": "}", "“": "”", "\"": "\"", "'": "'"}
    for opener, closer in openers.items():
        if stripped.endswith(opener):
            return True
        if stripped.count(opener) > stripped.count(closer):
            return True
    return len(stripped) < 8


def _observation_signature(observation: dict[str, Any]) -> str:
    query = _compact_observation_text(str(observation.get("query_text") or ""))
    summary = _compact_observation_text(str(observation.get("summary") or ""))
    title = _compact_observation_text(str(observation.get("title") or ""))
    if query:
        return f"q:{query}"
    if summary:
        return f"s:{summary[:80]}"
    return f"t:{title}" if title else ""


def _compact_observation_text(text: str) -> str:
    text = re.sub(r"\s+", " ", text.lower()).strip()
    text = re.sub(r"[^0-9a-z가-힣 ]+", "", text)
    return text


def _append_eval_issue(evaluation: dict[str, Any], issue: dict[str, Any]) -> None:
    issues = evaluation.setdefault("issues", [])
    signature = (issue.get("type"), tuple(issue.get("target", [])))
    for existing in issues:
        if (existing.get("type"), tuple(existing.get("target", []))) == signature:
            return
    issues.append(issue)


def _unique(values: list[str]) -> list[str]:
    seen = set()
    rows = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        rows.append(value)
    return rows
