from __future__ import annotations

import hashlib
import json
import os
import re
import uuid
from datetime import date
from pathlib import Path
from typing import Any, Callable

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Json

from app.modules.wiki_ingestion.infrastructure.object_storage import read_text_object, write_text_object


SOURCE_RELATED_THRESHOLD = 0.75
ALLOWED_CORE_RELATIONS = {
    "part_of",
    "child_of",
    "uses_or_depends_on",
    "contrasts_with",
    "supports_or_enables",
    "related_evidence",
}
MATERIALIZED_CORE_RELATIONS = ALLOWED_CORE_RELATIONS - {"related_evidence"}


def _unique_keep_order(values: list[str]) -> list[str]:
    return list(dict.fromkeys(value for value in values if value))


def _today_iso() -> str:
    return date.today().isoformat()


def _slugify(value: str) -> str:
    text = value.strip().lower()
    text = re.sub(r"[^a-z0-9가-힣]+", "-", text)
    text = re.sub(r"-+", "-", text).strip("-")
    return text or "untitled"


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
                user_id TEXT NOT NULL DEFAULT 'local-user',
                workspace_id TEXT NOT NULL DEFAULT 'local-workspace',
                status TEXT NOT NULL DEFAULT 'active',
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """
        )
        conn.execute("ALTER TABLE wiki_pages ADD COLUMN IF NOT EXISTS user_id TEXT NOT NULL DEFAULT 'local-user'")
        conn.execute("ALTER TABLE wiki_pages ADD COLUMN IF NOT EXISTS workspace_id TEXT NOT NULL DEFAULT 'local-workspace'")
        conn.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_wiki_pages_workspace_type_slug
            ON wiki_pages (user_id, workspace_id, page_type, slug)
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


def list_active_concept_index(user_id: str = "local-user", workspace_id: str = "local-workspace") -> list[dict[str, Any]]:
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT slug, title, summary, markdown_uri
            FROM wiki_pages
            WHERE page_type = 'concept'
              AND status = 'active'
              AND user_id = %s
              AND workspace_id = %s
            ORDER BY slug
            """,
            (user_id, workspace_id),
        ).fetchall()
    concepts = []
    for row in rows:
        markdown = _read_optional_text_object(row["markdown_uri"])
        concepts.append(_concept_index_from_markdown(row["slug"], row["title"], row["markdown_uri"], markdown))
    return concepts


def _concept_index_from_markdown(slug: str, title: str, markdown_uri: str, markdown: str) -> dict[str, Any]:
    return {
        "slug": slug,
        "title": title,
        "aliases": _markdown_list_section(markdown, "Aliases"),
        "definition": _markdown_section(markdown, "Definition"),
        "evidence": _markdown_list_section(markdown, "Evidence"),
        "why_page_worthy": _markdown_section(markdown, "Why It Matters"),
        "path": markdown_uri,
    }


PromotionPageGenerator = Callable[[dict[str, Any]], dict[str, Any]]


def lint_wiki_workspace(
    user_id: str = "local-user",
    workspace_id: str = "local-workspace",
    *,
    materialize_promotions: bool = False,
    promotion_page_generator: PromotionPageGenerator | None = None,
) -> dict[str, Any]:
    active_path = f"wiki/{user_id}/{workspace_id}/clusters/active.md"
    log_path = f"wiki/{user_id}/{workspace_id}/logs/{_today_iso()}.md"
    active_markdown = _read_optional_text_object(active_path)
    clusters = _parse_active_cluster_lint(active_markdown)
    source_refs = _unique_keep_order(
        ref
        for cluster in clusters
        for ref in cluster.get("refs", [])
    )
    orphan_refs = _orphan_source_refs(source_refs)
    invalid_relations = [
        {
            "cluster_id": cluster["id"],
            **relation,
        }
        for cluster in clusters
        for relation in cluster.get("invalid_relations", [])
    ]
    invalid_promotions = [
        {
            "cluster_id": cluster["id"],
            "reason": "promotion candidate has no source_refs",
        }
        for cluster in clusters
        if cluster.get("promotion_status") == "candidate" and not cluster.get("promotion_source_refs")
    ]
    promotion_candidates = [
        cluster["id"]
        for cluster in clusters
        if cluster.get("promotion_status") == "candidate" and cluster.get("promotion_source_refs")
    ]
    needs_review = [
        cluster["id"]
        for cluster in clusters
        if cluster.get("promotion_status") == "needs_review"
        or any(claim.get("decision") == "needs_review" for claim in cluster.get("claims", []))
    ]
    relation_candidates = [
        {
            "cluster_id": cluster["id"],
            "target": relation.get("target"),
            "relation": relation.get("relation"),
            "evidence": relation.get("evidence", []),
        }
        for cluster in clusters
        for relation in cluster.get("relations", [])
    ]
    result = {
        "user_id": user_id,
        "workspace_id": workspace_id,
        "active_path": active_path,
        "cluster_count": len(clusters),
        "source_ref_count": len(source_refs),
        "orphan_refs": orphan_refs,
        "promotion_candidates": promotion_candidates,
        "needs_review": needs_review,
        "relation_candidates": relation_candidates,
        "invalid_relations": invalid_relations,
        "invalid_promotions": invalid_promotions,
        "materialized_promotions": [],
        "merged_promotions": [],
        "materialized_relations": [],
    }
    if materialize_promotions and promotion_page_generator is not None:
        with connect() as conn:
            materialized = _materialize_promotion_candidates(
                conn,
                user_id,
                workspace_id,
                clusters,
                active_markdown,
                active_path,
                promotion_page_generator,
            )
        result["materialized_promotions"] = materialized["promotions"]
        result["merged_promotions"] = materialized["merged_promotions"]
        result["materialized_relations"] = materialized["relations"]
        result["active_path"] = active_path
    existing_log = _read_optional_text_object(log_path)
    separator = "\n" if existing_log and not existing_log.endswith("\n") else ""
    write_text_object(log_path, f"{existing_log}{separator}{_lint_log_markdown(result)}")
    return result


def _parse_active_cluster_lint(markdown: str) -> list[dict[str, Any]]:
    clusters = []
    for cluster_id, section in _cluster_sections_by_id(markdown).items():
        relations, invalid_relations = _cluster_relation_items(section)
        cluster = {
            "id": cluster_id,
            "refs": _refs_in_text(section),
            "claims": _cluster_claims(section),
            "relations": relations,
            "invalid_relations": invalid_relations,
            "promotion_status": _cluster_promotion_status(section),
            "promotion_source_refs": _cluster_promotion_source_refs(section),
        }
        clusters.append(cluster)
    return clusters


def _refs_in_text(text: str) -> list[str]:
    ref_pattern = r"[A-Za-z0-9_.-]+:B\d{4}"
    return _unique_keep_order(re.findall(ref_pattern, text))


def _cluster_claims(section: str) -> list[dict[str, str]]:
    claims = []
    current_claim: dict[str, str] | None = None
    for line in section.splitlines():
        stripped = line.strip()
        match = re.match(r"^- (claim_[^:]+|ev_[^:]+):\s*(.+)$", stripped)
        if match:
            text = match.group(2)
            current_claim = {
                "id": match.group(1),
                "text": text,
                "claim": _clean_unit_text(text),
                "refs": _refs_in_text(text),
                "decision": "",
            }
            claims.append(current_claim)
            continue
        if current_claim and stripped.startswith("cluster_decision:"):
            current_claim["decision"] = stripped.split(":", 1)[1].strip()
    return claims


def _cluster_relations(section: str) -> list[dict[str, Any]]:
    relations, _invalid = _cluster_relation_items(section)
    return relations


def _cluster_relation_items(section: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    relations: list[dict[str, Any]] = []
    invalid: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    in_relations = False

    def finish_current() -> None:
        nonlocal current
        if not current:
            return
        target = str(current.get("target") or "").strip()
        relation = str(current.get("relation") or "").strip()
        evidence = [str(item).strip() for item in current.get("evidence", []) if str(item).strip()]
        reason = str(current.get("reason") or "").strip()
        item = {"target": target, "relation": relation, "evidence": evidence, "reason": reason}
        if target and relation in ALLOWED_CORE_RELATIONS and evidence:
            relations.append(item)
        else:
            missing = []
            if not target:
                missing.append("target")
            if relation not in ALLOWED_CORE_RELATIONS:
                missing.append("relation")
            if not evidence:
                missing.append("evidence")
            item["missing"] = missing
            invalid.append(item)
        current = None

    for line in section.splitlines():
        stripped = line.strip()
        if stripped == "### Core Relation Candidates":
            in_relations = True
            continue
        if in_relations and stripped.startswith("### "):
            finish_current()
            break
        if not in_relations:
            continue
        if stripped.startswith("- target:"):
            finish_current()
            current = {"target": stripped.split(":", 1)[1].strip(), "relation": "", "evidence": []}
        elif current and stripped.startswith("relation:"):
            current["relation"] = stripped.split(":", 1)[1].strip()
        elif current and stripped.startswith("evidence:"):
            current["evidence"] = [part.strip() for part in stripped.split("[", 1)[-1].rstrip("]").split(",") if part.strip()]
        elif current and stripped.startswith("reason:"):
            current["reason"] = stripped.split(":", 1)[1].strip()
    if in_relations:
        finish_current()
    return relations, invalid


def _cluster_promotion_status(section: str) -> str:
    in_promotion = False
    for line in section.splitlines():
        stripped = line.strip()
        if stripped == "### Promotion":
            in_promotion = True
            continue
        if in_promotion and stripped.startswith("### "):
            break
        if in_promotion and stripped.startswith("status:"):
            return stripped.split(":", 1)[1].strip()
    return ""


def _cluster_promotion_source_refs(section: str) -> list[str]:
    in_promotion = False
    for line in section.splitlines():
        stripped = line.strip()
        if stripped == "### Promotion":
            in_promotion = True
            continue
        if in_promotion and stripped.startswith("### "):
            break
        if in_promotion and stripped.startswith("source_refs:"):
            return [part.strip() for part in stripped.split("[", 1)[-1].rstrip("]").split(",") if part.strip()]
    return []


def _orphan_source_refs(refs: list[str]) -> list[str]:
    if not refs:
        return []
    existing = set()
    by_document: dict[str, list[str]] = {}
    for ref in refs:
        document_id, _sep, block_id = ref.partition(":")
        if document_id and block_id:
            by_document.setdefault(document_id, []).append(block_id)
    with connect() as conn:
        for document_id, block_ids in by_document.items():
            rows = conn.execute(
                """
                SELECT block_id
                FROM source_blocks
                WHERE document_id = %s
                  AND block_id = ANY(%s)
                """,
                (document_id, block_ids),
            ).fetchall()
            existing.update(f"{document_id}:{row['block_id']}" for row in rows)
    return [ref for ref in refs if ref not in existing]


def _materialize_promotion_candidates(
    conn: psycopg.Connection,
    user_id: str,
    workspace_id: str,
    clusters: list[dict[str, Any]],
    active_markdown: str,
    active_path: str,
    promotion_page_generator: PromotionPageGenerator,
) -> dict[str, list[dict[str, Any]]]:
    active_sections = _cluster_sections_by_id(active_markdown)
    archived_sections: list[str] = []
    materialized_promotions: list[dict[str, Any]] = []
    merged_promotions: list[dict[str, Any]] = []
    materialized_relations: list[dict[str, Any]] = []
    existing_concept_ids = _load_existing_concept_ids_by_slug(conn, user_id, workspace_id)
    for cluster in clusters:
        cluster_id = cluster["id"]
        if cluster.get("promotion_status") != "candidate":
            continue
        if not cluster.get("promotion_source_refs"):
            continue
        claims = [claim for claim in cluster.get("claims", []) if claim.get("refs")]
        if not claims:
            continue
        if cluster_id in existing_concept_ids:
            if _merge_promotion_into_existing_concept(conn, user_id, workspace_id, cluster_id, claims):
                merged_promotions.append(
                    {
                        "cluster_id": cluster_id,
                        "concept_slug": cluster_id,
                        "page_id": existing_concept_ids[cluster_id],
                    }
                )
                section = active_sections.pop(cluster_id, "")
                if section:
                    archived_sections.append(
                        f"{section}\n\n### Archived\nmerged_to: concept:{cluster_id}\nmerged_at: {_today_iso()}"
                    )
            continue
        source_refs = _unique_keep_order(ref for claim in claims for ref in claim.get("refs", []))
        source_blocks = _source_blocks_for_refs(conn, source_refs)
        promotion_input = {
            **cluster,
            "claims": claims,
            "source_blocks": source_blocks,
        }
        page = promotion_page_generator(promotion_input)
        markdown = str(page.get("markdown") or "").strip()
        title = str(page.get("title") or cluster_id).strip()
        slug = _slugify(str(page.get("slug") or cluster_id))
        if not markdown or not slug or slug == "untitled":
            continue
        page_id = _resolve_or_create_wiki_page_id(conn, user_id, workspace_id, "concept", slug)
        markdown_uri = _upload_wiki_markdown(markdown + "\n", f"wiki/{user_id}/{workspace_id}/concepts/{slug}.md")
        _upsert_wiki_page(conn, page_id, "concept", title, slug, _markdown_section(markdown, "Definition"), markdown_uri, user_id, workspace_id)
        first_document_id = source_refs[0].split(":", 1)[0] if source_refs else ""
        _persist_embedding_units(conn, page_id, first_document_id, markdown)
        existing_concept_ids[slug] = page_id
        materialized_promotions.append({"cluster_id": cluster_id, "concept_slug": slug, "page_id": page_id})
        for relation in cluster.get("relations", []):
            relation_type = relation.get("relation")
            target_slug = str(relation.get("target") or "").split("concept:", 1)[-1]
            target_page_id = existing_concept_ids.get(target_slug)
            if relation_type not in MATERIALIZED_CORE_RELATIONS or not target_page_id:
                continue
            _upsert_wiki_page_link(conn, page_id, target_page_id, relation_type, relation.get("reason"), 0.8)
            materialized_relations.append(
                {
                    "from": slug,
                    "to": target_slug,
                    "relation": relation_type,
                    "evidence": relation.get("evidence", []),
                }
            )
        section = active_sections.pop(cluster_id, "")
        if section:
            archived_sections.append(f"{section}\n\n### Archived\npromoted_to: concept:{slug}\npromoted_at: {_today_iso()}")
    materialized_relations = _materialize_active_relation_candidates(conn, clusters, existing_concept_ids)
    if materialized_promotions or merged_promotions:
        _write_active_cluster_sections(active_path, active_sections)
        _append_archived_clusters(user_id, workspace_id, archived_sections)
    return {
        "promotions": materialized_promotions,
        "merged_promotions": merged_promotions,
        "relations": materialized_relations,
    }


def _merge_promotion_into_existing_concept(
    conn: psycopg.Connection,
    user_id: str,
    workspace_id: str,
    concept_slug: str,
    claims: list[dict[str, Any]],
) -> bool:
    row = conn.execute(
        """
        SELECT id, markdown_uri
        FROM wiki_pages
        WHERE page_type = 'concept'
          AND status = 'active'
          AND user_id = %s
          AND workspace_id = %s
          AND slug = %s
        """,
        (user_id, workspace_id, concept_slug),
    ).fetchone()
    if not row:
        return False
    markdown = _read_optional_text_object(row["markdown_uri"])
    if not markdown:
        return False
    updates = [
        {
            "claim_id": claim.get("id"),
            "claim": claim.get("claim") or claim.get("text"),
            "refs": claim.get("refs", []),
        }
        for claim in claims
    ]
    updated_markdown = _append_concept_evidence(markdown, updates)
    if updated_markdown == markdown:
        return True
    write_text_object(row["markdown_uri"], updated_markdown)
    first_ref = next((ref for claim in claims for ref in claim.get("refs", [])), "")
    document_id = first_ref.split(":", 1)[0] if first_ref else ""
    _persist_embedding_units(conn, row["id"], document_id, updated_markdown)
    return True


def _materialize_active_relation_candidates(
    conn: psycopg.Connection,
    clusters: list[dict[str, Any]],
    concept_ids_by_slug: dict[str, str],
) -> list[dict[str, Any]]:
    materialized: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str]] = set()
    for cluster in clusters:
        source_slug = str(cluster.get("id") or "")
        source_page_id = concept_ids_by_slug.get(source_slug)
        if not source_page_id:
            continue
        for relation in cluster.get("relations", []):
            relation_type = str(relation.get("relation") or "")
            if relation_type not in MATERIALIZED_CORE_RELATIONS:
                continue
            target_slug = str(relation.get("target") or "").split("concept:", 1)[-1]
            target_page_id = concept_ids_by_slug.get(target_slug)
            if not target_page_id or target_page_id == source_page_id:
                continue
            evidence_refs = _relation_evidence_source_refs(cluster, relation)
            if not evidence_refs:
                continue
            key = (source_slug, target_slug, relation_type)
            if key in seen:
                continue
            seen.add(key)
            _upsert_wiki_page_link(conn, source_page_id, target_page_id, relation_type, relation.get("reason"), 0.8)
            materialized.append(
                {
                    "from": source_slug,
                    "to": target_slug,
                    "relation": relation_type,
                    "evidence": relation.get("evidence", []),
                    "source_refs": evidence_refs,
                }
            )
    return materialized


def _relation_evidence_source_refs(cluster: dict[str, Any], relation: dict[str, Any]) -> list[str]:
    claims_by_id = {str(claim.get("id") or ""): claim for claim in cluster.get("claims", [])}
    refs: list[str] = []
    for item in relation.get("evidence", []):
        evidence = str(item).strip()
        if not evidence:
            continue
        if re.search(r"[A-Za-z0-9_.-]+:B\d{4}", evidence):
            refs.append(evidence)
            continue
        claim = claims_by_id.get(evidence)
        if claim:
            refs.extend(str(ref) for ref in claim.get("refs", []) if ref)
    return _unique_keep_order(refs)


def _source_blocks_for_refs(conn: psycopg.Connection, refs: list[str]) -> list[dict[str, str]]:
    if not refs:
        return []
    by_document: dict[str, list[str]] = {}
    for ref in refs:
        document_id, _sep, block_id = ref.partition(":")
        if document_id and block_id:
            by_document.setdefault(document_id, []).append(block_id)
    rows_out: list[dict[str, str]] = []
    for document_id, block_ids in by_document.items():
        rows = conn.execute(
            """
            SELECT block_id, text
            FROM source_blocks
            WHERE document_id = %s
              AND block_id = ANY(%s)
            ORDER BY block_id
            """,
            (document_id, block_ids),
        ).fetchall()
        for row in rows:
            rows_out.append({"ref": f"{document_id}:{row['block_id']}", "text": row["text"]})
    return rows_out


def _write_active_cluster_sections(active_path: str, sections: dict[str, str]) -> None:
    lines = ["# Active Meaning Clusters"]
    for cluster_id in sorted(sections):
        lines.append(sections[cluster_id].strip())
    write_text_object(active_path, "\n\n".join(lines).rstrip() + "\n")


def _append_archived_clusters(user_id: str, workspace_id: str, sections: list[str]) -> None:
    if not sections:
        return
    archive_path = f"wiki/{user_id}/{workspace_id}/clusters/archived.md"
    existing = _read_optional_text_object(archive_path)
    separator = "\n\n" if existing and not existing.endswith("\n\n") else ""
    write_text_object(archive_path, f"{existing}{separator}" + "\n\n".join(sections).rstrip() + "\n")


def _lint_log_markdown(result: dict[str, Any]) -> str:
    lines = [
        f"## {_today_iso()} lint: {result['workspace_id']}",
        "",
        f"user: {result['user_id']}",
        f"workspace: {result['workspace_id']}",
        f"active: {result['active_path']}",
        "",
        "### Orphan Risks",
    ]
    orphan_refs = result.get("orphan_refs", [])
    lines.extend(f"- {ref}" for ref in orphan_refs) if orphan_refs else lines.append("- orphan risk 없음")
    lines.extend(["", "### Promotion Queue"])
    promotions = result.get("promotion_candidates", [])
    lines.extend(f"- cluster:{cluster_id}" for cluster_id in promotions) if promotions else lines.append("- promotion candidate 없음")
    lines.extend(["", "### Needs Review"])
    needs_review = result.get("needs_review", [])
    lines.extend(f"- cluster:{cluster_id}" for cluster_id in needs_review) if needs_review else lines.append("- needs_review 없음")
    lines.extend(["", "### Relation Candidate Queue"])
    relation_candidates = result.get("relation_candidates", [])
    if relation_candidates:
        for item in relation_candidates:
            lines.append(f"- cluster:{item.get('cluster_id')} -> {item.get('target')}")
            lines.append(f"  relation: {item.get('relation')}")
            lines.append(f"  evidence: [{', '.join(item.get('evidence', []))}]")
    else:
        lines.append("- relation candidate 없음")
    lines.extend(["", "### Invalid Relation Candidates"])
    invalid_relations = result.get("invalid_relations", [])
    if invalid_relations:
        for item in invalid_relations:
            lines.append(f"- cluster:{item.get('cluster_id')} -> {item.get('target') or '-'}")
            lines.append(f"  relation: {item.get('relation') or '-'}")
            lines.append(f"  evidence: [{', '.join(item.get('evidence', []))}]")
            lines.append(f"  missing: [{', '.join(item.get('missing', []))}]")
    else:
        lines.append("- invalid relation candidate 없음")
    lines.extend(["", "### Invalid Promotions"])
    invalid_promotions = result.get("invalid_promotions", [])
    if invalid_promotions:
        for item in invalid_promotions:
            lines.append(f"- cluster:{item.get('cluster_id')}")
            lines.append(f"  reason: {item.get('reason')}")
    else:
        lines.append("- invalid promotion 없음")
    lines.extend(["", "### Materialized Changes"])
    materialized_promotions = result.get("materialized_promotions", [])
    merged_promotions = result.get("merged_promotions", [])
    materialized_relations = result.get("materialized_relations", [])
    if materialized_promotions:
        for item in materialized_promotions:
            lines.append(f"- promoted: cluster:{item.get('cluster_id')} -> concept:{item.get('concept_slug')}")
    if merged_promotions:
        for item in merged_promotions:
            lines.append(f"- merged: cluster:{item.get('cluster_id')} -> concept:{item.get('concept_slug')}")
    if materialized_relations:
        for item in materialized_relations:
            lines.append(f"- linked: concept:{item.get('from')} -[{item.get('relation')}]-> concept:{item.get('to')}")
    lines.append("- updated: logs/{yyyy-mm-dd}.md")
    return "\n".join(lines) + "\n"


def _markdown_section(markdown: str, heading: str) -> str:
    lines = _markdown_section_lines(markdown, heading)
    return "\n".join(line.strip() for line in lines if line.strip()).strip()


def _markdown_list_section(markdown: str, heading: str) -> list[str]:
    items = []
    for line in _markdown_section_lines(markdown, heading):
        stripped = line.strip()
        if not stripped or stripped == "-":
            continue
        if stripped.startswith("- "):
            stripped = stripped[2:].strip()
        if stripped and not stripped.startswith("-"):
            items.append(stripped)
    return items


def _markdown_section_lines(markdown: str, heading: str) -> list[str]:
    lines = []
    in_section = False
    heading_pattern = re.compile(rf"^##\s+{re.escape(heading)}\s*$", re.IGNORECASE)
    for line in markdown.splitlines():
        if heading_pattern.match(line.strip()):
            in_section = True
            continue
        if in_section and line.startswith("## "):
            break
        if in_section:
            lines.append(line)
    return lines


def _persist_wiki_outputs(conn: psycopg.Connection, document_id: str, manifest: dict[str, Any]) -> list[str]:
    normalized = manifest.get("normalized")
    if normalized is None:
        out_dir = Path(manifest["out"])
        normalized = json.loads((out_dir / "normalized.json").read_text(encoding="utf-8"))
    links = manifest.get("links")
    if isinstance(links, str):
        links = json.loads(Path(links).read_text(encoding="utf-8"))
    links = links or []
    user_id = str(manifest.get("user_id") or "local-user")
    workspace_id = str(manifest.get("workspace_id") or "local-workspace")
    persisted_page_ids: list[str] = []
    _persist_source_blocks(conn, document_id, manifest)

    source_slug = document_id
    source_page_id = _resolve_or_create_wiki_page_id(conn, user_id, workspace_id, "source", source_slug)
    source_page = _page_payload(manifest["source_page"])
    source_markdown = source_page["markdown"]
    source_markdown_uri = _upload_wiki_markdown(source_markdown, f"wiki/{user_id}/{workspace_id}/sources/{source_slug}.md")
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
        user_id,
        workspace_id,
    )
    persisted_page_ids.append(source_page_id)
    _upsert_document_wiki_link(conn, document_id, source_page_id, "source_of", 1.0)
    _persist_embedding_units(conn, source_page_id, document_id, source_markdown)

    concept_pages = [_page_payload(page) for page in manifest.get("concept_pages", [])]
    concept_pages_by_slug = {page["slug"]: page for page in concept_pages}
    generated_concept_slugs = set(concept_pages_by_slug)
    concept_id_by_slug: dict[str, str] = _load_existing_concept_ids_by_slug(conn, user_id, workspace_id)
    for concept in normalized.get("concept_ledger", []):
        slug = concept["slug"]
        if slug not in generated_concept_slugs:
            continue
        page_id = _resolve_or_create_wiki_page_id(conn, user_id, workspace_id, "concept", slug)
        concept_id_by_slug[slug] = page_id
        concept_page = concept_pages_by_slug[slug]
        concept_markdown = concept_page["markdown"]
        concept_markdown_uri = _upload_wiki_markdown(concept_markdown, f"wiki/{user_id}/{workspace_id}/concepts/{slug}.md")
        _upsert_wiki_page(
            conn,
            page_id,
            "concept",
            concept.get("title") or slug,
            slug,
            concept.get("definition") or concept.get("why_page_worthy") or "",
            concept_markdown_uri,
            user_id,
            workspace_id,
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
    _persist_meaning_cluster_artifacts(conn, document_id, manifest)
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
        text = block.get("text") or ""
        if not block_id:
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
    ref_pattern = r"(?:[A-Za-z0-9_.-]+:)?B\d{4}"
    for group in re.findall(rf"\[((?:{ref_pattern})(?:\s*,\s*(?:{ref_pattern}))*)\]", text):
        block_ids.extend(part.strip() for part in group.split(",") if part.strip())
    return list(dict.fromkeys(block_ids))


def _clean_unit_text(text: str) -> str:
    ref_pattern = r"(?:[A-Za-z0-9_.-]+:)?B\d{4}"
    cleaned = re.sub(rf"\s*\[(?:{ref_pattern})(?:\s*,\s*(?:{ref_pattern}))*\]", "", text)
    cleaned = re.sub(r"^[-*]\s+", "", cleaned.strip())
    cleaned = re.sub(r"^(?:\[[A-Za-z0-9_,\s-]+\]\s*)+", "", cleaned)
    return cleaned.strip()


def _hash_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _upload_wiki_markdown(markdown: str, object_name: str) -> str:
    return write_text_object(object_name, markdown)


def _persist_meaning_cluster_artifacts(conn: psycopg.Connection, document_id: str, manifest: dict[str, Any]) -> None:
    artifact = manifest.get("meaning_clusters")
    if not isinstance(artifact, dict):
        return
    user_id = str(manifest.get("user_id") or "local-user")
    workspace_id = str(manifest.get("workspace_id") or "local-workspace")
    _apply_concept_update_decisions(conn, document_id, user_id, workspace_id, artifact.get("concept_update_decisions", []))
    active_path = artifact.get("active_path")
    active_markdown = artifact.get("active_markdown")
    log_path = artifact.get("log_path")
    log_markdown = artifact.get("log_markdown")
    if isinstance(active_path, str) and isinstance(active_markdown, str):
        existing_active = _read_optional_text_object(active_path)
        merged_active = _merge_active_cluster_markdown(existing_active, active_markdown)
        active_uri = write_text_object(active_path, merged_active)
        artifact["active_uri"] = active_uri
    if isinstance(log_path, str) and isinstance(log_markdown, str):
        existing_log = _read_optional_text_object(log_path)
        separator = "\n" if existing_log and not existing_log.endswith("\n") else ""
        log_uri = write_text_object(log_path, f"{existing_log}{separator}{log_markdown}")
        artifact["log_uri"] = log_uri


def _read_optional_text_object(object_name: str) -> str:
    try:
        return read_text_object(object_name)
    except Exception:
        return ""


def _apply_concept_update_decisions(
    conn: psycopg.Connection,
    document_id: str,
    user_id: str,
    workspace_id: str,
    decisions: Any,
) -> None:
    if not isinstance(decisions, list):
        return
    by_concept: dict[str, list[dict[str, Any]]] = {}
    for decision in decisions:
        if not isinstance(decision, dict) or decision.get("decision") != "same_concept":
            continue
        concept_slug = str(decision.get("concept_slug") or "").strip()
        claim = str(decision.get("claim") or "").strip()
        refs = [str(ref) for ref in decision.get("refs", []) if ref]
        if not concept_slug or not claim:
            continue
        by_concept.setdefault(concept_slug, []).append(
            {
                "claim_id": decision.get("claim_id"),
                "claim": claim,
                "refs": refs,
            }
        )
    for concept_slug, updates in by_concept.items():
        row = conn.execute(
            """
            SELECT id, markdown_uri
            FROM wiki_pages
            WHERE page_type = 'concept'
              AND status = 'active'
              AND user_id = %s
              AND workspace_id = %s
              AND slug = %s
            """,
            (user_id, workspace_id, concept_slug),
        ).fetchone()
        if not row:
            continue
        markdown = _read_optional_text_object(row["markdown_uri"])
        if not markdown:
            continue
        updated_markdown = _append_concept_evidence(markdown, updates)
        if updated_markdown == markdown:
            continue
        write_text_object(row["markdown_uri"], updated_markdown)
        _persist_embedding_units(conn, row["id"], document_id, updated_markdown)


def _append_concept_evidence(markdown: str, updates: list[dict[str, Any]]) -> str:
    evidence_lines = [_concept_evidence_line(update) for update in updates]
    evidence_lines = [line for line in evidence_lines if line]
    if not evidence_lines:
        return markdown
    lines = markdown.splitlines()
    heading_index = next((index for index, line in enumerate(lines) if line.strip() == "## Evidence"), -1)
    if heading_index < 0:
        if lines and lines[-1].strip():
            lines.append("")
        lines.extend(["## Evidence", *evidence_lines])
        return "\n".join(lines).rstrip() + "\n"
    end_index = heading_index + 1
    while end_index < len(lines) and not lines[end_index].startswith("## "):
        end_index += 1
    existing = {line.strip() for line in lines[heading_index + 1 : end_index] if line.strip()}
    if "- 아직 연결된 evidence claim 없음" in existing:
        remove_index = next(
            (index for index in range(heading_index + 1, end_index) if lines[index].strip() == "- 아직 연결된 evidence claim 없음"),
            -1,
        )
        if remove_index >= 0:
            lines.pop(remove_index)
            end_index -= 1
            existing.remove("- 아직 연결된 evidence claim 없음")
    insert_at = end_index
    for evidence_line in evidence_lines:
        if evidence_line.strip() in existing:
            continue
        lines.insert(insert_at, evidence_line)
        insert_at += 1
        existing.add(evidence_line.strip())
    return "\n".join(lines).rstrip() + "\n"


def _concept_evidence_line(update: dict[str, Any]) -> str:
    claim = str(update.get("claim") or "").strip()
    if not claim:
        return ""
    refs = [str(ref) for ref in update.get("refs", []) if ref]
    suffix = f" [{', '.join(refs)}]" if refs else ""
    claim_id = str(update.get("claim_id") or "").strip()
    prefix = f"{claim_id}: " if claim_id else ""
    return f"- {prefix}{claim}{suffix}"


def _merge_active_cluster_markdown(existing: str, incoming: str) -> str:
    existing_sections = _cluster_sections_by_id(existing)
    incoming_sections = _cluster_sections_by_id(incoming)
    if not existing_sections:
        return incoming
    for cluster_id, section in incoming_sections.items():
        if cluster_id not in existing_sections:
            existing_sections[cluster_id] = section
            continue
        existing_sections[cluster_id] = _merge_cluster_section(existing_sections[cluster_id], section)
    lines = ["# Active Meaning Clusters"]
    for cluster_id in sorted(existing_sections):
        lines.append(existing_sections[cluster_id].strip())
    return "\n\n".join(lines).rstrip() + "\n"


def _cluster_sections_by_id(markdown: str) -> dict[str, str]:
    sections: dict[str, str] = {}
    current_id = ""
    current_lines: list[str] = []
    for line in markdown.splitlines():
        match = re.match(r"^## cluster:\s*(.+?)\s*$", line)
        if match:
            if current_id:
                sections[current_id] = "\n".join(current_lines).strip()
            current_id = match.group(1).strip()
            current_lines = [line]
            continue
        if current_id:
            current_lines.append(line)
    if current_id:
        sections[current_id] = "\n".join(current_lines).strip()
    return sections


def _merge_cluster_section(existing: str, incoming: str) -> str:
    existing_lines = existing.splitlines()
    incoming_lines = incoming.splitlines()
    merged_lines = list(existing_lines)
    existing_claims = {line.strip() for line in existing_lines if line.strip().startswith("- claim_") or line.strip().startswith("- ev_")}
    incoming_claims = [line for line in incoming_lines if line.strip().startswith("- claim_") or line.strip().startswith("- ev_")]
    insert_at = _claim_insert_index(merged_lines)
    for claim in incoming_claims:
        if claim.strip() in existing_claims:
            continue
        merged_lines.insert(insert_at, claim)
        insert_at += 1
        existing_claims.add(claim.strip())

    _merge_relation_candidate_section(merged_lines, incoming_lines)
    if "### Promotion" not in existing and "### Promotion" in incoming:
        promotion = _section_from_heading(incoming_lines, "### Promotion")
        if promotion:
            if merged_lines and merged_lines[-1].strip():
                merged_lines.append("")
            merged_lines.extend(promotion)
    return "\n".join(merged_lines).strip()


def _merge_heading_section(merged_lines: list[str], existing: str, incoming_lines: list[str], heading: str) -> None:
    incoming_section = _section_from_heading(incoming_lines, heading)
    if not incoming_section:
        return
    if heading not in existing:
        if merged_lines and merged_lines[-1].strip():
            merged_lines.append("")
        merged_lines.extend(incoming_section)
        return
    existing_entries = {line.strip() for line in merged_lines if line.strip()}
    insert_at = len(merged_lines)
    for index, line in enumerate(merged_lines):
        if line.strip() == heading:
            insert_at = index + 1
            while insert_at < len(merged_lines) and not merged_lines[insert_at].startswith("### "):
                insert_at += 1
            break
    for line in incoming_section[1:]:
        if line.strip() and line.strip() in existing_entries:
            continue
        merged_lines.insert(insert_at, line)
        insert_at += 1
        if line.strip():
            existing_entries.add(line.strip())


def _merge_relation_candidate_section(merged_lines: list[str], incoming_lines: list[str]) -> None:
    incoming_section = _section_from_heading(incoming_lines, "### Core Relation Candidates")
    if not incoming_section:
        return
    existing_relations, _existing_invalid = _cluster_relation_items("\n".join(merged_lines))
    incoming_relations, _incoming_invalid = _cluster_relation_items("\n".join(incoming_section))
    relations_by_key = {
        _relation_item_key(item): item
        for item in existing_relations
    }
    for item in incoming_relations:
        relations_by_key.setdefault(_relation_item_key(item), item)
    replacement = _relation_items_to_lines(list(relations_by_key.values()))
    _replace_heading_section(merged_lines, "### Core Relation Candidates", replacement)


def _relation_item_key(item: dict[str, Any]) -> tuple[str, str, tuple[str, ...]]:
    return (
        str(item.get("target") or ""),
        str(item.get("relation") or ""),
        tuple(sorted(str(evidence) for evidence in item.get("evidence", []))),
    )


def _relation_items_to_lines(items: list[dict[str, Any]]) -> list[str]:
    if not items:
        return []
    lines = ["### Core Relation Candidates"]
    for item in items:
        lines.extend(
            [
                f"- target: {item.get('target')}",
                f"  relation: {item.get('relation')}",
                f"  evidence: [{', '.join(item.get('evidence', []))}]",
                f"  reason: {item.get('reason') or '-'}",
            ]
        )
    return lines


def _replace_heading_section(lines: list[str], heading: str, replacement: list[str]) -> None:
    start = next((index for index, line in enumerate(lines) if line.strip() == heading), -1)
    if start >= 0:
        end = start + 1
        while end < len(lines) and not lines[end].startswith("### "):
            end += 1
        lines[start:end] = replacement
        return
    if not replacement:
        return
    insert_at = next((index for index, line in enumerate(lines) if line.strip() == "### Promotion"), len(lines))
    if insert_at > 0 and lines[insert_at - 1].strip():
        replacement = ["", *replacement]
    lines[insert_at:insert_at] = replacement


def _claim_insert_index(lines: list[str]) -> int:
    last_claim_index = -1
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("- claim_") or stripped.startswith("- ev_"):
            last_claim_index = index
    if last_claim_index >= 0:
        return last_claim_index + 1
    for index, line in enumerate(lines):
        if line.strip() == "### Evidence Claims":
            return index + 1
    return len(lines)


def _section_from_heading(lines: list[str], heading: str) -> list[str]:
    out: list[str] = []
    in_section = False
    for line in lines:
        if line.strip() == heading:
            in_section = True
        elif in_section and line.startswith("### "):
            break
        if in_section:
            out.append(line)
    return out


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
    meaning_clusters = stored.get("meaning_clusters")
    if isinstance(meaning_clusters, dict):
        stored["meaning_clusters"] = {
            key: value
            for key, value in meaning_clusters.items()
            if key not in {"active_markdown", "log_markdown", "clusters"}
        }
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


def _load_existing_concept_ids_by_slug(conn: psycopg.Connection, user_id: str, workspace_id: str) -> dict[str, str]:
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


def _resolve_or_create_wiki_page_id(
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


def _upsert_wiki_page(
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
        (page_id, page_type, title, slug, summary, markdown_uri, user_id, workspace_id),
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
