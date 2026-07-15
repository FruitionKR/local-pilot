from __future__ import annotations

import unittest
from pathlib import Path

from app.modules.document_restoration.infrastructure.recover_blocks_with_ocr_sllm import (
    resolve_paddle_cache_dir,
)


class ResolvePaddleCacheDirTest(unittest.TestCase):
    def test_uses_nearest_parent_for_shallow_output_path(self) -> None:
        self.assertEqual(
            resolve_paddle_cache_dir(Path("/tmp/output")),
            Path("/tmp/paddle_cache"),
        )

    def test_handles_root_output_path(self) -> None:
        self.assertEqual(resolve_paddle_cache_dir(Path("/")), Path("/paddle_cache"))
