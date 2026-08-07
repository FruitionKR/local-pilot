from __future__ import annotations

import uuid

import psycopg

from app.modules.wiki_ingestion.infrastructure.embedding_units import (
    extract_embedding_units,
    hash_text,
    unit_representation,
)
from app.modules.wiki_ingestion.infrastructure.object_storage import (
    read_text_object,
    write_text_object,
)


def persist_embedding_units(
    conn: psycopg.Connection,
    page_id: str,
    document_id: str,
    markdown: str,
) -> None:
    if not markdown:
        return
    units = extract_embedding_units(markdown)
    conn.execute("DELETE FROM wiki_embedding_units WHERE page_id = %s", (page_id,))
    for unit in units:
        representation_text = unit_representation(unit["unit_type"], unit["text"])
        representation_hash = hash_text(representation_text)
        vector_id = f"embedding:{representation_hash}"
        unit_id = f"unit:{hash_text('|'.join([page_id, unit['unit_type'], ','.join(unit['block_refs']), unit['text']]))[:24]}"
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


def upload_wiki_markdown(markdown: str, object_name: str) -> str:
    return write_text_object(object_name, markdown)


def read_optional_text_object(object_name: str) -> str:
    try:
        return read_text_object(object_name)
    except Exception:
        return ""


def delete_source_related_links(
    conn: psycopg.Connection,
    user_id: str,
    workspace_id: str,
) -> None:
    conn.execute(
        """
        DELETE FROM wiki_page_links l
        USING wiki_pages from_page, wiki_pages to_page
        WHERE l.link_type = 'source_related_to'
          AND from_page.id = l.from_page_id
          AND to_page.id = l.to_page_id
          AND from_page.page_type = 'source'
          AND to_page.page_type = 'source'
          AND from_page.user_id = %s
          AND from_page.workspace_id = %s
          AND to_page.user_id = %s
          AND to_page.workspace_id = %s
        """,
        (user_id, workspace_id, user_id, workspace_id),
    )


def load_existing_concept_ids_by_slug(
    conn: psycopg.Connection,
    user_id: str,
    workspace_id: str,
) -> dict[str, str]:
    rows = conn.execute(
        """
        SELECT slug, id
        FROM wiki_pages
        WHERE page_type = 'concept'
          AND status = 'active'
          AND user_id = %s
          AND workspace_id = %s
        """,
        (user_id, workspace_id),
    ).fetchall()
    return {row["slug"]: row["id"] for row in rows}


def resolve_or_create_wiki_page_id(
    conn: psycopg.Connection,
    user_id: str,
    workspace_id: str,
    page_type: str,
    slug: str,
) -> str:
    row = conn.execute(
        """
        SELECT id
        FROM wiki_pages
        WHERE user_id = %s
          AND workspace_id = %s
          AND page_type = %s
          AND slug = %s
        """,
        (user_id, workspace_id, page_type, slug),
    ).fetchone()
    if row:
        return row["id"]
    return f"wiki_page_{uuid.uuid4()}"


def upsert_wiki_page(
    conn: psycopg.Connection,
    page_id: str,
    page_type: str,
    title: str,
    slug: str,
    summary: str,
    markdown_uri: str,
    user_id: str,
    workspace_id: str,
) -> None:
    conn.execute(
        """
        INSERT INTO wiki_pages (id, page_type, title, slug, summary, markdown_uri, user_id, workspace_id, status, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 'active', now(), now())
        ON CONFLICT (user_id, workspace_id, page_type, slug) DO UPDATE SET
            page_type = EXCLUDED.page_type,
            title = EXCLUDED.title,
            slug = EXCLUDED.slug,
            summary = EXCLUDED.summary,
            markdown_uri = EXCLUDED.markdown_uri,
            user_id = EXCLUDED.user_id,
            workspace_id = EXCLUDED.workspace_id,
            status = 'active',
            updated_at = now()
        """,
        (
            page_id,
            page_type,
            title,
            slug,
            summary,
            markdown_uri,
            user_id,
            workspace_id,
        ),
    )


def upsert_document_wiki_link(
    conn: psycopg.Connection,
    document_id: str,
    wiki_page_id: str,
    relation_type: str,
    confidence: float | None,
    workspace_id: str,
) -> None:
    conn.execute(
        """
        INSERT INTO document_wiki_links (document_id, wiki_page_id, relation_type, confidence, workspace_id, created_at)
        VALUES (%s, %s, %s, %s, %s, now())
        ON CONFLICT (document_id, wiki_page_id, relation_type) DO UPDATE SET
            confidence = EXCLUDED.confidence,
            workspace_id = EXCLUDED.workspace_id
        """,
        (document_id, wiki_page_id, relation_type, confidence, workspace_id),
    )


def upsert_wiki_page_link(
    conn: psycopg.Connection,
    from_page_id: str,
    to_page_id: str,
    link_type: str,
    label: str | None,
    confidence: float | None,
    workspace_id: str,
) -> None:
    conn.execute(
        """
        INSERT INTO wiki_page_links (from_page_id, to_page_id, link_type, label, confidence, workspace_id, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, now(), now())
        ON CONFLICT (from_page_id, to_page_id, link_type) DO UPDATE SET
            label = EXCLUDED.label,
            confidence = EXCLUDED.confidence,
            workspace_id = EXCLUDED.workspace_id,
            updated_at = now()
        """,
        (from_page_id, to_page_id, link_type, label, confidence, workspace_id),
    )
