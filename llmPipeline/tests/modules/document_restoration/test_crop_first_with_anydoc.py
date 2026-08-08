import json
import tempfile
import unittest
from pathlib import Path

from app.modules.document_restoration.infrastructure.crop_first_with_anydoc import (
    assemble,
    parse_heron_regions,
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


if __name__ == "__main__":
    unittest.main()
