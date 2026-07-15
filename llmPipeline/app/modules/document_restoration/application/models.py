from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from app.modules.document_restoration.domain.entities import RestorationStage


@dataclass(frozen=True)
class RestoreDocumentCommand:
    pdf_file: Path
    output_dir: Path
    document_slug: str
    docling_json: Path | None = None
    use_local_sllm: bool = False
    use_local_vision: bool = False
    endpoint: str = "http://127.0.0.1:11434/v1/chat/completions"
    model: str = "qwen2.5:7b"
    vision_model: str = "qwen2.5vl:7b"
    max_vision_attempts: int = 3
    docling_command: str = "docling"


@dataclass(frozen=True)
class PreparedRestoration:
    pdf_file: Path
    docling_json: Path
    manifest_file: Path


@dataclass(frozen=True)
class StageTiming:
    stage: RestorationStage
    elapsed_seconds: float
