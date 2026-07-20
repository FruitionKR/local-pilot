from unittest.mock import patch

from run_lab import parse_args


def test_wiki_evaluation_loop_is_enabled_by_default() -> None:
    with patch("sys.argv", ["run_lab.py", "--input", "input.md"]):
        args = parse_args()

    assert args.wiki_evaluation_loop is True


def test_wiki_evaluation_loop_can_be_disabled() -> None:
    with patch("sys.argv", ["run_lab.py", "--input", "input.md", "--no-wiki-evaluation-loop"]):
        args = parse_args()

    assert args.wiki_evaluation_loop is False
