import base64
import unittest
from pathlib import Path
from unittest import mock

from app import process_pdf


class ConverterCropFirstBoundaryTest(unittest.TestCase):
    def test_process_pdf_preserves_crop_asset_link_in_markdown(self) -> None:
        fixture = (
            b"\x89PNG\r\n\x1a\n"
            b"\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
            b"\x08\x06\x00\x00\x00\x1f\x15\xc4\x89"
            b"\x00\x00\x00\x0dIDAT\x08\xd7c\xf8\xcf\xc0\xf0\x1f\x00"
            b"\x05\x00\x01\xff\x89\x99=\x1d\x00\x00\x00\x00IEND\xaeB`\x82"
        )
        asset_paths: list[Path] = []

        def fake_run_to_file(
            command: list[str],
            output_file: Path,
            working_dir: Path,
            timeout_seconds: int,
            log_file: Path,
        ) -> None:
            output_file.write_text("diagnostic", encoding="utf-8")
            log_file.touch()

        def fake_run(
            command: list[str],
            working_dir: Path,
            timeout_seconds: int,
            log_file: Path,
        ) -> None:
            output_dir = Path(command[command.index("--output-dir") + 1])
            slug = command[command.index("--document-slug") + 1]
            asset = output_dir / "layout" / "crop_first" / "assets" / "figures" / "region.png"
            asset.parent.mkdir(parents=True)
            asset.write_bytes(fixture)
            asset_paths.append(asset)
            output_file = output_dir / "final" / f"{slug}.restored.md"
            output_file.parent.mkdir(parents=True)
            output_file.write_text(
                "![figure](../layout/crop_first/assets/figures/region.png)\n",
                encoding="utf-8",
            )

        with mock.patch("app.missing_commands", return_value=[]):
            with mock.patch("app.run_to_file", side_effect=fake_run_to_file):
                with mock.patch("app.run", side_effect=fake_run) as restoration:
                    result = process_pdf(b"pdf")

        command = restoration.call_args.args[0]
        self.assertEqual(command[command.index("--mode") + 1], "crop-first")
        self.assertFalse(asset_paths[0].exists())
        marker = "data:image/png;base64,"
        self.assertIn(marker, result["markdown"])
        encoded = result["markdown"].split(marker, 1)[1].split(")", 1)[0]
        self.assertEqual(base64.b64decode(encoded), fixture)


if __name__ == "__main__":
    unittest.main()
