import os
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from markitdown import MarkItDown


app = FastAPI(title="Fruition MarkItDown Converter")
markitdown = MarkItDown(enable_plugins=False)


def max_upload_bytes() -> int:
    return int(os.getenv("MAX_UPLOAD_MB", "50")) * 1024 * 1024


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/convert")
async def convert(file: UploadFile = File(...)) -> dict[str, str]:
    content = await file.read()
    if len(content) > max_upload_bytes():
        raise HTTPException(status_code=413, detail="File is too large")

    suffix = Path(file.filename or "").suffix.lower()
    if suffix != ".pdf" and file.content_type != "application/pdf":
        raise HTTPException(status_code=415, detail="Only PDF files are supported in the MVP")

    with tempfile.NamedTemporaryFile(suffix=".pdf") as temp_file:
        temp_file.write(content)
        temp_file.flush()
        result = markitdown.convert(temp_file.name)

    return {
        "filename": file.filename or "document.pdf",
        "content_type": file.content_type or "application/pdf",
        "markdown": result.text_content,
    }
