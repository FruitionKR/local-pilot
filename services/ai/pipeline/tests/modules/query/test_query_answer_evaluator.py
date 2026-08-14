from app.modules.query.infrastructure.query_answer_evaluator import (
    DEFAULT_QUERY_EVALUATOR_PROMPT,
    _normalize_evaluation,
)


def test_evaluator_prompt_defines_exclusive_route_order() -> None:
    prompt = DEFAULT_QUERY_EVALUATOR_PROMPT.read_text(encoding="utf-8")

    assert "Choose the first matching route in this exact order" in prompt
    assert "This check applies even when no substantive answer can be produced" in prompt
    assert "choose `internal_web_augmented`, never `web_fallback`" in prompt
    assert "not a hallucinated answer that still needs revision" in prompt
    assert "Mandatory answer-safety gate" in prompt
    assert "retrieved evidence is sufficient, but" not in prompt


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


def test_web_route_becomes_internal_revision_when_web_search_is_unavailable() -> None:
    evaluation = _normalize_evaluation(
        {
            "route": "internal_web_augmented",
            "reason": "내부 문서가 질문의 일부만 설명합니다.",
            "feedback": "",
            "web_query": "외부 구현 방법",
        },
        web_search_available=False,
        has_internal_evidence=True,
    )

    assert evaluation.route == "revise_answer"
    assert "내부 문서가 직접 뒷받침하는 내용만" in evaluation.feedback
    assert evaluation.web_query is None


def test_web_route_becomes_unsupported_without_internal_evidence_or_web_search() -> None:
    evaluation = _normalize_evaluation(
        {
            "route": "web_fallback",
            "reason": "내부 근거가 없습니다.",
            "web_query": "외부 질문",
        },
        web_search_available=False,
        has_internal_evidence=False,
    )

    assert evaluation.route == "unsupported"
    assert evaluation.feedback == ""
    assert evaluation.web_query is None
