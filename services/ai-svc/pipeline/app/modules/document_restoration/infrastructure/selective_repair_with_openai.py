from __future__ import annotations

import argparse
import base64
import json
import mimetypes
import os
import re
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

import fitz

from app.modules.document_restoration.domain.markdown_text import (
    has_balanced_braces,
    is_valid_markdown_table,
)
from app.modules.document_restoration.domain.text_quality import looks_glyph_encoded


PROMPT_FILE = (
    Path(__file__).resolve().parents[4]
    / "prompts"
    / "document_restoration"
    / "direct_visual_block_repair.md"
)
OUTPUT_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "properties": {
        "results": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "block_id": {"type": "string"},
                    "action": {"type": "string", "enum": ["keep", "replace"]},
                    "replacement": {"type": "string"},
                },
                "required": ["block_id", "action", "replacement"],
            },
        }
    },
    "required": ["results"],
}


def select_candidates(blocks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        block
        for block in blocks
        if block["type"] in {"table_candidate", "equation_candidate"}
        or (
            block["type"] in {"paragraph", "heading"}
            and (
                block.get("text_decision") == "needs_text_adjudication"
                or looks_glyph_encoded(str(block.get("source_text", "")))
            )
        )
    ]


def markdown_fragments(markdown: str) -> dict[str, str]:
    pattern = re.compile(r"<!-- (docling_\S+) type=\S+ bbox=.*? -->\n")
    matches = list(pattern.finditer(markdown))
    fragments = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown)
        value = markdown[match.end():end].strip()
        fragments[match.group(1)] = re.sub(
            r"\n+## Page \d+\s*$",
            "",
            value,
        ).strip()
    return fragments


def page_markdown(markdown: str) -> dict[int, str]:
    matches = list(re.finditer(r"^## Page (\d+)\s*$", markdown, re.MULTILINE))
    pages = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown)
        pages[int(match.group(1))] = markdown[match.end():end].strip()
    return pages


def valid_replacement(block_type: str, replacement: str) -> bool:
    if block_type == "equation_candidate":
        normalized = normalize_replacement(block_type, replacement)
        return normalized.count("$$") == 2 and has_balanced_braces(normalized)
    if block_type == "table_candidate":
        return is_valid_markdown_table(replacement)
    if block_type == "heading":
        return bool(re.fullmatch(r"#{1,6}\s+\S[^\n]*", replacement))
    return bool(replacement.strip()) and "```" not in replacement


def normalize_replacement(block_type: str, replacement: str) -> str:
    normalized = replacement.strip()
    if (
        block_type == "equation_candidate"
        and normalized.startswith(r"\[")
        and normalized.endswith(r"\]")
    ):
        return "$$\n" + normalized[2:-2].strip() + "\n$$"
    return normalized


def image_data_url(data: bytes, media_type: str = "image/png") -> str:
    return f"data:{media_type};base64,{base64.b64encode(data).decode('ascii')}"


def image_file_data_url(path: Path) -> str:
    media_type = mimetypes.guess_type(path.name)[0] or "image/png"
    return image_data_url(path.read_bytes(), media_type)


def render_page(pdf_file: Path, page_number: int) -> str:
    with fitz.open(pdf_file) as pdf:
        pixmap = pdf[page_number - 1].get_pixmap(
            matrix=fitz.Matrix(2, 2),
            alpha=False,
        )
    return image_data_url(pixmap.tobytes("png"))


def render_crop(pdf_file: Path, block: dict[str, Any]) -> str:
    with fitz.open(pdf_file) as pdf:
        page = pdf[int(block["page"]) - 1]
        rect = fitz.Rect(*(float(value) for value in block["bbox"]))
        rect = fitz.Rect(
            max(0, rect.x0 - 4),
            max(0, rect.y0 - 4),
            min(page.rect.width, rect.x1 + 4),
            min(page.rect.height, rect.y1 + 4),
        )
        pixmap = page.get_pixmap(matrix=fitz.Matrix(3, 3), clip=rect, alpha=False)
    return image_data_url(pixmap.tobytes("png"))


def block_image(
    output_dir: Path,
    pdf_file: Path,
    block: dict[str, Any],
) -> str:
    asset = block.get("asset")
    if asset:
        asset_path = output_dir / str(asset)
        if asset_path.exists():
            return image_file_data_url(asset_path)
    return render_crop(pdf_file, block)


def response_text(response: dict[str, Any]) -> str:
    for output in response.get("output", []):
        if output.get("type") != "message":
            continue
        for content in output.get("content", []):
            if content.get("type") == "output_text":
                return str(content.get("text", ""))
    raise ValueError("Responses API output_text가 없습니다.")


def call_page(
    *,
    endpoint: str,
    api_key: str,
    model: str,
    reasoning_effort: str,
    prompt: str,
    payload: dict[str, Any],
    images: list[str],
) -> tuple[dict[str, Any], dict[str, Any]]:
    content: list[dict[str, Any]] = [
        {
            "type": "input_text",
            "text": "INPUT PAYLOAD:\n"
            + json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        }
    ]
    for sequence, image in enumerate(images):
        content.append(
            {
                "type": "input_image",
                "image_url": image,
                "detail": "auto" if sequence == 0 else "original",
            }
        )
    body = {
        "model": model,
        "store": False,
        "reasoning": {"effort": reasoning_effort},
        "input": [
            {"role": "system", "content": prompt},
            {"role": "user", "content": content},
        ],
        "text": {
            "format": {
                "type": "json_schema",
                "name": "document_block_restoration",
                "strict": True,
                "schema": OUTPUT_SCHEMA,
            }
        },
    }
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as raw:
            response = json.loads(raw.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"Responses API HTTP {exc.code}") from exc
    result = json.loads(response_text(response))
    return result, response.get("usage") or {}


def clean_previous_results(
    output_dir: Path,
) -> None:
    layout = output_dir / "layout" / "auto"
    result_dirs = (
        (layout / "evaluations", layout / "recovered_blocks"),
        (layout / "text_evaluations", layout / "text_recovered"),
    )
    for evaluation_dir, recovered_dir in result_dirs:
        for evaluation_file in evaluation_dir.glob("*.json"):
            evaluation_file.unlink()
        for recovered_file in recovered_dir.glob("*.md"):
            recovered_file.unlink()


def save_replacements(
    output_dir: Path,
    blocks: list[dict[str, Any]],
    result: dict[str, Any],
) -> dict[str, int]:
    expected = {block["id"]: block for block in blocks}
    returned = result.get("results") or []
    ids = [item.get("block_id") for item in returned]
    if len(ids) != len(set(ids)) or set(ids) != set(expected):
        raise ValueError("Responses API result ID mismatch")
    counts = {"replace": 0, "keep": 0, "rejected": 0}
    layout = output_dir / "layout" / "auto"
    for item in returned:
        block = expected[item["block_id"]]
        if item["action"] == "keep":
            counts["keep"] += 1
            continue
        replacement = normalize_replacement(
            block["type"],
            str(item["replacement"]),
        )
        if not valid_replacement(block["type"], replacement):
            counts["rejected"] += 1
            continue
        text_block = block["type"] in {"paragraph", "heading"}
        recovered_dir = layout / ("text_recovered" if text_block else "recovered_blocks")
        evaluation_dir = layout / ("text_evaluations" if text_block else "evaluations")
        recovered_dir.mkdir(parents=True, exist_ok=True)
        evaluation_dir.mkdir(parents=True, exist_ok=True)
        (recovered_dir / f"{block['id']}.md").write_text(
            replacement + "\n",
            encoding="utf-8",
        )
        (evaluation_dir / f"{block['id']}.json").write_text(
            json.dumps(
                {
                    "accepted": True,
                    "score": 1.0,
                    "reasons": [],
                    "recovery_source": "openai_selective_repair",
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        counts["replace"] += 1
    return counts


def run(args: argparse.Namespace) -> dict[str, Any]:
    api_key = os.environ.get("DOCUMENT_REPAIR_OPENAI_API_KEY") or os.environ.get(
        "OPENAI_API_KEY"
    )
    if not api_key:
        raise RuntimeError(
            "selective-repair에는 DOCUMENT_REPAIR_OPENAI_API_KEY 또는 "
            "OPENAI_API_KEY가 필요합니다."
        )
    manifest = json.loads(args.manifest_file.read_text(encoding="utf-8"))
    selected = select_candidates(manifest)
    clean_previous_results(args.output_dir)
    markdown = args.detected_markdown.read_text(encoding="utf-8")
    fragments = markdown_fragments(markdown)
    pages = page_markdown(markdown)
    prompt = PROMPT_FILE.read_text(encoding="utf-8")
    grouped = {
        page: [block for block in selected if block["page"] == page]
        for page in sorted({int(block["page"]) for block in selected})
    }

    def process(page: int) -> dict[str, Any]:
        blocks = grouped[page]
        payload_blocks = [
            {
                "block_id": block["id"],
                "page": block["page"],
                "order": block["order"],
                "type": block["type"],
                "bbox": block["bbox"],
                "crop_sequence": sequence,
                "current_markdown": fragments.get(
                    block["id"],
                    str(block.get("markdown") or block.get("source_text") or ""),
                ),
            }
            for sequence, block in enumerate(blocks, 1)
        ]
        images = [render_page(args.pdf_file, page)]
        images.extend(block_image(args.output_dir, args.pdf_file, block) for block in blocks)
        result, usage = call_page(
            endpoint=args.endpoint,
            api_key=api_key,
            model=args.model,
            reasoning_effort=args.reasoning_effort,
            prompt=prompt,
            payload={
                "page_context": pages.get(page, ""),
                "blocks": payload_blocks,
            },
            images=images,
        )
        counts = save_replacements(args.output_dir, blocks, result)
        return {"page": page, "blocks": len(blocks), **counts, "usage": usage}

    with ThreadPoolExecutor(max_workers=args.max_workers) as executor:
        page_results = list(executor.map(process, grouped))
    summary = {
        "model": args.model,
        "reasoning_effort": args.reasoning_effort,
        "calls": len(page_results),
        "blocks": len(selected),
        "pages": page_results,
    }
    summary_file = (
        args.output_dir / "final" / "selective_repair_summary.json"
    )
    summary_file.parent.mkdir(parents=True, exist_ok=True)
    summary_file.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf-file", type=Path, required=True)
    parser.add_argument("--manifest-file", type=Path, required=True)
    parser.add_argument("--detected-markdown", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--reasoning-effort", required=True)
    parser.add_argument("--max-workers", type=int, required=True)
    args = parser.parse_args()
    args.pdf_file = args.pdf_file.resolve()
    args.manifest_file = args.manifest_file.resolve()
    args.detected_markdown = args.detected_markdown.resolve()
    args.output_dir = args.output_dir.resolve()
    print(json.dumps(run(args), ensure_ascii=False))


if __name__ == "__main__":
    main()
