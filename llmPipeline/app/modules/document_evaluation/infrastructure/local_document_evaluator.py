from __future__ import annotations

import base64
import difflib
import http.client
import json
import re
import socket
import time
import unicodedata
import urllib.error
import urllib.request
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from app.modules.document_evaluation.application.models import (
    LocalDocumentEvaluationCommand,
)
from app.modules.document_restoration.domain.text_quality import (
    language_score,
    looks_glyph_encoded,
)


DEFAULT_ENDPOINT = "http://127.0.0.1:11434/v1/chat/completions"
BLOCK_PATTERN = re.compile(
    r"<!--\s+(?P<id>\S+)\s+type=(?P<type>\S+)\s+"
    r"bbox=\[(?P<bbox>[^]]+)](?:\s+[^>]*)?-->"
)
PAGE_PATTERN = re.compile(r"_p(?P<page>\d+)_")
EVALUATION_FLOW = "local_first_v8"
TABLE_LAYOUT_SIMILARITY_THRESHOLD = 0.98
TABLE_LAYOUT_QUALITY_TOLERANCE = 0.1


@dataclass(frozen=True)
class Block:
    id: str
    type: str
    page: int
    bbox: tuple[float, float, float, float]
    markdown: str


@dataclass(frozen=True)
class EvaluationPlan:
    table_evidence: dict[str, tuple[str | None, int | None]]
    local_blocks: list[Block]
    fallback_blocks: list[Block]
    fallback_chunks: list[list[Block]]
    batches: list[tuple[str, list[Block]]]
    selected_batches: list[tuple[str, list[Block]]]


def parse_blocks(markdown: str) -> list[Block]:
    matches = list(BLOCK_PATTERN.finditer(markdown))
    blocks = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown)
        content = markdown[match.end() : end].strip()
        content = re.sub(r"\n+## Page \d+\s*$", "", content).strip()
        page_match = PAGE_PATTERN.search(match.group("id"))
        bbox = tuple(float(value.strip()) for value in match.group("bbox").split(","))
        if page_match is None or len(bbox) != 4:
            raise ValueError(f"잘못된 block metadata: {match.group(0)}")
        blocks.append(
            Block(
                id=match.group("id"),
                type=match.group("type"),
                page=int(page_match.group("page")),
                bbox=bbox,
                markdown=content,
            )
        )
    return blocks


def make_chunks(blocks: list[Block], max_blocks: int, max_chars: int) -> list[list[Block]]:
    chunks: list[list[Block]] = []
    current: list[Block] = []
    current_chars = 0
    for block in blocks:
        size = len(block.markdown)
        if current and (len(current) >= max_blocks or current_chars + size > max_chars):
            chunks.append(current)
            current = []
            current_chars = 0
        current.append(block)
        current_chars += size
    if current:
        chunks.append(current)
    return chunks


def extract_json(text: str) -> dict[str, Any]:
    stripped = text.strip()
    if stripped.startswith("```"):
        lines = stripped.splitlines()
        stripped = "\n".join(lines[1:-1]).strip()
    start = stripped.find("{")
    end = stripped.rfind("}")
    if start < 0 or end < start:
        raise ValueError("모델 응답에 JSON object가 없음")
    value = json.loads(stripped[start : end + 1])
    if not isinstance(value, dict):
        raise ValueError("모델 응답이 JSON object가 아님")
    return value


def call_model(
    endpoint: str,
    model: str,
    prompt: str,
    image: bytes | None = None,
    timeout: int = 600,
) -> dict[str, Any]:
    content: str | list[dict[str, Any]] = prompt
    if image is not None:
        encoded = base64.b64encode(image).decode("ascii")
        content = [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{encoded}"}},
        ]
    payload = {
        "model": model,
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [{"role": "user", "content": content}],
    }
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer ollama"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            data = json.loads(response.read().decode("utf-8"))
    except (
        urllib.error.URLError,
        socket.timeout,
        TimeoutError,
        ConnectionError,
        http.client.HTTPException,
    ) as exc:
        raise RuntimeError(f"모델 요청 실패: {exc}") from exc
    try:
        return extract_json(data["choices"][0]["message"]["content"])
    except (KeyError, TypeError, ValueError) as exc:
        raise RuntimeError(f"모델 JSON 응답 파싱 실패: {exc}") from exc


def block_payload(block: Block) -> dict[str, Any]:
    return {
        "block_id": block.id,
        "type": block.type,
        "page": block.page,
        "markdown": block.markdown,
    }


def has_suspicious_text_pattern(text: str) -> bool:
    if "�" in text:
        return True
    if any(
        not char.isspace() and unicodedata.category(char) in {"Cc", "Cs", "Co"}
        for char in text
    ):
        return True
    if re.search(r"([A-Za-z])\1{3,}", text):
        return True
    for token in re.findall(r"[A-Za-z]+", text):
        uppercase_count = sum(char.isupper() for char in token)
        lowercase_count = sum(char.islower() for char in token)
        case_transitions = sum(
            left.isupper() != right.isupper()
            for left, right in zip(token, token[1:])
        )
        if (
            len(token) >= 16
            and uppercase_count >= 6
            and lowercase_count >= 4
            and case_transitions >= 4
        ):
            return True
    return looks_glyph_encoded(text) and language_score(text) < 0.5


def needs_local_review(block: Block) -> bool:
    if block.type in {"paragraph", "heading"}:
        return has_suspicious_text_pattern(block.markdown)
    if block.type in {"table", "table_candidate"}:
        return not is_valid_markdown_table(block.markdown)
    return False


def normalized_table_text(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", text.lower())


def table_header_has_suspicious_symbol(markdown: str) -> bool:
    lines = markdown.splitlines()
    if not lines:
        return False
    return any(
        ord(char) > 127 and unicodedata.category(char)[0] in {"P", "S"}
        for char in lines[0]
    )


def table_layout_disagrees(block: Block, restored_table: str | None) -> bool:
    if not restored_table or not is_valid_markdown_table(restored_table):
        return False
    similarity = difflib.SequenceMatcher(
        None,
        normalized_table_text(block.markdown),
        normalized_table_text(restored_table),
    ).ratio()
    return (
        similarity < TABLE_LAYOUT_SIMILARITY_THRESHOLD
        and language_score(restored_table)
        >= language_score(block.markdown) - TABLE_LAYOUT_QUALITY_TOLERANCE
    )


def has_strong_table_layout_evidence(
    block: Block,
    restored_table: str | None,
    detected_column_count: int | None,
) -> bool:
    if detected_column_count is None or not is_valid_markdown_table(block.markdown):
        return False
    return table_layout_disagrees(block, restored_table)


def needs_text_fallback(
    block: Block,
    restored_table: str | None = None,
    detected_column_count: int | None = None,
) -> bool:
    if block.type in {"table", "table_candidate"}:
        if not is_valid_markdown_table(block.markdown):
            return False
        lines = block.markdown.splitlines()
        header = lines[0] if lines else ""
        has_generic_header = (
            detected_column_count is not None
            and bool(re.search(r"\bColumn\s+\d+\b", header, flags=re.IGNORECASE))
        )
        has_layout_disagreement = table_layout_disagrees(block, restored_table)
        return (
            has_generic_header
            or table_header_has_suspicious_symbol(block.markdown)
            or has_layout_disagreement
        )
    return False


def select_requests(initial: dict[str, Any], chunk: list[Block]) -> list[dict[str, str]]:
    by_id = {block.id: block for block in chunk}
    requests = []
    selected_ids = set()
    for request in initial.get("requests", []):
        block_id = str(request.get("block_id", ""))
        reason = str(request.get("reason", ""))
        block = by_id.get(block_id)
        if block is None or block_id in selected_ids:
            continue
        if reason.startswith("[broken_text]"):
            if block.type not in {"paragraph", "heading"}:
                continue
        elif reason.startswith("[table_structure]"):
            if block.type not in {"table", "table_candidate"}:
                continue
        else:
            continue
        requests.append({"block_id": block_id, "reason": reason})
        selected_ids.add(block_id)

    for block in chunk:
        if (
            block.id not in selected_ids
            and block.type in {"paragraph", "heading"}
            and needs_local_review(block)
        ):
            requests.append(
                {
                    "block_id": block.id,
                    "reason": "[broken_text] 문서 독립적 문자 품질 이상을 감지함",
                }
            )
            selected_ids.add(block.id)
        if (
            block.id not in selected_ids
            and block.type in {"table", "table_candidate"}
            and not is_valid_markdown_table(block.markdown)
        ):
            requests.append(
                {
                    "block_id": block.id,
                    "reason": "[table_structure] 로컬 표 구조 검사에서 유효하지 않은 Markdown 표를 감지함",
                }
            )
            selected_ids.add(block.id)
    return requests


def evaluation_prompt(chunk: list[Block]) -> str:
    return """Inspect this chunk of blocks from a fully assembled PDF-to-Markdown result.
Select only blocks that need a source crop for one of these two reasons:

1. `[broken_text]`: visible character-level OCR or encoding corruption such as mojibake, glyph debris,
   inconsistent mixed scripts, meaningless symbol runs, or a word split into character fragments.
   Quote the exact suspicious substring from that block's Markdown in the Korean reason. If you cannot
   quote exact evidence from the input, do not request it.
2. `[table_structure]`: a `table` or `table_candidate` with broken Markdown delimiters, inconsistent cell
   counts, generic `Column N` headers caused by flattening, words from one logical field spread across many
   cells, shifted headers, duplicated rows, or a caption/footnote embedded in the table. State the exact
   observed row/column defect in the Korean reason.

Do not evaluate duplicates, continuity, grammar, punctuation, writing style, terminology, equations, or
factual accuracy in this pass. A technical term, proper name, abbreviation, variable, unit, citation,
hyphenated word, or sentence ending at a block boundary is not broken text. Do not invent evidence.
Optimize for precision: omit ambiguous blocks and return an empty list when there is no exact evidence.
Write each reason in Hangul Korean, not Chinese or English, after the category prefix.

JSON format:
{"requests":[{"block_id":"...","reason":"..."}]}

Blocks:
""" + json.dumps([block_payload(block) for block in chunk], ensure_ascii=False)


def render_crop(pdf_file: Path, block: Block, padding: float) -> bytes:
    import fitz

    document = fitz.open(pdf_file)
    try:
        page = document[block.page - 1]
        x0, y0, x1, y1 = block.bbox
        clip = fitz.Rect(x0 - padding, y0 - padding, x1 + padding, y1 + padding) & page.rect
        return page.get_pixmap(matrix=fitz.Matrix(2, 2), clip=clip, alpha=False).tobytes("png")
    finally:
        document.close()


def extract_positioned_words(pdf_file: Path, block: Block) -> list[dict[str, Any]]:
    import fitz

    document = fitz.open(pdf_file)
    try:
        page = document[block.page - 1]
        clip = fitz.Rect(*block.bbox) & page.rect
        return [
            {
                "x": round(word[0] - clip.x0, 1),
                "y": round(word[1] - clip.y0, 1),
                "text": word[4],
            }
            for word in page.get_text("words", clip=clip)
        ]
    finally:
        document.close()


def detect_table_column_count(pdf_file: Path, block: Block) -> int | None:
    import fitz

    document = fitz.open(pdf_file)
    try:
        page = document[block.page - 1]
        clip = fitz.Rect(*block.bbox) & page.rect
        tables = page.find_tables(clip=clip, strategy="text").tables
        if len(tables) != 1 or tables[0].col_count < 1:
            return None
        return tables[0].col_count
    finally:
        document.close()


def assemble_markdown_table(cell_rows: list[list[str]]) -> str | None:
    populated = [(index, row) for index, row in enumerate(cell_rows) if any(row)]
    if len(populated) < 3:
        return None
    column_count = len(cell_rows[0])
    if column_count < 1 or any(len(row) != column_count for row in cell_rows):
        return None

    occupancies = Counter(sum(bool(cell) for cell in row) for _, row in populated)
    body_occupancy, frequency = max(occupancies.items(), key=lambda item: (item[1], item[0]))
    if frequency < 2:
        return None
    body_start = next(
        (
            index
            for index, row in populated
            if index > 0 and sum(bool(cell) for cell in row) == body_occupancy
        ),
        -1,
    )
    if body_start < 1:
        return None
    header_rows = cell_rows[:body_start]
    body_rows = [row for row in cell_rows[body_start:] if any(row)]
    if not header_rows or not body_rows:
        return None

    leaf_index = max(
        range(len(header_rows)),
        key=lambda index: sum(bool(cell) for cell in header_rows[index]),
    )
    headers = _hierarchical_table_headers(header_rows, leaf_index, column_count)
    if headers is None:
        return None

    lines = [_markdown_table_row(headers), _markdown_table_row(["---"] * column_count)]
    lines.extend(_markdown_table_row(row) for row in body_rows)
    return "\n".join(lines)


def _hierarchical_table_headers(
    header_rows: list[list[str]],
    leaf_index: int,
    column_count: int,
) -> list[str] | None:
    leaf_row = header_rows[leaf_index]
    leaf_columns = [index for index, cell in enumerate(leaf_row) if cell]
    headers = ["" for _ in range(column_count)]

    for column in range(column_count):
        if leaf_row[column]:
            continue
        values = [row[column] for row in header_rows if row[column]]
        headers[column] = " / ".join(dict.fromkeys(values))

    parent_paths: dict[int, list[str]] = {column: [] for column in leaf_columns}
    for row_index, row in enumerate(header_rows):
        if row_index == leaf_index:
            continue
        groups = []
        start = None
        for column, cell in enumerate(row + [""]):
            if cell and start is None:
                start = column
            if not cell and start is not None:
                end = column - 1
                if any(start <= leaf_column <= end for leaf_column in leaf_columns):
                    groups.append((start, end, " ".join(row[start : end + 1])))
                start = None
        if not groups:
            continue
        centers = [((start + end) / 2, text) for start, end, text in groups]
        for column in leaf_columns:
            parent = min(centers, key=lambda item: abs(item[0] - column))[1]
            if parent and parent not in parent_paths[column]:
                parent_paths[column].append(parent)

    for column in leaf_columns:
        headers[column] = " / ".join(parent_paths[column] + [leaf_row[column]])
    if any(not header for header in headers):
        return None
    return headers


def _markdown_table_row(cells: list[str]) -> str:
    escaped = [cell.replace("|", "\\|").replace("\n", " ").strip() for cell in cells]
    return "| " + " | ".join(escaped) + " |"


def restore_table_from_text_layout(pdf_file: Path, block: Block) -> str | None:
    import fitz

    document = fitz.open(pdf_file)
    try:
        page = document[block.page - 1]
        clip = fitz.Rect(*block.bbox) & page.rect
        tables = page.find_tables(clip=clip, strategy="text").tables
        if len(tables) != 1:
            return None
        table = tables[0]
        cell_words: list[list[list[tuple[float, float, str]]]] = [
            [[] for _ in range(table.col_count)] for _ in range(table.row_count)
        ]
        for word in page.get_text("words", clip=clip):
            center_x = (word[0] + word[2]) / 2
            center_y = (word[1] + word[3]) / 2
            for row_index, row in enumerate(table.rows):
                for column_index, cell in enumerate(row.cells):
                    if cell is None:
                        continue
                    x0, y0, x1, y1 = cell
                    if x0 <= center_x <= x1 and y0 <= center_y <= y1:
                        cell_words[row_index][column_index].append((word[1], word[0], word[4]))
                        break
                else:
                    continue
                break
        cell_rows = [
            [
                " ".join(word[2] for word in sorted(words))
                for words in row
            ]
            for row in cell_words
        ]
        return assemble_markdown_table(cell_rows)
    finally:
        document.close()


def vision_prompt(
    block: Block,
    reason: str,
    attempt: int,
    positioned_words: list[dict[str, Any]] | None = None,
    detected_column_count: int | None = None,
) -> str:
    type_instruction = """For prose or a heading, read and transcribe visible characters independently from the image.
The known-corrupted OCR text is deliberately omitted: do not reconstruct or echo it.
Put the exact image text that you cite in the reason into `transcription`. Preserve visible wording and
punctuation exactly; never improve grammar, style, terminology, or abbreviation formatting. If the crop is
fully readable, return `corrected`. If a sentence is cut at the crop boundary, transcribe only the visible
portion and mention the cutoff in the reason."""
    review_context = "Review request category: [broken_text]\nKnown-corrupted current Markdown: omitted"
    if block.type in {"table", "table_candidate"}:
        type_instruction = """For a table, inspect the image independently and reconstruct every visible
header and cell as a Markdown table
while preserving row-to-column relationships. Do not move merged, empty, or multiline cells into another
column. Preserve subscripts, superscripts, Greek letters, and unit symbols; use inline LaTeX when needed.
Expand a merged or hierarchical header into one explicit header per data column. The header and every data
row must have exactly the same number of columns.
Scan the complete table from its left edge to its right edge. For a hierarchical header, follow each
vertical data column to its bottom-most header and include its complete header path in `source_columns`.
Use those exact strings as the Markdown header cells, with one pipe-delimited column per entry. Do not stop
after the first header group, merge distinct visible columns, or drop the rightmost data column.
Use positioned source words as supplementary evidence for row and column boundaries; words at distinct
horizontal positions in the same row must remain in their corresponding distinct cells. The image remains
authoritative when the text layer and visible source disagree.
The known OCR table is deliberately omitted. Read every cell independently from the image. Compare every
parameter symbol with the image. Return only the Markdown table in `transcription`, without a failure label,
note, or surrounding prose. Never guess a cropped or unreadable row or cell; identify it in the reason instead."""
        review_context = "Review request category: [table_structure]\nKnown current Markdown: omitted"
        if positioned_words:
            review_context += (
                "\nPositioned words extracted from the same source crop "
                "(coordinates are relative to the crop):\n"
                + json.dumps(positioned_words, ensure_ascii=False)
            )
        if detected_column_count is not None:
            review_context += (
                "\nA text-layout detector found "
                f"{detected_column_count} vertical columns in this crop. "
                "Use the image and positioned words to name and fill each column."
            )

    retry_instruction = ""
    if attempt > 1 and block.type in {"table", "table_candidate"}:
        retry_instruction = """A previous transcription was rejected because its declared source-column
structure did not match its Markdown table. Reinspect the image and return a consistent result."""

    return f"""Recover the selected block independently from this crop of the source PDF.
Use only content actually visible in the image. Do not summarize, normalize, or supplement it.
Return `corrected` with an independent transcription when the crop is fully readable. Return `uncertain`
or `unreadable` when the crop does not provide enough evidence. If content is cut at a crop boundary, return
`uncertain`; never replace a block with a visible subset.
{type_instruction}
{retry_instruction}
Write the reason in Korean. This is review attempt {attempt}.

JSON format:
{{"status":"match|corrected|uncertain|unreadable","source_columns":["ordered leaf-header path"],"transcription":"image-grounded Markdown or an empty string","reason":"..."}}
For prose or headings, return an empty `source_columns` array.

block_id: {block.id}
type: {block.type}
{review_context}
"""


def is_valid_markdown_table(markdown: str) -> bool:
    lines = [line.strip() for line in markdown.strip().splitlines() if line.strip()]
    if len(lines) < 3 or any(not line.startswith("|") or not line.endswith("|") for line in lines):
        return False
    if len({line.count("|") for line in lines}) != 1:
        return False
    delimiter_cells = [cell.strip() for cell in lines[1].strip("|").split("|")]
    return bool(delimiter_cells) and all(
        re.fullmatch(r":?-{3,}:?", cell) for cell in delimiter_cells
    )


def is_valid_table_result(
    result: dict[str, Any],
    detected_column_count: int | None = None,
) -> bool:
    markdown = str(result.get("transcription", ""))
    if not is_valid_markdown_table(markdown):
        return False
    source_columns = result.get("source_columns")
    if not isinstance(source_columns, list) or not source_columns:
        return False
    if not all(isinstance(column, str) and column.strip() for column in source_columns):
        return False
    if detected_column_count is not None and len(source_columns) != detected_column_count:
        return False
    header = [cell.strip() for cell in markdown.strip().splitlines()[0].strip("|").split("|")]
    return header == [column.strip() for column in source_columns]


def review_with_vision(
    block: Block,
    reason: str,
    max_attempts: int,
    render: Callable[[Block, float], bytes],
    ask: Callable[[str, bytes], dict[str, Any]],
    positioned_words: list[dict[str, Any]] | None = None,
    detected_column_count: int | None = None,
) -> dict[str, Any]:
    attempts = []
    for attempt in range(1, max_attempts + 1):
        try:
            result = ask(
                vision_prompt(
                    block,
                    reason,
                    attempt,
                    positioned_words,
                    detected_column_count,
                ),
                render(block, 4.0 * attempt),
            )
        except RuntimeError as exc:
            result = {
                "status": "uncertain",
                "transcription": "",
                "reason": f"Vision tool 오류: {exc}",
            }
        if (
            block.type in {"table", "table_candidate"}
            and result.get("status") == "corrected"
            and not is_valid_table_result(result, detected_column_count)
        ):
            result = {
                "status": "uncertain",
                "transcription": "",
                "reason": "Vision 표 결과가 순수하고 열 수가 일관된 Markdown 표가 아님",
            }
        result["attempt"] = attempt
        attempts.append(result)
        if result.get("status") not in {"uncertain"}:
            break
        if attempt < max_attempts:
            time.sleep(5)
    return {"block_id": block.id, "request_reason": reason, "attempts": attempts, "result": attempts[-1]}


def call_text_with_retries(ask: Callable[[], dict[str, Any]], fallback_key: str) -> dict[str, Any]:
    errors = []
    for attempt in range(1, 4):
        try:
            return ask()
        except RuntimeError as exc:
            errors.append(str(exc))
            if attempt < 3:
                time.sleep(5)
    return {fallback_key: [], "model_errors": errors}


def correction_map(report: dict[str, Any]) -> dict[str, str]:
    corrections = {}
    for chunk in report["chunks"]:
        for decision in chunk["final_evaluation"].get("decisions", []):
            block_id = str(decision.get("block_id", ""))
            markdown = str(decision.get("suggested_markdown", "")).strip()
            if decision.get("decision") == "suggest_correction" and block_id and markdown:
                corrections[block_id] = markdown
    return corrections


def complete_decisions(
    final: dict[str, Any],
    vision_results: list[dict[str, Any]],
) -> dict[str, Any]:
    decisions = list(final.get("decisions", []))
    if vision_results:
        by_id = {str(decision.get("block_id", "")): decision for decision in decisions}
        completed = []
        for vision in vision_results:
            block_id = vision["block_id"]
            result = vision["result"]
            status = result.get("status")
            transcription = str(result.get("transcription", "")).strip()
            if status == "match":
                decision = "keep"
                suggested_markdown = ""
            elif status == "corrected" and transcription:
                decision = "suggest_correction"
                suggested_markdown = transcription
            else:
                decision = "unresolved"
                suggested_markdown = ""
            model_decision = by_id.get(block_id, {})
            completed.append(
                {
                    "block_id": block_id,
                    "decision": decision,
                    "reason": str(model_decision.get("reason", "")).strip()
                    or f"Vision {status} 판정을 사용함",
                    "suggested_markdown": suggested_markdown,
                }
            )
        return {**final, "decisions": completed}

    for decision in decisions:
        if decision.get("decision") == "match":
            decision["decision"] = "keep"
        elif decision.get("decision") == "corrected":
            decision["decision"] = "suggest_correction"
    decided_ids = {str(decision.get("block_id", "")) for decision in decisions}
    for vision in vision_results:
        block_id = vision["block_id"]
        if block_id in decided_ids:
            continue
        result = vision["result"]
        status = result.get("status")
        if status == "match":
            decision = "keep"
        elif status == "corrected" and str(result.get("transcription", "")).strip():
            decision = "suggest_correction"
        else:
            decision = "unresolved"
        decisions.append(
            {
                "block_id": block_id,
                "decision": decision,
                "reason": f"최종 evaluator 누락으로 Vision {status} 판정을 사용함",
                "suggested_markdown": str(result.get("transcription", "")).strip(),
            }
        )
    return {**final, "decisions": decisions}


def apply_corrections(markdown: str, corrections: dict[str, str]) -> str:
    matches = list(BLOCK_PATTERN.finditer(markdown))
    for index in range(len(matches) - 1, -1, -1):
        match = matches[index]
        block_id = match.group("id")
        if block_id not in corrections:
            continue
        end = matches[index + 1].start() if index + 1 < len(matches) else len(markdown)
        current = markdown[match.end() : end]
        page_heading = re.search(r"(\n+## Page \d+\s*\n*)$", current)
        suffix = page_heading.group(1) if page_heading else "\n\n"
        replacement = "\n" + corrections[block_id].strip() + suffix
        markdown = markdown[: match.end()] + replacement + markdown[end:]
    return markdown


def read_restoration_elapsed_seconds(markdown_file: Path) -> float | None:
    suffix = ".restored.md"
    if not markdown_file.name.endswith(suffix):
        return None
    document_slug = markdown_file.name[: -len(suffix)]
    timing_file = markdown_file.with_name(f"{document_slug}.pipeline_timing.json")
    if not timing_file.exists():
        return None
    payload = json.loads(timing_file.read_text(encoding="utf-8"))
    elapsed = payload.get("total_elapsed_seconds")
    return float(elapsed) if isinstance(elapsed, (int, float)) else None


def record_evaluation_timing(
    report: dict[str, Any],
    evaluation_elapsed_seconds: float,
    restoration_elapsed_seconds: float | None,
) -> None:
    previous_total = float(report.get("evaluation_elapsed_seconds_total", 0.0))
    evaluation_total = previous_total + evaluation_elapsed_seconds
    report["evaluation_elapsed_seconds_last_run"] = evaluation_elapsed_seconds
    report["evaluation_elapsed_seconds_total"] = evaluation_total
    if restoration_elapsed_seconds is None:
        return
    report["restoration_elapsed_seconds"] = restoration_elapsed_seconds
    report["pdf_to_evaluated_processing_seconds"] = restoration_elapsed_seconds + evaluation_total


def markdown_report(report: dict[str, Any]) -> str:
    vision_results = [result for chunk in report["chunks"] for result in chunk["vision_results"]]
    decisions = [
        decision
        for chunk in report["chunks"]
        for decision in chunk["final_evaluation"].get("decisions", [])
    ]
    correction_count = sum(decision.get("decision") == "suggest_correction" for decision in decisions)
    unresolved_count = sum(decision.get("decision") == "unresolved" for decision in decisions)
    lines = [
        "# 조립 Markdown 하이브리드 평가 리포트",
        "",
        "## 요약",
        "",
        f"- 입력 Markdown: `{report['markdown_file']}`",
        f"- 원본 PDF: `{report['pdf_file']}`",
        f"- 전체 block: {report['block_count']}",
        f"- 평가 chunk: {report['evaluated_chunk_count']} / {report['chunk_count']}",
        f"- Vision 요청 block: {len(vision_results)}",
        f"- 수정 반영 block: {correction_count}",
        f"- 미확정 block: {unresolved_count}",
    ]
    if "evaluation_elapsed_seconds_last_run" in report:
        lines.append(
            f"- Evaluator 이번 실행: {report['evaluation_elapsed_seconds_last_run']:.2f}초"
        )
        lines.append(f"- Evaluator 누적 실행: {report['evaluation_elapsed_seconds_total']:.2f}초")
    if "pdf_to_evaluated_processing_seconds" in report:
        lines.append(
            "- PDF 복원 + evaluator 누적 처리: "
            f"{report['pdf_to_evaluated_processing_seconds']:.2f}초"
        )
    lines.extend(["", "## 판정", ""])
    if not decisions:
        lines.append("- Vision 재검토가 필요한 block이 없었다.")
    for decision in decisions:
        block_id = str(decision.get("block_id", ""))
        verdict = str(decision.get("decision", "unresolved"))
        reason = str(decision.get("reason", "")).strip()
        lines.append(f"### `{block_id}` - {verdict}")
        lines.extend(["", reason or "판정 이유 없음", ""])
        suggested = str(decision.get("suggested_markdown", "")).strip()
        if verdict == "suggest_correction" and suggested:
            lines.extend(["```markdown", suggested, "```", ""])
    return "\n".join(lines).rstrip() + "\n"


def write_artifacts(args: LocalDocumentEvaluationCommand, report: dict[str, Any]) -> None:
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.output_markdown_file:
        source = args.markdown_file.read_text(encoding="utf-8")
        args.output_markdown_file.parent.mkdir(parents=True, exist_ok=True)
        args.output_markdown_file.write_text(
            apply_corrections(source, correction_map(report)),
            encoding="utf-8",
        )
    if args.output_report_file:
        args.output_report_file.parent.mkdir(parents=True, exist_ok=True)
        args.output_report_file.write_text(markdown_report(report), encoding="utf-8")


def build_evaluation_plan(
    blocks: list[Block],
    args: LocalDocumentEvaluationCommand,
) -> EvaluationPlan:
    table_evidence: dict[str, tuple[str | None, int | None]] = {}
    for block in blocks:
        if block.type not in {"table", "table_candidate"}:
            continue
        table_evidence[block.id] = (
            restore_table_from_text_layout(args.pdf_file, block),
            detect_table_column_count(args.pdf_file, block),
        )
    local_blocks = []
    fallback_blocks = []
    for block in blocks:
        restored_table, detected_columns = table_evidence.get(block.id, (None, None))
        needs_fallback = needs_text_fallback(block, restored_table, detected_columns)
        if needs_local_review(block) or has_strong_table_layout_evidence(
            block, restored_table, detected_columns
        ) or (block.type in {"table", "table_candidate"} and needs_fallback):
            local_blocks.append(block)
            continue
        if needs_fallback:
            fallback_blocks.append(block)
    fallback_chunks = make_chunks(fallback_blocks, args.max_blocks, args.max_chars)
    batches = []
    if local_blocks:
        batches.append(("local", local_blocks))
    batches.extend(("text_fallback", chunk) for chunk in fallback_chunks)
    selected_batches = batches[: args.max_chunks] if args.max_chunks else batches
    return EvaluationPlan(
        table_evidence=table_evidence,
        local_blocks=local_blocks,
        fallback_blocks=fallback_blocks,
        fallback_chunks=fallback_chunks,
        batches=batches,
        selected_batches=selected_batches,
    )


def evaluate(args: LocalDocumentEvaluationCommand) -> dict[str, Any]:
    blocks = parse_blocks(args.markdown_file.read_text(encoding="utf-8"))
    plan = build_evaluation_plan(blocks, args)
    table_evidence = plan.table_evidence
    selected_batches = plan.selected_batches
    if args.resume and args.output_file.exists() and json.loads(
        args.output_file.read_text(encoding="utf-8")
    ).get("evaluation_flow") == EVALUATION_FLOW:
        report = json.loads(args.output_file.read_text(encoding="utf-8"))
        first_failed = next(
            (
                index
                for index, chunk in enumerate(report["chunks"])
                if chunk["initial_evaluation"].get("model_errors")
                or chunk["final_evaluation"].get("model_errors")
            ),
            len(report["chunks"]),
        )
        report["chunks"] = report["chunks"][:first_failed]
        for chunk in report["chunks"]:
            chunk["final_evaluation"] = complete_decisions(
                chunk["final_evaluation"], chunk["vision_results"]
            )
    else:
        report = {
            "markdown_file": str(args.markdown_file),
            "pdf_file": str(args.pdf_file),
            "block_count": len(blocks),
            "evaluation_flow": EVALUATION_FLOW,
            "local_candidate_count": len(plan.local_blocks),
            "fallback_candidate_count": len(plan.fallback_blocks),
            "text_evaluator_call_count": len(plan.fallback_chunks),
            "chunk_count": len(plan.batches),
            "evaluated_chunk_count": len(selected_batches),
            "chunks": [],
        }
    if args.dry_run:
        report["chunks"] = [
            {"selection": selection, "block_ids": [block.id for block in chunk]}
            for selection, chunk in selected_batches
        ]
        return report

    remaining_vision = args.max_vision_requests or None
    completed_chunks = len(report["chunks"])
    for index, (selection, chunk) in enumerate(
        selected_batches[completed_chunks:], start=completed_chunks + 1
    ):
        initial = (
            call_text_with_retries(
                lambda: call_model(args.endpoint, args.evaluator_model, evaluation_prompt(chunk)),
                "requests",
            )
            if selection == "text_fallback"
            else {
                "requests": [
                    {
                        "block_id": block.id,
                        "reason": (
                            "[table_structure] 문서 독립적 표 구조 이상을 감지함"
                            if block.type in {"table", "table_candidate"}
                            else "[broken_text] 문서 독립적 문자 품질 이상을 감지함"
                        ),
                    }
                    for block in chunk
                ]
            }
        )
        by_id = {block.id: block for block in chunk}
        requests = select_requests(initial, chunk)
        if remaining_vision is not None:
            requests = requests[:remaining_vision]
            remaining_vision -= len(requests)

        vision_results = []
        for request in requests:
            block = by_id[request["block_id"]]
            restored_table, detected_columns = table_evidence.get(block.id, (None, None))
            if block.type in {"table", "table_candidate"} and block.id not in table_evidence:
                restored_table = restore_table_from_text_layout(args.pdf_file, block)
                detected_columns = detect_table_column_count(args.pdf_file, block)
            if restored_table and is_valid_markdown_table(restored_table):
                source_columns = [
                    cell.strip()
                    for cell in restored_table.splitlines()[0].strip("|").split("|")
                ]
                result = {
                    "status": "corrected",
                    "source_columns": source_columns,
                    "transcription": restored_table,
                    "reason": "PDF text-layout의 cell 경계와 원본 word bbox를 이용해 표를 재조립함",
                    "attempt": 1,
                    "method": "text_layout",
                }
                vision_results.append(
                    {
                        "block_id": block.id,
                        "request_reason": request["reason"],
                        "attempts": [result],
                        "result": result,
                    }
                )
                continue
            vision_results.append(
                review_with_vision(
                    block,
                    request["reason"],
                    args.max_vision_attempts,
                    lambda target, padding: render_crop(args.pdf_file, target, padding),
                    lambda prompt, image: call_model(args.endpoint, args.vision_model, prompt, image),
                    extract_positioned_words(args.pdf_file, block)
                    if block.type in {"table", "table_candidate"}
                    else None,
                    detected_columns,
                )
            )
        final = {"decisions": []}
        final = complete_decisions(final, vision_results)
        report["chunks"].append(
            {
                "index": index,
                "selection": selection,
                "block_ids": list(by_id),
                "initial_evaluation": initial,
                "vision_results": vision_results,
                "final_evaluation": final,
            }
        )
        write_artifacts(args, report)
    return report
