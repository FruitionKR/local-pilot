import argparse
import json
import os
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

from app.modules.document_restoration.infrastructure.crop_first_with_anydoc import (
    assemble,
)
from app.modules.document_restoration.infrastructure.selective_repair_with_openai import (
    call_page,
    clean_previous_results,
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
                payload={"blocks": []},
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

    def test_propagates_retryable_responses_api_errors_for_fallback(self) -> None:
        for status_code in (429, 500):
            error = urllib.error.HTTPError(
                "https://api.openai.test/v1/responses",
                status_code,
                "Retry",
                {},
                None,
            )
            with self.subTest(status_code=status_code), (
                mock.patch("urllib.request.urlopen", side_effect=error)
            ), self.assertRaises(urllib.error.HTTPError) as raised:
                call_page(
                    endpoint="https://api.openai.test/v1/responses",
                    api_key="test-key",
                    model="gpt-5.6-terra",
                    reasoning_effort="low",
                    prompt="restore",
                    payload={"blocks": []},
                    images=[],
                )

            self.assertEqual(raised.exception.code, status_code)

    def test_skips_openai_without_api_key_and_keeps_baseline(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir)
            manifest_file = output_dir / "manifest.json"
            detected_markdown = output_dir / "detected.md"
            manifest_file.write_text(
                json.dumps(
                    [
                        {
                            "id": "body-1",
                            "page": 1,
                            "order": 0,
                            "type": "paragraph",
                            "source_text": "before XQ001QX after",
                            "body_broken": False,
                        },
                        {
                            "id": "table-1",
                            "page": 1,
                            "order": 1,
                            "type": "table_candidate",
                            "token": "XQ001QX",
                            "asset": "layout/crop_first/assets/specials/table.png",
                        },
                    ]
                ),
                encoding="utf-8",
            )
            asset = (
                output_dir
                / "layout"
                / "crop_first"
                / "assets"
                / "specials"
                / "table.png"
            )
            asset.parent.mkdir(parents=True)
            asset.write_bytes(b"png")
            detected_markdown.write_text("baseline", encoding="utf-8")
            args = argparse.Namespace(
                pdf_file=output_dir / "input.pdf",
                manifest_file=manifest_file,
                detected_markdown=detected_markdown,
                output_dir=output_dir,
                endpoint="https://api.openai.test/v1/responses",
                model="gpt-5.6-luna",
                reasoning_effort="medium",
                max_workers=1,
            )

            with mock.patch.dict(
                os.environ,
                {"DOCUMENT_REPAIR_OPENAI_API_KEY": "", "OPENAI_API_KEY": ""},
            ), mock.patch(
                "app.modules.document_restoration.infrastructure.selective_repair_with_openai.call_page"
            ) as call_page_mock:
                summary = run(args)

            self.assertEqual(summary["calls"], 0)
            self.assertEqual(summary["blocks"], 1)
            call_page_mock.assert_not_called()
            output_file = output_dir / "final" / "restored.md"
            assemble(manifest_file, output_dir, output_file)
            self.assertIn(
                "[source crop](../layout/crop_first/assets/specials/table.png)",
                output_file.read_text(encoding="utf-8"),
            )

    def test_uses_baseline_when_retryable_openai_errors_exhaust_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir)
            manifest_file = output_dir / "manifest.json"
            detected_markdown = output_dir / "detected.md"
            manifest_file.write_text(
                json.dumps(
                    [
                        {
                            "id": "table-1",
                            "page": 1,
                            "order": 1,
                            "type": "table_candidate",
                            "bbox": [0, 0, 1, 1],
                        }
                    ]
                ),
                encoding="utf-8",
            )
            detected_markdown.write_text("## Page 1\n", encoding="utf-8")
            args = argparse.Namespace(
                pdf_file=output_dir / "input.pdf",
                manifest_file=manifest_file,
                detected_markdown=detected_markdown,
                output_dir=output_dir,
                endpoint="https://api.openai.test/v1/responses",
                model="gpt-5.6-luna",
                reasoning_effort="medium",
                max_workers=1,
            )
            error = urllib.error.HTTPError(
                "https://api.openai.test/v1/responses", 429, "Retry", {}, None
            )

            with mock.patch.dict(
                os.environ, {"DOCUMENT_REPAIR_OPENAI_API_KEY": "test-key"}
            ), mock.patch(
                "app.modules.document_restoration.infrastructure.selective_repair_with_openai.render_page",
                return_value="page",
            ), mock.patch(
                "app.modules.document_restoration.infrastructure.selective_repair_with_openai.block_image",
                return_value="crop",
            ), mock.patch(
                "app.modules.document_restoration.infrastructure.selective_repair_with_openai.call_page",
                side_effect=error,
            ) as call_page_mock:
                summary = run(args)

            self.assertEqual(call_page_mock.call_count, 2)
            self.assertEqual(summary["pages"][0]["failed"], 1)
            self.assertEqual(summary["pages"][0]["batch_error"], "HTTPError")

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


if __name__ == "__main__":
    unittest.main()
