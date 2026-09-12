from unittest.mock import Mock, patch

from app.modules.markdown_edit.domain.markdown_output_contract import (
    MarkdownOutputContractError,
)
from evaluate_markdown_edit import CASES, RecordingClient, run_case, summarize


def test_replay_consumes_first_generation_once_without_a_model_call():
    first = {"kind": "generation", "response": {"summary": "기록된 초안"}, "seconds": 2.5}
    with patch("evaluate_markdown_edit.ChatCompletionsJsonClient") as model, patch.dict(
        "os.environ", {"OPENAI_API_KEY": "test-key"}
    ):
        client = RecordingClient("gpt-5-nano", first)
        assert client.complete_json("system", "{}") == first["response"]
        model.return_value.complete_json.assert_not_called()
        client.complete_json("system", "{}")
        model.return_value.complete_json.assert_called_once()
    assert client.calls[0]["replayed"] is True
    assert client.calls[0]["seconds"] == 2.5


def test_evaluation_rejection_counts_as_failure_even_when_external_checks_pass():
    editor = Mock()
    editor.generate_edit.side_effect = MarkdownOutputContractError(
        ["LLM evaluation: rejected"],
        "**배포 전 테스트를 완료한다.**\n> 운영 DB를 직접 수정하지 않는다.",
    )
    with patch("evaluate_markdown_edit.RecordingClient") as client:
        client.return_value.calls = []
        row = run_case(
            Mock(return_value=editor), "candidate", CASES[0], 1, "gpt-5-nano"
        )
    assert row["external_failures"] == []
    assert row["returned"] is False
    assert row["passed"] is False


def test_blocked_correct_output_is_not_counted_as_quality_success():
    rows = [
        {
            "passed": True,
            "returned": True,
            "external_failures": [],
            "error_type": None,
            "seconds": 2,
            "calls": [{"kind": "generation"}, {"kind": "evaluation"}],
        },
        {
            "passed": False,
            "returned": False,
            "external_failures": [],
            "error_type": None,
            "seconds": 4,
            "calls": [{"kind": "generation"}, {"kind": "evaluation"}],
        },
        {
            "passed": False,
            "returned": True,
            "external_failures": ["누락"],
            "error_type": None,
            "seconds": 3,
            "calls": [{"kind": "generation"}],
        },
    ]
    assert summarize(rows) == {
        "total": 3,
        "passed": 1,
        "returned": 2,
        "invalid_returned": 1,
        "errors": 0,
        "average_seconds": 3,
        "median_seconds": 3,
        "generation_calls": 3,
        "evaluation_calls": 2,
    }
