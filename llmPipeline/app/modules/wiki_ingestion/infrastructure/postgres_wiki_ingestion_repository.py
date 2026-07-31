from __future__ import annotations

import json
import os
import re
from contextlib import nullcontext
from datetime import date
from typing import Any, Callable

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Json

from app.core.error_text import truncate_error
from app.core.pipeline_control import PipelineRunCancelledError
from app.modules.wiki_ingestion.infrastructure.active_cluster_markdown import (
    MATERIALIZED_CORE_RELATIONS,
    cluster_relation_items as _cluster_relation_items,
    cluster_sections_by_id as _cluster_sections_by_id,
    parse_active_cluster_lint as _parse_active_cluster_lint,
    reconcile_active_cluster_invalidations as _reconcile_active_cluster_invalidations,
    refs_in_text as _refs_in_text,
)
from app.modules.wiki_ingestion.infrastructure.concept_evidence import append_concept_evidence
from app.modules.wiki_ingestion.infrastructure.embedding_units import clean_unit_text as _clean_unit_text
from app.modules.wiki_ingestion.infrastructure.markdown_sections import (
    markdown_list_section as _markdown_list_section,
    markdown_section as _markdown_section,
)
from app.modules.wiki_ingestion.infrastructure.lint_operation_artifacts import (
    persist_lint_operation_artifacts,
)
from app.modules.wiki_ingestion.domain.orphan_link_lint import find_orphan_links
from app.modules.wiki_ingestion.infrastructure.object_storage import (
    read_text_object,
    write_text_object,
)
from app.modules.wiki_ingestion.infrastructure.postgres_wiki_output_persistence import (
    persist_wiki_outputs as _persist_wiki_outputs,
)
from app.modules.wiki_ingestion.infrastructure.source_contribution_reconciliation import (
    active_relation_keys as _active_relation_keys,
    apply_structural_reconciliation as _apply_structural_reconciliation,
    list_reconciliation_candidates as _list_reconciliation_candidates,
    _remove_stale_relations,
    source_contribution_payload as _source_contribution_payload,
)
from app.modules.wiki_ingestion.infrastructure.postgres_wiki_writer import (
    delete_source_related_links as _delete_source_related_links,
    load_existing_concept_ids_by_slug as _load_existing_concept_ids_by_slug,
    persist_embedding_units as _persist_embedding_units,
    read_optional_text_object as _read_optional_text_object,
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
    "wiki_page_contributions",
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
            active_document = conn.execute(
                """
                SELECT d.id
                FROM documents d
                JOIN workspaces w ON w.id = d.workspace_id
                WHERE d.id = %s
                  AND d.deleted_at IS NULL
                  AND w.deleted_at IS NULL
                FOR SHARE OF d, w
                """,
                (document_id,),
            ).fetchone()
            if active_document is None:
                raise PipelineRunCancelledError(
                    "Pipeline run cancelled because its document or workspace is inactive."
                )
            embedded_page_ids = _persist_wiki_outputs(conn, document_id, manifest)
            manifest["source_contribution"] = _source_contribution_payload(
                manifest
            )
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
                  AND deleted_at IS NULL
                  AND EXISTS (
                      SELECT 1
                      FROM workspaces w
                      WHERE w.id = documents.workspace_id
                        AND w.deleted_at IS NULL
                  )
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


def mark_pipeline_notification_pending(run_id: str, error: str) -> None:
    with connect() as conn:
        conn.execute(
            """
            UPDATE pipeline_runs
            SET status = 'notify_pending', error = %s,
                updated_at = now(), finished_at = now()
            WHERE id = %s
            """,
            (truncate_error(error), run_id),
        )


def touch_pipeline_run(run_id: str) -> bool:
    with connect() as conn:
        row = conn.execute(
            """
            UPDATE pipeline_runs pr
            SET updated_at = now()
            WHERE pr.id = %s
              AND pr.status = 'running'
              AND (
                  pr.document_id IS NULL
                  OR EXISTS (
                      SELECT 1
                      FROM documents d
                      JOIN workspaces w ON w.id = d.workspace_id
                      WHERE d.id = pr.document_id
                        AND d.deleted_at IS NULL
                        AND w.deleted_at IS NULL
                  )
              )
            RETURNING pr.id
            """,
            (run_id,),
        ).fetchone()
    return row is not None


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


def list_source_blocks(document_id: str) -> list[dict[str, Any]]:
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT document_id, block_id, text
            FROM source_blocks
            WHERE document_id = %s
            ORDER BY block_id
            """,
            (document_id,),
        ).fetchall()
    return [dict(row) for row in rows]


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
    apply_reconciliation: bool = False,
    write_log: bool = True,
    connection: psycopg.Connection | None = None,
) -> dict[str, Any]:
    active_path = f"wiki/{user_id}/{workspace_id}/clusters/active.md"
    lint_date = _today_iso()
    log_path = f"wiki/{user_id}/{workspace_id}/logs/{lint_date}.md"
    active_markdown = _read_optional_text_object(active_path)
    with _connection_scope(connection) as conn:
        reconciliation_candidates = _list_reconciliation_candidates(
            conn,
            user_id,
            workspace_id,
        )
    stale_source_refs = {
        ref
        for candidate in reconciliation_candidates
        for ref in candidate["invalidated_source_refs"]
    }
    cluster_reconciliation_source_refs = {
        ref
        for candidate in reconciliation_candidates
        if candidate.get("_cluster_reconciliation_ready", False)
        for ref in candidate["invalidated_source_refs"]
    }
    current_claim_signatures = {
        tuple(signature)
        for candidate in reconciliation_candidates
        for signature in candidate["_current_claim_signatures"]
    }
    current_relation_signatures = {
        (
            str(signature[0]),
            str(signature[1]),
            str(signature[2]),
            tuple(str(item) for item in signature[3]),
        )
        for candidate in reconciliation_candidates
        for signature in candidate["_current_relation_signatures"]
    }
    applied_cluster_reconciliation = {
        "removed_claims": [],
        "removed_relations": [],
    }
    if apply_reconciliation:
        (
            reconciled_markdown,
            removed_claims,
            removed_relations,
        ) = _reconcile_active_cluster_invalidations(
            active_markdown,
            cluster_reconciliation_source_refs,
            current_claim_signatures,
            current_relation_signatures,
        )
        if reconciled_markdown != active_markdown:
            write_text_object(active_path, reconciled_markdown)
            active_markdown = reconciled_markdown
        applied_cluster_reconciliation = {
            "removed_claims": removed_claims,
            "removed_relations": removed_relations,
        }
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
    blocked_cluster_ids = {
        cluster["id"]
        for cluster in clusters
        for claim in cluster.get("claims", [])
        if stale_source_refs.intersection(claim.get("refs", []))
        and (
            cluster["id"],
            str(claim.get("id") or ""),
            str(claim.get("text") or ""),
        )
        not in current_claim_signatures
    }
    promotion_candidates = [
        cluster["id"]
        for cluster in clusters
        if cluster.get("promotion_status") == "candidate" and cluster.get("promotion_source_refs")
        and cluster["id"] not in blocked_cluster_ids
    ]
    needs_review = [
        cluster["id"]
        for cluster in clusters
        if cluster.get("promotion_status") == "needs_review"
        or any(claim.get("decision") == "needs_review" for claim in cluster.get("claims", []))
        or cluster["id"] in blocked_cluster_ids
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
        "reconciliation_candidates": [
            {
                key: value
                for key, value in candidate.items()
                if key not in {"user_id", "workspace_id"}
                and not key.startswith("_")
            }
            for candidate in reconciliation_candidates
        ],
        "applied_reconciliations": [],
        "applied_cluster_reconciliation": applied_cluster_reconciliation,
        "materialized_promotions": [],
        "merged_promotions": [],
        "materialized_relations": [],
    }
    if apply_reconciliation:
        with _connection_scope(connection) as conn:
            result["applied_reconciliations"] = _apply_structural_reconciliation(
                conn,
                reconciliation_candidates,
                _active_relation_keys(conn, user_id, workspace_id),
            )
    if materialize_promotions and promotion_page_generator is not None:
        with _connection_scope(connection) as conn:
            materialized = _materialize_promotion_candidates(
                conn,
                user_id,
                workspace_id,
                [
                    cluster
                    for cluster in clusters
                    if cluster["id"] not in blocked_cluster_ids
                ],
                active_markdown,
                active_path,
                promotion_page_generator,
            )
        result["materialized_promotions"] = materialized["promotions"]
        result["merged_promotions"] = materialized["merged_promotions"]
        result["materialized_relations"] = materialized["relations"]
        result["_lint_page_changes"] = materialized["page_changes"]
        result["active_path"] = active_path
    if write_log:
        write_wiki_lint_log(result, lint_date)
    return result


def lint_orphan_wiki_links(
    user_id: str,
    workspace_id: str,
    *,
    apply: bool,
    connection: psycopg.Connection | None = None,
) -> dict[str, list[dict[str, Any]]]:
    with _connection_scope(connection) as conn:
        contribution_rows = conn.execute(
            """
            SELECT contribution.active,
                   contribution.object_key
            FROM wiki_page_contributions contribution
            JOIN wiki_pages page ON page.id = contribution.page_id
            WHERE contribution.object_key IS NOT NULL
              AND page.page_type = 'concept'
              AND page.user_id = %s
              AND page.workspace_id = %s
            ORDER BY contribution.sequence_revision
            """,
            (user_id, workspace_id),
        ).fetchall()
        link_rows = conn.execute(
            """
            SELECT concat(source_page.page_type, ':', source_page.slug) AS source,
                   concat(target_page.page_type, ':', target_page.slug) AS target,
                   link.link_type AS relation,
                   source_page.status AS source_status,
                   target_page.status AS target_status
            FROM wiki_page_links link
            JOIN wiki_pages source_page ON source_page.id = link.from_page_id
            JOIN wiki_pages target_page ON target_page.id = link.to_page_id
            WHERE source_page.user_id = %s
              AND source_page.workspace_id = %s
              AND target_page.user_id = %s
              AND target_page.workspace_id = %s
            """,
            (user_id, workspace_id, user_id, workspace_id),
        ).fetchall()

        managed_contributions = []
        active_contributions = []
        for row in contribution_rows:
            payload = json.loads(read_text_object(str(row["object_key"])))
            if not isinstance(payload, dict):
                raise RuntimeError(
                    "concept contribution log must be a JSON object"
                )
            managed_contributions.append(payload)
            if bool(row["active"]):
                active_contributions.append(payload)

        current_links = [
            {
                "source": str(row["source"]),
                "target": str(row["target"]),
                "relation": str(row["relation"]),
            }
            for row in link_rows
        ]
        deleted_page_refs = {
            str(row[key])
            for row in link_rows
            for key, status_key in (
                ("source", "source_status"),
                ("target", "target_status"),
            )
            if str(row[status_key]) != "active"
        }
        candidates = find_orphan_links(
            current_links=current_links,
            active_contribution_json=active_contributions,
            managed_contribution_json=managed_contributions,
            deleted_page_refs=deleted_page_refs,
        )
        removed = (
            _remove_stale_relations(
                conn,
                candidates,
                set(),
                user_id,
                workspace_id,
            )
            if apply
            else []
        )
    return {
        "orphan_link_candidates": candidates,
        "removed_orphan_links": removed,
    }


def write_wiki_lint_log(
    result: dict[str, Any],
    lint_date: str | None = None,
) -> None:
    date_value = lint_date or _today_iso()
    log_path = (
        f"wiki/{result['user_id']}/{result['workspace_id']}/logs/"
        f"{date_value}.md"
    )
    existing_log = _read_optional_text_object(log_path)
    separator = "\n" if existing_log and not existing_log.endswith("\n") else ""
    write_text_object(
        log_path,
        f"{existing_log}{separator}{render_lint_log_markdown(result, date_value)}",
    )


def persist_lint_operation_result(
    user_id: str,
    workspace_id: str,
    operation_id: str,
    result: dict[str, Any],
    connection: psycopg.Connection | None = None,
) -> list[dict[str, Any]]:
    page_changes = {
        str(change["slug"]): dict(change)
        for change in result.pop("_lint_page_changes", [])
    }
    added_links = [
        _normalized_relation_link(item)
        for item in result.get("materialized_relations", [])
    ]
    removed_links = [
        _normalized_relation_link(item)
        for reconciliation in result.get("applied_reconciliations", [])
        for item in reconciliation.get("removed_relations", [])
    ]
    removed_links.extend(
        _normalized_relation_link(item)
        for item in result.get("removed_orphan_links", [])
    )
    links_by_source: dict[str, dict[str, list[dict[str, Any]]]] = {}
    for field, links in (
        ("added_links", added_links),
        ("removed_links", removed_links),
    ):
        for link in links:
            source = str(link.get("source") or "")
            if not source.startswith("concept:"):
                continue
            slug = source.split(":", 1)[1]
            links_by_source.setdefault(
                slug,
                {"added_links": [], "removed_links": []},
            )[field].append(link)

    missing_slugs = [slug for slug in links_by_source if slug not in page_changes]
    if missing_slugs:
        with _connection_scope(connection) as conn:
            rows = conn.execute(
                """
                SELECT id, slug, title, summary, markdown_uri
                FROM wiki_pages
                WHERE page_type = 'concept'
                  AND user_id = %s
                  AND workspace_id = %s
                  AND slug = ANY(%s)
                """,
                (user_id, workspace_id, missing_slugs),
            ).fetchall()
        for row in rows:
            markdown = _read_optional_text_object(str(row["markdown_uri"]))
            page_changes[str(row["slug"])] = {
                "page_id": str(row["id"]),
                "slug": str(row["slug"]),
                "title": str(row["title"]),
                "definition": str(row.get("summary") or ""),
                "markdown": markdown,
                "content_action": "none",
                "claims": [],
            }

    for slug, link_actions in links_by_source.items():
        change = page_changes.get(slug)
        if change is None:
            raise RuntimeError(
                f"failed to resolve lint operation source page: concept:{slug}"
            )
        change.update(link_actions)

    return persist_lint_operation_artifacts(
        operation_id=operation_id,
        workspace_id=workspace_id,
        page_changes=list(page_changes.values()),
        write_text=write_text_object,
    )


def _connection_scope(connection: psycopg.Connection | None):
    return nullcontext(connection) if connection is not None else connect()


def _normalized_relation_link(item: dict[str, Any]) -> dict[str, Any]:
    source = str(item.get("source") or item.get("from") or "")
    target = str(item.get("target") or item.get("to") or "")
    return {
        "source": source if ":" in source else f"concept:{source}",
        "target": target if ":" in target else f"concept:{target}",
        "relation": str(item.get("relation") or "related_to"),
    }


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
    page_changes: list[dict[str, Any]] = []
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
            merged_page = _merge_promotion_into_existing_concept(
                conn,
                user_id,
                workspace_id,
                cluster_id,
                claims,
            )
            if merged_page is not None:
                merged_promotions.append(
                    {
                        "cluster_id": cluster_id,
                        "concept_slug": cluster_id,
                        "page_id": existing_concept_ids[cluster_id],
                    }
                )
                page_changes.append(merged_page)
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
        page_changes.append(
            {
                "page_id": page_id,
                "slug": slug,
                "title": title,
                "definition": _markdown_section(markdown, "Definition"),
                "markdown": markdown + "\n",
                "content_action": "create",
                "claims": claims,
            }
        )
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
        "page_changes": page_changes,
    }


def _merge_promotion_into_existing_concept(
    conn: psycopg.Connection,
    user_id: str,
    workspace_id: str,
    concept_slug: str,
    claims: list[dict[str, Any]],
) -> dict[str, Any] | None:
    row = conn.execute(
        """
        SELECT id, title, summary, markdown_uri
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
        return None
    markdown = _read_optional_text_object(row["markdown_uri"])
    if not markdown:
        return None
    updates = [
        {
            "claim_id": claim.get("id"),
            "claim": claim.get("claim") or claim.get("text"),
            "refs": claim.get("refs", []),
        }
        for claim in claims
    ]
    updated_markdown = append_concept_evidence(markdown, updates)
    current_markdown_key = (
        f"wiki/{user_id}/{workspace_id}/concepts/{concept_slug}.md"
    )
    current_markdown_uri = write_text_object(
        current_markdown_key,
        updated_markdown,
    )
    _upsert_wiki_page(
        conn,
        str(row["id"]),
        "concept",
        str(row.get("title") or concept_slug),
        concept_slug,
        str(row.get("summary") or ""),
        current_markdown_uri,
        user_id,
        workspace_id,
    )
    if updated_markdown != markdown:
        first_ref = next(
            (ref for claim in claims for ref in claim.get("refs", [])),
            "",
        )
        document_id = first_ref.split(":", 1)[0] if first_ref else ""
        _persist_embedding_units(conn, row["id"], document_id, updated_markdown)
    return {
        "page_id": str(row["id"]),
        "slug": concept_slug,
        "title": str(row.get("title") or concept_slug),
        "definition": str(row.get("summary") or ""),
        "markdown": updated_markdown,
        "content_action": "append_evidence",
        "claims": claims,
    }


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
