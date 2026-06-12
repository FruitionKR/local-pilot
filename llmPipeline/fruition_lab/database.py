from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Json

from .storage import write_text_object


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


def finish_pipeline_run(run_id: str, manifest: dict[str, Any]) -> None:
    with connect() as conn:
        row = conn.execute("SELECT document_id FROM pipeline_runs WHERE id = %s", (run_id,)).fetchone()
        document_id = row["document_id"] if row else None
        if document_id:
            _persist_wiki_outputs(conn, document_id, manifest)
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


def _persist_wiki_outputs(conn: psycopg.Connection, document_id: str, manifest: dict[str, Any]) -> None:
    out_dir = Path(manifest["out"])
    normalized = json.loads((out_dir / "normalized.json").read_text(encoding="utf-8"))
    links = json.loads(Path(manifest["links"]).read_text(encoding="utf-8"))

    source_page_id = f"source:{document_id}"
    source_slug = document_id
    source_page_path = Path(manifest["source_page"])
    source_markdown_uri = _upload_wiki_markdown(source_page_path, f"wiki/sources/{source_slug}.md")
    source_title = _markdown_title(source_page_path) or normalized["document"].get("title") or document_id
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
    _upsert_document_wiki_link(conn, document_id, source_page_id, "source_of", 1.0)

    generated_concept_slugs = {Path(path).stem for path in manifest.get("concept_pages", [])}
    concept_id_by_slug: dict[str, str] = _load_existing_concept_ids_by_slug(conn)
    for concept in normalized.get("concept_ledger", []):
        slug = concept["slug"]
        if slug not in generated_concept_slugs:
            continue
        page_id = f"concept:{slug}"
        concept_id_by_slug[slug] = page_id
        concept_page_path = out_dir / "wiki" / "concepts" / f"{slug}.md"
        concept_markdown_uri = _upload_wiki_markdown(concept_page_path, f"wiki/concepts/{slug}.md")
        _upsert_wiki_page(
            conn,
            page_id,
            "concept",
            concept.get("title") or slug,
            slug,
            concept.get("definition") or concept.get("why_page_worthy") or "",
            concept_markdown_uri,
        )
        _upsert_document_wiki_link(conn, document_id, page_id, "extracted_concept", concept.get("importance_score"))

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


def _upload_wiki_markdown(path: Path, object_name: str) -> str:
    return write_text_object(object_name, path.read_text(encoding="utf-8"))


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


def _markdown_title(path: Path) -> str:
    if not path.exists():
        return ""
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return ""


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
