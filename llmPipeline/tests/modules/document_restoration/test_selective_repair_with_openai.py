import argparse
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

from app.modules.document_restoration.infrastructure.selective_repair_with_openai import (
    call_page,
    candidate_lane,
    clean_previous_results,
    group_candidates,
    markdown_fragments,
    normalize_replacement,
    page_markdown,
    response_text,
    run,
    save_replacements,
    select_candidates,
    valid_replacement,
)


class SelectiveRepairWithOpenAITest(unittest.TestCase):
    def test_selects_all_tables_equations_and_only_damaged_text(self) -> None:
        blocks = [
            {"id": "table", "type": "table_candidate"},
            {"id": "equation", "type": "equation_candidate"},
            {"id": "normal", "type": "paragraph", "source_text": "Normal text"},
            {
                "id": "damaged",
                "type": "paragraph",
                "source_text": "Normal text",
                "text_decision": "needs_text_adjudication",
            },
        ]

        self.assertEqual(
            [block["id"] for block in select_candidates(blocks)],
            ["table", "equation", "damaged"],
        )

    def test_groups_text_separately_from_tables_and_equations(self) -> None:
        blocks = [
            {"id": "text", "type": "paragraph", "page": 1},
            {"id": "table", "type": "table_candidate", "page": 1},
            {"id": "equation", "type": "equation_candidate", "page": 1},
        ]

        grouped = group_candidates(blocks)

        self.assertEqual(candidate_lane(blocks[0]), "text")
        self.assertEqual(
            [block["id"] for block in grouped[(1, "special")]],
            ["table", "equation"],
        )
        self.assertEqual(
            [block["id"] for block in grouped[(1, "text")]],
            ["text"],
        )

    def test_extracts_fragments_and_pages_from_detected_markdown(self) -> None:
        markdown = """## Page 1

<!-- docling_text_p01_001 type=paragraph bbox=[1, 2, 3, 4] confidence=x -->
First

## Page 2

<!-- docling_text_p02_001 type=paragraph bbox=[1, 2, 3, 4] confidence=x -->
Second
"""

        self.assertEqual(
            markdown_fragments(markdown),
            {
                "docling_text_p01_001": "First",
                "docling_text_p02_001": "Second",
            },
        )
        self.assertIn("First", page_markdown(markdown)[1])
        self.assertIn("Second", page_markdown(markdown)[2])

    def test_validates_replacement_by_block_type(self) -> None:
        self.assertTrue(valid_replacement("equation_candidate", "$$\nx_{1}=1\n$$"))
        self.assertTrue(valid_replacement("equation_candidate", r"\[x_{1}=1\]"))
        self.assertFalse(valid_replacement("equation_candidate", "$$x_{1}=1"))
        self.assertTrue(
            valid_replacement(
                "table_candidate",
                "| A | B |\n| --- | --- |\n| 1 | 2 |",
            )
        )
        self.assertFalse(valid_replacement("heading", "Heading"))
        self.assertTrue(valid_replacement("heading", "## Heading"))
        self.assertFalse(
            valid_replacement("heading", "## Heading\nUnexpected body")
        )
        self.assertEqual(
            normalize_replacement("equation_candidate", r"\[x_{1}=1\]"),
            "$$\nx_{1}=1\n$$",
        )
        self.assertTrue(
            valid_replacement(
                "paragraph",
                "Body XQ001QX",
                ["XQ001QX"],
            )
        )
        self.assertFalse(
            valid_replacement(
                "paragraph",
                "Body without marker",
                ["XQ001QX"],
            )
        )
        self.assertTrue(
            valid_replacement(
                "paragraph",
                "```python\nprint('visible code')\n```\nXQ001QX",
                ["XQ001QX"],
                scope="page_body",
            )
        )

    def test_rejects_keep_when_replacement_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            counts = save_replacements(
                Path(temp_dir),
                [
                    {
                        "id": "heron_table_p001_001",
                        "type": "table_candidate",
                        "replacement_required": True,
                    }
                ],
                {
                    "results": [
                        {
                            "block_id": "heron_table_p001_001",
                            "action": "keep",
                            "replacement": "",
                        }
                    ]
                },
            )

        self.assertEqual(counts, {"replace": 0, "keep": 0, "rejected": 1})

    def test_extracts_structured_output_text(self) -> None:
        payload = {"results": []}
        response = {
            "output": [
                {"type": "reasoning"},
                {
                    "type": "message",
                    "content": [
                        {
                            "type": "output_text",
                            "text": json.dumps(payload),
                        }
                    ],
                },
            ]
        }

        self.assertEqual(json.loads(response_text(response)), payload)

    def test_calls_responses_api_with_images_and_json_schema(self) -> None:
        response_body = {
            "output": [
                {
                    "type": "message",
                    "content": [
                        {
                            "type": "output_text",
                            "text": '{"results":[]}',
                        }
                    ],
                }
            ],
            "usage": {"total_tokens": 10},
        }

        class FakeResponse:
            def __enter__(self) -> "FakeResponse":
                return self

            def __exit__(self, *args: object) -> None:
                return None

            def read(self) -> bytes:
                return json.dumps(response_body).encode("utf-8")

        with mock.patch(
            "urllib.request.urlopen",
            return_value=FakeResponse(),
        ) as urlopen:
            result, usage = call_page(
                endpoint="https://api.openai.test/v1/responses",
                api_key="test-key",
                model="gpt-5.6-terra",
                reasoning_effort="low",
                prompt="restore",
                payload={"blocks": [{"scope": "page_body"}]},
                images=["data:image/png;base64,AA=="],
            )

        request = urlopen.call_args.args[0]
        body = json.loads(request.data.decode("utf-8"))
        self.assertEqual(body["model"], "gpt-5.6-terra")
        self.assertEqual(body["reasoning"], {"effort": "low"})
        self.assertEqual(body["text"]["format"]["type"], "json_schema")
        self.assertEqual(
            body["input"][1]["content"][1]["type"],
            "input_image",
        )
        self.assertEqual(
            body["input"][1]["content"][1]["detail"],
            "original",
        )
        self.assertEqual(result, {"results": []})
        self.assertEqual(usage, {"total_tokens": 10})

    def test_does_not_expose_responses_api_error_body(self) -> None:
        error = urllib.error.HTTPError(
            "https://api.openai.test/v1/responses",
            400,
            "Bad Request",
            {},
            None,
        )
        error.read = mock.Mock(return_value=b"sensitive document text")

        with (
            mock.patch("urllib.request.urlopen", side_effect=error),
            self.assertRaisesRegex(RuntimeError, r"^Responses API HTTP 400$"),
        ):
            call_page(
                endpoint="https://api.openai.test/v1/responses",
                api_key="test-key",
                model="gpt-5.6-terra",
                reasoning_effort="low",
                prompt="restore",
                payload={"blocks": []},
                images=[],
            )

        error.read.assert_not_called()

    def test_rejects_invalid_replacement_and_preserves_source(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir)
            blocks = [
                {
                    "id": "docling_formula_p01_001",
                    "type": "equation_candidate",
                }
            ]

            counts = save_replacements(
                output_dir,
                blocks,
                {
                    "results": [
                        {
                            "block_id": "docling_formula_p01_001",
                            "action": "replace",
                            "replacement": "broken",
                        }
                    ]
                },
            )

            self.assertEqual(counts, {"replace": 0, "keep": 0, "rejected": 1})
            self.assertFalse(
                (
                    output_dir
                    / "layout"
                    / "auto"
                    / "recovered_blocks"
                    / "docling_formula_p01_001.md"
                ).exists()
            )

    def test_cleans_all_results_from_previous_restoration_mode(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir)
            evaluation_dir = output_dir / "layout" / "auto" / "evaluations"
            recovered_dir = (
                output_dir / "layout" / "auto" / "recovered_blocks"
            )
            evaluation_dir.mkdir(parents=True)
            recovered_dir.mkdir(parents=True)
            stale_evaluation = evaluation_dir / "stale.json"
            stale_recovered = recovered_dir / "stale.md"
            preserved_evaluation = evaluation_dir / "preserved.json"
            stale_evaluation.write_text(
                json.dumps(
                    {"recovery_source": "openai_selective_repair"}
                ),
                encoding="utf-8",
            )
            stale_recovered.write_text("stale", encoding="utf-8")
            preserved_evaluation.write_text(
                json.dumps({"recovery_source": "local_ocr"}),
                encoding="utf-8",
            )
            preserved_recovered = recovered_dir / "preserved.md"
            preserved_recovered.write_text("local result", encoding="utf-8")

            clean_previous_results(output_dir)

            self.assertFalse(stale_evaluation.exists())
            self.assertFalse(stale_recovered.exists())
            self.assertFalse(preserved_evaluation.exists())
            self.assertFalse(preserved_recovered.exists())

    def test_retries_only_failed_or_rejected_items(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            manifest_file = root / "manifest.json"
            detected_markdown = root / "detected.md"
            output_dir = root / "output"
            blocks = [
                {
                    "id": "equation",
                    "type": "equation_candidate",
                    "page": 1,
                    "order": 1,
                    "bbox": [0, 0, 10, 10],
                },
                {
                    "id": "table",
                    "type": "table_candidate",
                    "page": 1,
                    "order": 2,
                    "bbox": [0, 10, 10, 20],
                },
                {
                    "id": "text",
                    "type": "paragraph",
                    "page": 1,
                    "order": 3,
                    "bbox": [0, 20, 10, 30],
                    "source_text": "damaged",
                    "text_decision": "needs_text_adjudication",
                },
            ]
            manifest_file.write_text(json.dumps(blocks), encoding="utf-8")
            detected_markdown.write_text("## Page 1\n", encoding="utf-8")
            text_calls = 0

            def fake_call_page(**kwargs: object) -> tuple[dict, dict]:
                nonlocal text_calls
                payload = kwargs["payload"]
                ids = [block["block_id"] for block in payload["blocks"]]
                if ids == ["equation", "table"]:
                    return {
                        "results": [
                            {
                                "block_id": "equation",
                                "action": "replace",
                                "replacement": "broken",
                            },
                            {
                                "block_id": "table",
                                "action": "keep",
                                "replacement": "",
                            },
                        ]
                    }, {"total_tokens": 10}
                if ids == ["equation"]:
                    return {
                        "results": [
                            {
                                "block_id": "equation",
                                "action": "replace",
                                "replacement": "$$\nx=1\n$$",
                            }
                        ]
                    }, {"total_tokens": 3}
                text_calls += 1
                if text_calls == 1:
                    raise TimeoutError
                return {
                    "results": [
                        {
                            "block_id": "text",
                            "action": "keep",
                            "replacement": "",
                        }
                    ]
                }, {"total_tokens": 2}

            args = argparse.Namespace(
                pdf_file=root / "source.pdf",
                manifest_file=manifest_file,
                detected_markdown=detected_markdown,
                output_dir=output_dir,
                endpoint="https://api.openai.test/v1/responses",
                model="gpt-5.6-luna",
                reasoning_effort="low",
                max_workers=2,
            )
            with (
                mock.patch.dict("os.environ", {"OPENAI_API_KEY": "test-key"}),
                mock.patch(
                    "app.modules.document_restoration.infrastructure."
                    "selective_repair_with_openai.render_page",
                    return_value="page-image",
                ),
                mock.patch(
                    "app.modules.document_restoration.infrastructure."
                    "selective_repair_with_openai.block_image",
                    return_value="block-image",
                ),
                mock.patch(
                    "app.modules.document_restoration.infrastructure."
                    "selective_repair_with_openai.call_page",
                    side_effect=fake_call_page,
                ),
            ):
                summary = run(args)

            self.assertEqual(summary["group_calls"], 2)
            self.assertEqual(summary["fallback_calls"], 2)
            self.assertEqual(summary["calls"], 4)
            self.assertTrue(
                (
                    output_dir
                    / "layout"
                    / "auto"
                    / "recovered_blocks"
                    / "equation.md"
                ).exists()
            )

    def test_page_body_uses_only_redacted_image_and_one_markdown_copy(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            manifest_file = root / "manifest.json"
            detected_markdown = root / "detected.md"
            output_dir = root / "output"
            draft = "Body XQ001QX"
            manifest_file.write_text(
                json.dumps(
                    [
                        {
                            "id": "anydoc_body_p001",
                            "type": "paragraph",
                            "page": 1,
                            "order": 0,
                            "bbox": [0, 0, 10, 10],
                            "source_text": draft,
                            "asset": "layout/crop_first/body_images/page-001.png",
                            "scope": "page_body",
                            "required_tokens": ["XQ001QX"],
                            "replacement_required": True,
                            "text_decision": "needs_text_adjudication",
                        }
                    ]
                ),
                encoding="utf-8",
            )
            detected_markdown.write_text(
                "## Page 1\n\n"
                "<!-- anydoc_body_p001 type=paragraph bbox=[0, 0, 10, 10] "
                "confidence=x -->\n"
                f"{draft}\n",
                encoding="utf-8",
            )

            def fake_call_page(**kwargs: object) -> tuple[dict, dict]:
                payload = kwargs["payload"]
                self.assertEqual(payload["page_context"], "")
                self.assertEqual(payload["blocks"][0]["current_markdown"], draft)
                self.assertEqual(kwargs["images"], ["redacted-page-image"])
                return {
                    "results": [
                        {
                            "block_id": "anydoc_body_p001",
                            "action": "replace",
                            "replacement": (
                                "```python\nprint('visible code')\n```\nXQ001QX"
                            ),
                        }
                    ]
                }, {"total_tokens": 10}

            args = argparse.Namespace(
                pdf_file=root / "source.pdf",
                manifest_file=manifest_file,
                detected_markdown=detected_markdown,
                output_dir=output_dir,
                endpoint="https://api.openai.test/v1/responses",
                model="gpt-5.6-luna",
                reasoning_effort="medium",
                max_workers=1,
            )
            with (
                mock.patch.dict("os.environ", {"OPENAI_API_KEY": "test-key"}),
                mock.patch(
                    "app.modules.document_restoration.infrastructure."
                    "selective_repair_with_openai.render_page"
                ) as render_page,
                mock.patch(
                    "app.modules.document_restoration.infrastructure."
                    "selective_repair_with_openai.block_image",
                    return_value="redacted-page-image",
                ),
                mock.patch(
                    "app.modules.document_restoration.infrastructure."
                    "selective_repair_with_openai.call_page",
                    side_effect=fake_call_page,
                ),
            ):
                summary = run(args)

            render_page.assert_not_called()
            self.assertEqual(summary["calls"], 1)
            self.assertEqual(summary["pages"][0]["replace"], 1)


if __name__ == "__main__":
    unittest.main()
