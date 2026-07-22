from __future__ import annotations

import os
import re
from datetime import date
from typing import Any, Callable

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Json

from app.core.error_text import truncate_error
from app.modules.wiki_ingestion.infrastructure.active_cluster_markdown import (
    MATERIALIZED_CORE_RELATIONS,
    cluster_relation_items as _cluster_relation_items,
    cluster_sections_by_id as _cluster_sections_by_id,
    parse_active_cluster_lint as _parse_active_cluster_lint,
    refs_in_text as _refs_in_text,
)
from app.modules.wiki_ingestion.infrastructure.concept_evidence import append_concept_evidence
from app.modules.wiki_ingestion.infrastructure.embedding_units import clean_unit_text as _clean_unit_text
from app.modules.wiki_ingestion.infrastructure.markdown_sections import (
    markdown_list_section as _markdown_list_section,
    markdown_section as _markdown_section,
)
from app.modules.wiki_ingestion.infrastructure.object_storage import write_text_object
from app.modules.wiki_ingestion.infrastructure.postgres_wiki_output_persistence import (
    persist_wiki_outputs as _persist_wiki_outputs,
)
from app.modules.wiki_ingestion.infrastructure.postgres_wiki_writer import (
    load_existing_concept_ids_by_slug as _load_existing_concept_ids_by_slug,
    persist_embedding_units as _persist_embedding_units,
    read_optional_text_object as _read_optional_text_object,
    refresh_source_related_links as _refresh_source_related_links,
    resolve_or_create_wiki_page_id as _resolve_or_create_wiki_page_id,
    upload_wiki_markdown as _upload_wiki_markdown,
    upsert_wiki_page as _upsert_wiki_page,
    upsert_wiki_page_link as _upsert_wiki_page_link,
)
from app.modules.wiki_ingestion.infrastructure.wiki_persistence_payload import (
    stored_manifest as _stored_manifest,
)
from app.modules.wiki_ingestion.infrastructure.wiki_lint_report import render_lint_log_markdown


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


REQUIRED_TABLES = (
    "documents",
    "wiki_pages",
    "document_wiki_links",
    "source_blocks",
    "wiki_page_links",
    "pipeline_runs",
    "wiki_page_embeddings",
    "wiki_embedding_vectors",
    "wiki_embedding_units",
    "wiki_schemas",
)


def verify_schema() -> None:
    """Flyway가 pipeline 필수 테이블을 모두 적용했는지 확인한다."""
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = current_schema()
              AND table_name = ANY(%s)
            """,
            (list(REQUIRED_TABLES),),
        ).fetchall()
    existing_tables = {row["table_name"] for row in rows}
    missing_tables = sorted(set(REQUIRED_TABLES) - existing_tables)
    if missing_tables:
        missing = ", ".join(missing_tables)
        raise RuntimeError(f"Flyway migration is required; missing tables: {missing}")


def get_document(document_id: str) -> dict | None:
    with connect() as conn:
        row = conn.execute(
            """
            SELECT id, user_id, workspace_id, filename, mime_type, byte_size, status, source_uri, extracted_text_uri,
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
            SET status = 'succeeded', manifest = %s,
                updated_at = now(), finished_at = now()
            WHERE id = %s
            """,
            (Json(manifest), run_id),
        )
    return embedded_page_ids


def fail_pipeline_run(run_id: str, error: str) -> None:
    error_message = truncate_error(error)
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
            SET status = 'failed', error = %s,
                updated_at = now(), finished_at = now()
            WHERE id = %s
            """,
            (error_message, run_id),
        )


def touch_pipeline_run(run_id: str) -> None:
    with connect() as conn:
        conn.execute(
            """
            UPDATE pipeline_runs
            SET updated_at = now()
            WHERE id = %s AND status = 'running'
            """,
            (run_id,),
        )


def get_pipeline_run(run_id: str) -> dict | None:
    with connect() as conn:
        row = conn.execute(
            """
            SELECT id, document_id, input_source, output_dir, mode, status,
                   manifest, error, created_at, updated_at, finished_at
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


def latest_source_extraction_artifact(document_id: str) -> dict[str, Any] | None:
    with connect() as conn:
        row = conn.execute(
            """
            SELECT manifest
            FROM pipeline_runs
            WHERE document_id = %s
              AND status = 'succeeded'
              AND manifest ? 'source_extraction_artifact'
            ORDER BY finished_at DESC NULLS LAST, created_at DESC
            LIMIT 1
            """,
            (document_id,),
        ).fetchone()
    if not row:
        return None
    manifest = row.get("manifest") or {}
    artifact = manifest.get("source_extraction_artifact")
    return artifact if isinstance(artifact, dict) else None


def latest_source_page_context(
    document_id: str,
    user_id: str = "local-user",
    workspace_id: str = "local-workspace",
) -> dict[str, Any] | None:
    artifact = latest_source_extraction_artifact(document_id)
    if artifact is None:
        return None
    with connect() as conn:
        row = conn.execute(
            """
            SELECT markdown_uri
            FROM wiki_pages
            WHERE page_type = 'source'
              AND slug = %s
              AND status = 'active'
              AND user_id = %s
              AND workspace_id = %s
            ORDER BY updated_at DESC
            LIMIT 1
            """,
            (document_id, user_id, workspace_id),
        ).fetchone()
    if not row:
        return None
    source_markdown = _read_optional_text_object(row["markdown_uri"])
    return {
        "artifact": artifact,
        "source_markdown": source_markdown,
    }


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
    write_log: bool = True,
) -> dict[str, Any]:
    active_path = f"wiki/{user_id}/{workspace_id}/clusters/active.md"
    lint_date = _today_iso()
    log_path = f"wiki/{user_id}/{workspace_id}/logs/{lint_date}.md"
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
    if write_log:
        existing_log = _read_optional_text_object(log_path)
        separator = "\n" if existing_log and not existing_log.endswith("\n") else ""
        write_text_object(log_path, f"{existing_log}{separator}{render_lint_log_markdown(result, lint_date)}")
    return result


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
    updated_markdown = append_concept_evidence(markdown, updates)
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
