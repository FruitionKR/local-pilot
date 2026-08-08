import json
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from unittest.mock import patch

import fitz

from app.modules.document_restoration.infrastructure.crop_first_with_anydoc import (
    assemble,
    parse_heron_regions,
    prepare,
    select_body_pages,
)


class CropFirstWithAnyDocTest(unittest.TestCase):
    def test_parses_only_supported_heron_regions(self) -> None:
        output = "\n".join(
            [
                "REGION\tpaper.pdf\t1\ttable\t0.9\t1\t2\t3\t4",
                "REGION\tpaper.pdf\t1\tformula\t0.8\t5\t6\t7\t8",
                "REGION\tpaper.pdf\t2\tpicture\t0.7\t9\t10\t11\t12",
            ]
        )

        regions = parse_heron_regions(output)

        self.assertEqual(
            [region["type"] for region in regions],
            ["table_candidate", "equation_candidate", "figure_candidate"],
        )
        self.assertEqual(
            [region["token"] for region in regions],
            ["XQ001QX", "XQ002QX", "XQ003QX"],
        )
        self.assertFalse(regions[2]["replacement_required"])

    def test_selects_broken_pages_before_highest_difference(self) -> None:
        blocks = [
            {"page": 1, "body_broken": True, "body_difference": 0.1},
            {"page": 2, "body_broken": False, "body_difference": 0.8},
            {"page": 3, "body_broken": False, "body_difference": 0.4},
            {"page": 4, "body_broken": False, "body_difference": 0.2},
        ]

        select_body_pages(blocks, 0.5)

        self.assertEqual(
            [block["page"] for block in blocks if "text_decision" in block],
            [1, 2],
        )

    def test_assembles_body_special_recovery_and_original_figure(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            manifest_file = root / "manifest.json"
            output_file = root / "final" / "paper.restored.md"
            table_asset = root / "layout" / "crop_first" / "assets" / "specials" / "table.png"
            figure_asset = root / "layout" / "crop_first" / "assets" / "figures" / "figure.png"
            table_asset.parent.mkdir(parents=True)
            figure_asset.parent.mkdir(parents=True)
            table_asset.write_bytes(b"table")
            figure_asset.write_bytes(b"figure")
            recovered = root / "layout" / "auto" / "recovered_blocks"
            recovered.mkdir(parents=True)
            (recovered / "table.md").write_text(
                "| A |\n| --- |\n| 1 |\n",
                encoding="utf-8",
            )
            manifest_file.write_text(
                json.dumps(
                    [
                        {
                            "id": "body",
                            "page": 1,
                            "order": 0,
                            "type": "paragraph",
                            "source_text": "Intro XQ001QX XQ002QX End",
                            "body_broken": False,
                        },
                        {
                            "id": "table",
                            "page": 1,
                            "order": 1,
                            "type": "table_candidate",
                            "token": "XQ001QX",
                            "asset": str(table_asset.relative_to(root)),
                        },
                        {
                            "id": "figure",
                            "page": 1,
                            "order": 2,
                            "type": "figure_candidate",
                            "token": "XQ002QX",
                            "asset": str(figure_asset.relative_to(root)),
                        },
                    ]
                ),
                encoding="utf-8",
            )

            assemble(manifest_file, root, output_file)

            result = output_file.read_text(encoding="utf-8")
            self.assertIn("| A |", result)
            self.assertIn("![figure](../layout/crop_first/assets/figures/figure.png)", result)
            self.assertNotIn("XQ001QX", result)
            self.assertNotIn("XQ002QX", result)

    def test_keeps_anydoc_source_when_broken_body_recovery_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            manifest_file = root / "manifest.json"
            output_file = root / "final" / "paper.restored.md"
            manifest_file.write_text(
                json.dumps(
                    [
                        {
                            "id": "body",
                            "page": 1,
                            "order": 0,
                            "type": "paragraph",
                            "source_text": "AnyDoc 원문 본문",
                            "body_broken": True,
                        }
                    ]
                ),
                encoding="utf-8",
            )

            assemble(manifest_file, root, output_file)

            result = output_file.read_text(encoding="utf-8")
            self.assertIn("> 본문 자동 복원 실패", result)
            self.assertIn("AnyDoc 원문 본문", result)

    def test_checks_missing_unicode_usage_on_redacted_body_page(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            pdf_file = root / "source.pdf"
            source = fitz.open()
            page = source.new_page()
            page.insert_text((72, 72), "Body text")
            source.save(pdf_file)
            source.close()
            output_dir = root / "output"
            inspected_documents: list[Path] = []

            def run_anydoc(
                _command: str,
                _input_file: Path,
                output_file: Path,
                _log_file: Path,
            ) -> int:
                output_file.write_text("Body text", encoding="utf-8")
                return 0

            def inspect_page(page: fitz.Page, _missing_fonts: list[str]) -> int:
                inspected_documents.append(Path(page.parent.name))
                return 0

            args = Namespace(
                pdf_file=pdf_file,
                manifest_file=output_dir / "manifest.json",
                detected_markdown=output_dir / "detected.md",
                output_dir=output_dir,
                anydoc_command="anydoc",
                heron_command="raw-special-regions",
                heron_model=None,
                pdfium_library=None,
                body_ai_budget=0.3,
            )
            with (
                patch(
                    "app.modules.document_restoration.infrastructure."
                    "crop_first_with_anydoc.run_heron",
                    return_value=([], ""),
                ),
                patch(
                    "app.modules.document_restoration.infrastructure."
                    "crop_first_with_anydoc.run_anydoc",
                    side_effect=run_anydoc,
                ),
                patch(
                    "app.modules.document_restoration.infrastructure."
                    "crop_first_with_anydoc.missing_unicode_maps",
                    return_value=["MissingFont"],
                ),
                patch(
                    "app.modules.document_restoration.infrastructure."
                    "crop_first_with_anydoc.missing_unicode_usage",
                    side_effect=inspect_page,
                ),
            ):
                prepare(args)

            self.assertEqual(
                inspected_documents,
                [output_dir / "layout" / "crop_first" / "body_pages" / "page-001.pdf"],
            )


if __name__ == "__main__":
    unittest.main()
