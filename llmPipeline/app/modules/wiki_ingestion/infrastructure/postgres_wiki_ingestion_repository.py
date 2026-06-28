from __future__ import annotations

import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Json

from app.modules.wiki_ingestion.infrastructure.object_storage import write_text_object


SOURCE_RELATED_THRESHOLD = 0.75


def database_url() -> str:
    url = os.environ.get("DATABASE_URL") or os.environ.get("POSTGRES_DSN")
    if not url:
        raise RuntimeError("Set DATABASE_URL or POSTGRES_DSN before using PostgreSQL-backed APIs")
    return url


def connect() -> psycopg.Connection:
    return psycopg.connect(database_url(), row_factory=dict_row)


def init_db() -> None:
    """Create pipeline-owned tables and a Spring-compatible documents table if absent."""
    with connect() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS documents (
                id TEXT PRIMARY KEY,
                filename TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                byte_size BIGINT NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'processing',
                source_uri TEXT NOT NULL,
                extracted_text_uri TEXT,
                content_hash TEXT UNIQUE,
                uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                processed_at TIMESTAMPTZ,
                error_message TEXT
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS wiki_pages (
                id TEXT PRIMARY KEY,
                page_type TEXT NOT NULL,
                title TEXT NOT NULL,
                slug TEXT NOT NULL,
                summary TEXT NOT NULL DEFAULT '',
                markdown_uri TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'active',
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS document_wiki_links (
                document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                wiki_page_id TEXT NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
                relation_type TEXT NOT NULL,
                confidence DOUBLE PRECISION,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (document_id, wiki_page_id, relation_type)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS source_blocks (
                document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                block_id TEXT NOT NULL,
                text TEXT NOT NULL,
                PRIMARY KEY (document_id, block_id)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS wiki_page_links (
                from_page_id TEXT NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
                to_page_id TEXT NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
                link_type TEXT NOT NULL,
                label TEXT,
                confidence DOUBLE PRECISION,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (from_page_id, to_page_id, link_type)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS pipeline_runs (
                id UUID PRIMARY KEY,
                document_id TEXT REFERENCES documents(id) ON DELETE SET NULL,
                input_source TEXT NOT NULL,
                output_dir TEXT NOT NULL,
                mode TEXT NOT NULL,
                status TEXT NOT NULL,
                manifest JSONB,
                error TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                finished_at TIMESTAMPTZ
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS wiki_page_embeddings (
                page_id TEXT NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
                embedding_model TEXT NOT NULL,
                representation_hash TEXT NOT NULL,
                embedding_vector DOUBLE PRECISION[] NOT NULL,
                embedding_dimension INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'completed',
                error TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                PRIMARY KEY (page_id, embedding_model)
            )
            """
        )
        conn.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_wiki_page_embeddings_model_hash
            ON wiki_page_embeddings (embedding_model, representation_hash)
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS wiki_embedding_vectors (
                id TEXT PRIMARY KEY,
                embedding_model TEXT NOT NULL,
                representation_hash TEXT NOT NULL,
                representation_text TEXT NOT NULL,
                embedding_vector DOUBLE PRECISION[],
                embedding_dimension INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'pending',
                error TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                UNIQUE (embedding_model, representation_hash)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS wiki_embedding_units (
                id TEXT PRIMARY KEY,
                embedding_vector_id TEXT NOT NULL REFERENCES wiki_embedding_vectors(id) ON DELETE RESTRICT,
                page_id TEXT NOT NULL REFERENCES wiki_pages(id) ON DELETE CASCADE,
                source_document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                unit_type TEXT NOT NULL,
                block_refs TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
                text TEXT NOT NULL,
                weight DOUBLE PRECISION NOT NULL DEFAULT 1.0,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """
        )
        conn.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_wiki_embedding_units_page
            ON wiki_embedding_units (page_id)
            """
        )
        conn.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_wiki_embedding_units_vector
            ON wiki_embedding_units (embedding_vector_id)
            """
        )


def get_document(document_id: str) -> dict | None:
    with connect() as conn:
        row = conn.execute(
            """
            SELECT id, filename, mime_type, byte_size, status, source_uri, extracted_text_uri,
                   content_hash, uploaded_at, processed_at, error_message
            FROM documents
            WHERE id = %s
            """,
            (document_id,),
        ).fetchone()
        return dict(row) if row else None


def create_pipeline_run(run_id: str, document_id: str | None, input_source: str, output_dir: str, mode: str) -> None:
    with connect() as conn:
        conn.execute(
            """
            INSERT INTO pipeline_runs (id, document_id, input_source, output_dir, mode, status)
            VALUES (%s, %s, %s, %s, %s, 'running')
            """,
            (run_id, document_id, input_source, output_dir, mode),
        )


def create_pipeline_input_document(
    document_id: str,
    filename: str,
    mime_type: str,
    byte_size: int,
    source_uri: str,
) -> None:
    with connect() as conn:
        conn.execute(
            """
            INSERT INTO documents (
                id,
                filename,
                mime_type,
                byte_size,
                status,
                source_uri,
                extracted_text_uri,
                processed_at,
                error_message
            )
            VALUES (%s, %s, %s, %s, 'processing', %s, NULL, NULL, NULL)
            ON CONFLICT (id) DO UPDATE SET
                filename = EXCLUDED.filename,
                mime_type = EXCLUDED.mime_type,
                byte_size = EXCLUDED.byte_size,
                status = 'processing',
                source_uri = EXCLUDED.source_uri,
                extracted_text_uri = NULL,
                processed_at = NULL,
                error_message = NULL
            """,
            (document_id, filename, mime_type, byte_size, source_uri),
        )


def finish_pipeline_run(run_id: str, manifest: dict[str, Any]) -> list[str]:
    embedded_page_ids: list[str] = []
    with connect() as conn:
        row = conn.execute("SELECT document_id FROM pipeline_runs WHERE id = %s", (run_id,)).fetchone()
        document_id = row["document_id"] if row else None
        if document_id:
            embedded_page_ids = _persist_wiki_outputs(conn, document_id, manifest)
            manifest = _stored_manifest(manifest)
            conn.execute(
                """
                UPDATE documents
                SET status = 'completed', processed_at = now(), error_message = NULL
                WHERE id = %s
                """,
                (document_id,),
            )
        conn.execute(
            """
            UPDATE pipeline_runs
            SET status = 'succeeded', manifest = %s, finished_at = now()
            WHERE id = %s
            """,
            (Json(manifest), run_id),
        )
    return embedded_page_ids


def fail_pipeline_run(run_id: str, error: str) -> None:
    error_message = _truncate_error(error)
    with connect() as conn:
        row = conn.execute("SELECT document_id FROM pipeline_runs WHERE id = %s", (run_id,)).fetchone()
        if row and row["document_id"]:
            conn.execute(
                """
                UPDATE documents
                SET status = 'failed', processed_at = now(), error_message = %s
                WHERE id = %s
                """,
                (error_message, row["document_id"]),
            )
        conn.execute(
            """
            UPDATE pipeline_runs
            SET status = 'failed', error = %s, finished_at = now()
            WHERE id = %s
            """,
            (error_message, run_id),
        )


def get_pipeline_run(run_id: str) -> dict | None:
    with connect() as conn:
        row = conn.execute(
            """
            SELECT id, document_id, input_source, output_dir, mode, status, manifest, error, created_at, finished_at
            FROM pipeline_runs
            WHERE id = %s
            """,
            (run_id,),
        ).fetchone()
        return dict(row) if row else None


def _persist_wiki_outputs(conn: psycopg.Connection, document_id: str, manifest: dict[str, Any]) -> list[str]:
    normalized = manifest.get("normalized")
    if normalized is None:
        out_dir = Path(manifest["out"])
        normalized = json.loads((out_dir / "normalized.json").read_text(encoding="utf-8"))
    links = manifest.get("links")
    if isinstance(links, str):
        links = json.loads(Path(links).read_text(encoding="utf-8"))
    links = links or []
    persisted_page_ids: list[str] = []
    _persist_source_blocks(conn, document_id, manifest)

    source_page_id = f"source:{document_id}"
    source_slug = document_id
    source_page = _page_payload(manifest["source_page"])
    source_markdown = source_page["markdown"]
    source_markdown_uri = _upload_wiki_markdown(source_markdown, f"wiki/sources/{source_slug}.md")
    source_title = source_page.get("title") or _markdown_title(source_markdown) or normalized["document"].get("title") or document_id
    source_summary = _source_summary(normalized)
    _upsert_wiki_page(
        conn,
        source_page_id,
        "source",
        source_title,
        source_slug,
        source_summary,
        source_markdown_uri,
    )
    persisted_page_ids.append(source_page_id)
    _upsert_document_wiki_link(conn, document_id, source_page_id, "source_of", 1.0)
    _persist_embedding_units(conn, source_page_id, document_id, source_markdown)

    concept_pages = [_page_payload(page) for page in manifest.get("concept_pages", [])]
    concept_pages_by_slug = {page["slug"]: page for page in concept_pages}
    generated_concept_slugs = set(concept_pages_by_slug)
    concept_id_by_slug: dict[str, str] = _load_existing_concept_ids_by_slug(conn)
    for concept in normalized.get("concept_ledger", []):
        slug = concept["slug"]
        if slug not in generated_concept_slugs:
            continue
        page_id = f"concept:{slug}"
        concept_id_by_slug[slug] = page_id
        concept_page = concept_pages_by_slug[slug]
        concept_markdown = concept_page["markdown"]
        concept_markdown_uri = _upload_wiki_markdown(concept_markdown, f"wiki/concepts/{slug}.md")
        _upsert_wiki_page(
            conn,
            page_id,
            "concept",
            concept.get("title") or slug,
            slug,
            concept.get("definition") or concept.get("why_page_worthy") or "",
            concept_markdown_uri,
        )
        persisted_page_ids.append(page_id)
        _upsert_document_wiki_link(conn, document_id, page_id, "extracted_concept", concept.get("importance_score"))
        _persist_embedding_units(conn, page_id, document_id, concept_markdown)

    for link in links:
        from_page_id = _resolve_page_id(link.get("source"), source_page_id, concept_id_by_slug)
        to_page_id = _resolve_page_id(link.get("target"), source_page_id, concept_id_by_slug)
        if from_page_id and to_page_id and from_page_id != to_page_id:
            _upsert_wiki_page_link(
                conn,
                from_page_id,
                to_page_id,
                link.get("relation") or "related_to",
                link.get("label"),
                link.get("confidence"),
            )
    _refresh_source_related_links(conn)
    return persisted_page_ids


def _persist_source_blocks(conn: psycopg.Connection, document_id: str, manifest: dict[str, Any]) -> None:
    blocks = manifest.get("source_blocks")
    if isinstance(blocks, str):
        path = Path(blocks)
        if not path.exists():
            return
        blocks = json.loads(path.read_text(encoding="utf-8"))
    if not blocks:
        return
    conn.execute("DELETE FROM source_blocks WHERE document_id = %s", (document_id,))
    for block in blocks:
        block_id = block.get("block_id")
        text = block.get("text")
        if not block_id or not text:
            continue
        conn.execute(
            """
            INSERT INTO source_blocks (document_id, block_id, text)
            VALUES (%s, %s, %s)
            ON CONFLICT (document_id, block_id) DO UPDATE SET
                text = EXCLUDED.text
            """,
            (document_id, block_id, text),
        )


def _persist_embedding_units(conn: psycopg.Connection, page_id: str, document_id: str, markdown: str) -> None:
    if not markdown:
        return
    units = _extract_embedding_units(markdown)
    conn.execute("DELETE FROM wiki_embedding_units WHERE page_id = %s", (page_id,))
    for index, unit in enumerate(units, start=1):
        representation_text = _unit_representation(unit["unit_type"], unit["text"])
        representation_hash = _hash_text(representation_text)
        vector_id = f"embedding:{representation_hash}"
        unit_id = f"unit:{_hash_text('|'.join([page_id, unit['unit_type'], ','.join(unit['block_refs']), unit['text']]))[:24]}"
        conn.execute(
            """
            INSERT INTO wiki_embedding_vectors (
                id,
                embedding_model,
                representation_hash,
                representation_text,
                status,
                updated_at
            )
            VALUES (%s, %s, %s, %s, 'pending', now())
            ON CONFLICT (embedding_model, representation_hash) DO UPDATE SET
                representation_text = EXCLUDED.representation_text,
                updated_at = now()
            """,
            (vector_id, "canonical-text", representation_hash, representation_text),
        )
        conn.execute(
            """
            INSERT INTO wiki_embedding_units (
                id,
                embedding_vector_id,
                page_id,
                source_document_id,
                unit_type,
                block_refs,
                text,
                weight,
                updated_at
            )
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, now())
            ON CONFLICT (id) DO UPDATE SET
                embedding_vector_id = EXCLUDED.embedding_vector_id,
                page_id = EXCLUDED.page_id,
                source_document_id = EXCLUDED.source_document_id,
                unit_type = EXCLUDED.unit_type,
                block_refs = EXCLUDED.block_refs,
                text = EXCLUDED.text,
                weight = EXCLUDED.weight,
                updated_at = now()
            """,
            (
                unit_id,
                vector_id,
                page_id,
                document_id,
                unit["unit_type"],
                unit["block_refs"],
                unit["text"],
                unit["weight"],
            ),
        )


def _extract_embedding_units(markdown: str) -> list[dict[str, Any]]:
    units: list[dict[str, Any]] = []
    current_section = "body"
    in_frontmatter = False
    for raw_line in markdown.splitlines():
        line = raw_line.strip()
        if line == "---":
            in_frontmatter = not in_frontmatter
            continue
        if in_frontmatter or not line:
            continue
        heading = re.match(r"^##+\s+(.+?)\s*$", line)
        if heading:
            current_section = heading.group(1).strip()
            continue
        if not _source_block_ids(line):
            continue
        unit_text = _clean_unit_text(line)
        if not unit_text:
            continue
        units.append(
            {
                "unit_type": _unit_type(current_section),
                "block_refs": _source_block_ids(line),
                "text": unit_text,
                "weight": _section_weight(current_section),
            }
        )
    return _dedupe_units(units)


def _dedupe_units(units: list[dict[str, Any]]) -> list[dict[str, Any]]:
    deduped: list[dict[str, Any]] = []
    seen: set[tuple[str, tuple[str, ...], str]] = set()
    for unit in units:
        key = (unit["unit_type"], tuple(unit["block_refs"]), unit["text"])
        if key in seen:
            continue
        seen.add(key)
        deduped.append(unit)
    return deduped


def _unit_type(section: str) -> str:
    normalized = section.strip().lower()
    mapping = {
        "key points": "key_point",
        "observations": "observation",
        "observation": "observation",
        "categories": "category",
        "core concepts": "core_concept",
        "section candidates": "section_candidate",
        "mentions": "mention",
    }
    return mapping.get(normalized, "source_block")


def _section_weight(section: str) -> float:
    weights = {
        "key_point": 1.35,
        "observation": 1.30,
        "core_concept": 1.20,
        "section_candidate": 1.15,
        "mention": 1.05,
        "category": 0.95,
    }
    return weights.get(_unit_type(section), 1.0)


def _unit_representation(unit_type: str, text: str) -> str:
    normalized = re.sub(r"\s+", " ", text).strip().lower()
    return f"{unit_type}\n{normalized}"


def _source_block_ids(text: str) -> list[str]:
    block_ids = []
    for group in re.findall(r"\[((?:B\d{4})(?:\s*,\s*B\d{4})*)\]", text):
        block_ids.extend(part.strip() for part in group.split(",") if part.strip())
    return list(dict.fromkeys(block_ids))


def _clean_unit_text(text: str) -> str:
    cleaned = re.sub(r"\s*\[(?:B\d{4})(?:\s*,\s*B\d{4})*\]", "", text)
    cleaned = re.sub(r"^[-*]\s+", "", cleaned.strip())
    cleaned = re.sub(r"^(?:\[[A-Za-z0-9_,\s-]+\]\s*)+", "", cleaned)
    return cleaned.strip()


def _hash_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _upload_wiki_markdown(markdown: str, object_name: str) -> str:
    return write_text_object(object_name, markdown)


def _refresh_source_related_links(conn: psycopg.Connection) -> None:
    rows = conn.execute(
        """
        SELECT l.from_page_id AS source_id, l.to_page_id AS concept_id, c.title AS concept_title
        FROM wiki_page_links l
        JOIN wiki_pages s ON s.id = l.from_page_id
        JOIN wiki_pages c ON c.id = l.to_page_id
        WHERE l.link_type = 'source_mentions_concept'
          AND s.page_type = 'source'
          AND c.page_type = 'concept'
          AND s.status = 'active'
          AND c.status = 'active'
        """
    ).fetchall()

    conn.execute("DELETE FROM wiki_page_links WHERE link_type = 'source_related_to'")
    source_concepts: dict[str, dict[str, str]] = {}
    concept_source_counts: dict[str, int] = {}
    for row in rows:
        source_id = row["source_id"]
        concept_id = row["concept_id"]
        source_concepts.setdefault(source_id, {})[concept_id] = row["concept_title"] or concept_id

    for concepts in source_concepts.values():
        for concept_id in concepts:
            concept_source_counts[concept_id] = concept_source_counts.get(concept_id, 0) + 1

    def concept_weight(concept_id: str) -> float:
        return 1.0 / max(1, concept_source_counts.get(concept_id, 1))

    source_ids = sorted(source_concepts)
    for i, source_a in enumerate(source_ids):
        concepts_a = source_concepts[source_a]
        total_a = sum(concept_weight(concept_id) ** 2 for concept_id in concepts_a)
        if total_a <= 0:
            continue
        for source_b in source_ids[i + 1 :]:
            concepts_b = source_concepts[source_b]
            shared_concepts = sorted(set(concepts_a).intersection(concepts_b))
            if not shared_concepts:
                continue
            total_b = sum(concept_weight(concept_id) ** 2 for concept_id in concepts_b)
            if total_b <= 0:
                continue
            shared_weight = sum(concept_weight(concept_id) ** 2 for concept_id in shared_concepts)
            score = shared_weight / ((total_a * total_b) ** 0.5)
            if score < SOURCE_RELATED_THRESHOLD:
                continue
            label = _source_related_label(shared_concepts, concepts_a)
            _upsert_wiki_page_link(conn, source_a, source_b, "source_related_to", label, score)


def _source_related_label(shared_concepts: list[str], concept_titles: dict[str, str]) -> str:
    titles = [concept_titles.get(concept_id, concept_id) for concept_id in shared_concepts]
    visible_titles = titles[:5]
    suffix = f" 외 {len(titles) - len(visible_titles)}개" if len(titles) > len(visible_titles) else ""
    return f"공유 concept: {', '.join(visible_titles)}{suffix}"


def _source_summary(normalized: dict[str, Any]) -> str:
    for note in normalized.get("semantic_notes", []):
        summary = note.get("semantic_summary")
        if summary:
            return summary
    return normalized.get("document", {}).get("title", "")


def _markdown_title(markdown: str) -> str:
    for line in markdown.splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return ""


def _page_payload(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        if "markdown" not in value:
            raise RuntimeError("Pipeline manifest page payload is missing markdown")
        return value
    path = Path(str(value))
    if not path.exists():
        raise RuntimeError(f"Pipeline manifest page path does not exist: {path}")
    return {
        "slug": path.stem,
        "title": _markdown_title(path.read_text(encoding="utf-8")),
        "markdown_path": str(path),
        "markdown": path.read_text(encoding="utf-8"),
    }


def _stored_manifest(manifest: dict[str, Any]) -> dict[str, Any]:
    stored = dict(manifest)
    source_page = stored.get("source_page")
    if isinstance(source_page, dict):
        stored["source_page"] = _stored_page(source_page)
    stored["concept_pages"] = [
        _stored_page(page) if isinstance(page, dict) else page
        for page in stored.get("concept_pages", [])
    ]
    stored.pop("normalized", None)
    stored.pop("source_blocks", None)
    return stored


def _stored_page(page: dict[str, Any]) -> dict[str, Any]:
    return {
        key: value
        for key, value in page.items()
        if key not in {"markdown", "source_extraction_artifact"}
    }


def _resolve_page_id(value: str | None, source_page_id: str, concept_id_by_slug: dict[str, str]) -> str | None:
    if not value:
        return None
    if value.startswith("source:"):
        return source_page_id
    if value.startswith("concept:"):
        return concept_id_by_slug.get(value.split(":", 1)[1])
    return None


def _load_existing_concept_ids_by_slug(conn: psycopg.Connection) -> dict[str, str]:
    rows = conn.execute(
        """
        SELECT slug, id
        FROM wiki_pages
        WHERE page_type = 'concept' AND status = 'active'
        """
    ).fetchall()
    return {row["slug"]: row["id"] for row in rows}


def _upsert_wiki_page(
    conn: psycopg.Connection,
    page_id: str,
    page_type: str,
    title: str,
    slug: str,
    summary: str,
    markdown_uri: str,
) -> None:
    conn.execute(
        """
        INSERT INTO wiki_pages (id, page_type, title, slug, summary, markdown_uri, status, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, 'active', now(), now())
        ON CONFLICT (id) DO UPDATE SET
            page_type = EXCLUDED.page_type,
            title = EXCLUDED.title,
            slug = EXCLUDED.slug,
            summary = EXCLUDED.summary,
            markdown_uri = EXCLUDED.markdown_uri,
            status = 'active',
            updated_at = now()
        """,
        (page_id, page_type, title, slug, summary, markdown_uri),
    )


def _truncate_error(error: str, limit: int = 240) -> str:
    text = str(error)
    if len(text) <= limit:
        return text
    return text[: limit - 3] + "..."


def _upsert_document_wiki_link(
    conn: psycopg.Connection,
    document_id: str,
    wiki_page_id: str,
    relation_type: str,
    confidence: float | None,
) -> None:
    conn.execute(
        """
        INSERT INTO document_wiki_links (document_id, wiki_page_id, relation_type, confidence, created_at)
        VALUES (%s, %s, %s, %s, now())
        ON CONFLICT (document_id, wiki_page_id, relation_type) DO UPDATE SET
            confidence = EXCLUDED.confidence
        """,
        (document_id, wiki_page_id, relation_type, confidence),
    )


def _upsert_wiki_page_link(
    conn: psycopg.Connection,
    from_page_id: str,
    to_page_id: str,
    link_type: str,
    label: str | None,
    confidence: float | None,
) -> None:
    conn.execute(
        """
        INSERT INTO wiki_page_links (from_page_id, to_page_id, link_type, label, confidence, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, now(), now())
        ON CONFLICT (from_page_id, to_page_id, link_type) DO UPDATE SET
            label = EXCLUDED.label,
            confidence = EXCLUDED.confidence,
            updated_at = now()
        """,
        (from_page_id, to_page_id, link_type, label, confidence),
    )
