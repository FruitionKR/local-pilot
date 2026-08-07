import os
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile


app = FastAPI(title="Fruition PDF Converter")

OCR_TIMEOUT_SECONDS = int(os.getenv("OCR_TIMEOUT_SECONDS", "600"))
MARKDOWN_TIMEOUT_SECONDS = int(os.getenv("MARKDOWN_TIMEOUT_SECONDS", "300"))
PDF_DIAGNOSTIC_TIMEOUT_SECONDS = int(os.getenv("PDF_DIAGNOSTIC_TIMEOUT_SECONDS", "60"))


def max_upload_bytes() -> int:
    return int(os.getenv("MAX_UPLOAD_MB", "50")) * 1024 * 1024


def ocr_language() -> str:
    return os.getenv("OCR_LANGUAGE", "kor+eng")


def required_commands() -> list[str]:
    return ["pdfinfo", "pdffonts", "ocrmypdf", "markitdown"]


def missing_commands() -> list[str]:
    return [command for command in required_commands() if shutil.which(command) is None]


@app.get("/health")
def health() -> dict[str, Any]:
    missing = missing_commands()
    return {
        "status": "ok" if not missing else "degraded",
        "missing_commands": missing,
    }


def run_to_file(
    command: list[str],
    output_file: Path,
    working_dir: Path,
    timeout_seconds: int,
    log_file: Path,
) -> None:
    with output_file.open("w", encoding="utf-8") as stdout, log_file.open("a", encoding="utf-8") as log:
        log.write(f"$ {' '.join(command)}\n")
        try:
            process = subprocess.run(
                command,
                cwd=working_dir,
                stdout=stdout,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=timeout_seconds,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            log.write(f"timeout after {timeout_seconds}s\n\n")
            raise HTTPException(status_code=504, detail=f"Command timeout: {command[0]}") from exc

        log.write(f"exit={process.returncode}\n\n")
        if process.returncode != 0:
            raise HTTPException(status_code=422, detail=f"Command failed: {command[0]}")


def run(
    command: list[str],
    working_dir: Path,
    timeout_seconds: int,
    log_file: Path,
) -> None:
    with log_file.open("a", encoding="utf-8") as log:
        log.write(f"$ {' '.join(command)}\n")
        try:
            process = subprocess.run(
                command,
                cwd=working_dir,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=timeout_seconds,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            log.write(f"timeout after {timeout_seconds}s\n\n")
            raise HTTPException(status_code=504, detail=f"Command timeout: {command[0]}") from exc

        log.write(process.stdout)
        if process.stdout and not process.stdout.endswith("\n"):
            log.write("\n")
        log.write(f"exit={process.returncode}\n\n")

        if process.returncode != 0:
            raise HTTPException(status_code=422, detail=f"Command failed: {command[0]}")


def process_pdf(content: bytes) -> dict[str, str]:
    if missing := missing_commands():
        raise HTTPException(
            status_code=503,
            detail=f"Missing converter commands: {', '.join(missing)}",
        )

    with tempfile.TemporaryDirectory(prefix="fruition-pdf-") as temp_dir:
        job_dir = Path(temp_dir) / str(uuid.uuid4())
        job_dir.mkdir(mode=0o700)

        input_pdf = job_dir / "input.pdf"
        info_txt = job_dir / "info.txt"
        fonts_txt = job_dir / "fonts.txt"
        fixed_pdf = job_dir / "fixed.pdf"
        output_md = job_dir / "output.md"
        process_log = job_dir / "process.log"

        input_pdf.write_bytes(content)

        run_to_file(
            ["pdfinfo", input_pdf.name],
            info_txt,
            job_dir,
            PDF_DIAGNOSTIC_TIMEOUT_SECONDS,
            process_log,
        )
        run_to_file(
            ["pdffonts", input_pdf.name],
            fonts_txt,
            job_dir,
            PDF_DIAGNOSTIC_TIMEOUT_SECONDS,
            process_log,
        )
        run(
            [
                "ocrmypdf",
                "-l",
                ocr_language(),
                "--force-ocr",
                "--deskew",
                "--clean",
                input_pdf.name,
                fixed_pdf.name,
            ],
            job_dir,
            OCR_TIMEOUT_SECONDS,
            process_log,
        )
        run(
            ["markitdown", fixed_pdf.name, "-o", output_md.name],
            job_dir,
            MARKDOWN_TIMEOUT_SECONDS,
            process_log,
        )

        return {
            "markdown": output_md.read_text(encoding="utf-8"),
            "pdfinfo": info_txt.read_text(encoding="utf-8", errors="replace"),
            "pdffonts": fonts_txt.read_text(encoding="utf-8", errors="replace"),
            "process_log": process_log.read_text(encoding="utf-8", errors="replace"),
        }


@app.post("/convert")
async def convert(file: UploadFile = File(...)) -> dict[str, str]:
    content = await file.read()
    if len(content) > max_upload_bytes():
        raise HTTPException(status_code=413, detail="File is too large")

    suffix = Path(file.filename or "").suffix.lower()
    if suffix != ".pdf" and file.content_type != "application/pdf":
        raise HTTPException(status_code=415, detail="Only PDF files are supported in the MVP")

    result = process_pdf(content)

    return {
        "filename": file.filename or "document.pdf",
        "content_type": file.content_type or "application/pdf",
        **result,
    }
