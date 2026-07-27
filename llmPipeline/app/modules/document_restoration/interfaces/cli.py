import argparse
from pathlib import Path

from app.modules.document_restoration.application.models import RestoreDocumentCommand
from app.modules.document_restoration.application.restore_document import (
    RestoreDocumentUseCase,
)
from app.modules.document_restoration.domain.entities import RestorationMode
from app.modules.document_restoration.infrastructure.subprocess_restoration_stages import (
    SubprocessDocumentRestorationStages,
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf-file", type=Path, required=True)
    parser.add_argument("--docling-json", type=Path)
    parser.add_argument("--docling-markdown", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--document-slug", required=True)
    parser.add_argument(
        "--mode",
        choices=[mode.value for mode in RestorationMode],
        default=RestorationMode.DOCLING_ONLY.value,
        help=(
            "기본값은 Docling 결과만 게시합니다. 선택 복원은 selective-repair, "
            "기존 전체 복원은 full-repair를 사용합니다."
        ),
    )
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
    parser.add_argument(
        "--selective-endpoint",
        default="https://api.openai.com/v1/responses",
    )
    parser.add_argument("--selective-model", default="gpt-5.6-terra")
    parser.add_argument(
        "--selective-reasoning-effort",
        choices=["none", "low", "medium", "high", "xhigh", "max"],
        default="low",
    )
    parser.add_argument("--selective-max-workers", type=int, default=16)
    args = parser.parse_args()

    use_case = RestoreDocumentUseCase(SubprocessDocumentRestorationStages())
    use_case.execute(
        RestoreDocumentCommand(
            pdf_file=args.pdf_file.resolve(),
            output_dir=args.output_dir.resolve(),
            document_slug=args.document_slug,
            docling_json=args.docling_json.resolve() if args.docling_json else None,
            docling_markdown=(
                args.docling_markdown.resolve() if args.docling_markdown else None
            ),
            mode=RestorationMode(args.mode),
            use_local_sllm=args.use_local_sllm,
            use_local_vision=args.use_local_vision,
            endpoint=args.endpoint,
            model=args.model,
            vision_model=args.vision_model,
            max_vision_attempts=args.max_vision_attempts,
            docling_command=args.docling_command,
            selective_endpoint=args.selective_endpoint,
            selective_model=args.selective_model,
            selective_reasoning_effort=args.selective_reasoning_effort,
            selective_max_workers=args.selective_max_workers,
        )
    )


if __name__ == "__main__":
    main()
