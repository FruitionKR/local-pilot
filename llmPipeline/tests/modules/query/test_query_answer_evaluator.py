from app.modules.query.infrastructure.query_answer_evaluator import _normalize_evaluation


def test_internal_supported_with_actionable_feedback_becomes_revision() -> None:
    evaluation = _normalize_evaluation(
        {
            "route": "internal_supported",
            "evidence_relevance": 0.9,
            "reason": "근거는 충분하지만 인용 문장을 고쳐야 합니다.",
            "feedback": "두 번째 문장의 인용을 직접 근거로 교체하세요.",
            "warnings": [],
        }
    )

    assert evaluation.route == "revise_answer"
    assert evaluation.feedback == "두 번째 문장의 인용을 직접 근거로 교체하세요."


def test_internal_supported_keeps_non_blocking_warnings() -> None:
    evaluation = _normalize_evaluation(
        {
            "route": "internal_supported",
            "evidence_relevance": 0.9,
            "reason": "답변과 근거가 일치합니다.",
            "feedback": "",
            "warnings": ["표현을 더 간결하게 만들 수 있습니다."],
        }
    )

    assert evaluation.route == "internal_supported"
    assert evaluation.warnings == ["표현을 더 간결하게 만들 수 있습니다."]
