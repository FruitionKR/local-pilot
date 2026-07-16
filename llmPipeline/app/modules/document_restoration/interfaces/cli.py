import argparse
from pathlib import Path

from app.modules.document_restoration.application.models import RestoreDocumentCommand
from app.modules.document_restoration.application.restore_document import (
    RestoreDocumentUseCase,
)
from app.modules.document_restoration.infrastructure.subprocess_restoration_stages import (
    SubprocessDocumentRestorationStages,
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf-file", type=Path, required=True)
    parser.add_argument("--docling-json", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--document-slug", required=True)
    parser.add_argument(
        "--use-local-sllm",
        action="store_true",
        help="규칙 기반 코드로 복원하지 못한 수식에 로컬 SLLM 보완을 사용합니다.",
    )
    parser.add_argument("--use-local-vision", action="store_true")
    parser.add_argument("--endpoint", default="http://127.0.0.1:11434/v1/chat/completions")
    parser.add_argument("--model", default="qwen2.5:7b")
    parser.add_argument("--vision-model", default="qwen2.5vl:7b")
    parser.add_argument("--max-vision-attempts", type=int, default=3)
    parser.add_argument("--docling-command", default="docling")
    args = parser.parse_args()

    use_case = RestoreDocumentUseCase(SubprocessDocumentRestorationStages())
    use_case.execute(
        RestoreDocumentCommand(
            pdf_file=args.pdf_file.resolve(),
            output_dir=args.output_dir.resolve(),
            document_slug=args.document_slug,
            docling_json=args.docling_json.resolve() if args.docling_json else None,
            use_local_sllm=args.use_local_sllm,
            use_local_vision=args.use_local_vision,
            endpoint=args.endpoint,
            model=args.model,
            vision_model=args.vision_model,
            max_vision_attempts=args.max_vision_attempts,
            docling_command=args.docling_command,
        )
    )


if __name__ == "__main__":
    main()
