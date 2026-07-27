from __future__ import annotations

from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Json

from app.modules.wiki_ingestion.infrastructure.active_cluster_markdown import (
    parse_active_cluster_lint,
)


def persist_source_contribution(
    conn: psycopg.Connection,
    run_id: str,
    document_id: str,
    manifest: dict[str, Any],
) -> None:
    payload = _contribution_payload(manifest)
    conn.execute(
        """
        UPDATE wiki_source_contributions
        SET active = false
        WHERE document_id = %s
          AND active
        """,
        (document_id,),
    )
    conn.execute(
        """
        INSERT INTO wiki_source_contributions (
            pipeline_run_id,
            document_id,
            user_id,
            workspace_id,
            payload,
            active
        )
        VALUES (%s, %s, %s, %s, %s, true)
        """,
        (
            run_id,
            document_id,
            str(manifest.get("user_id") or "local-user"),
            str(manifest.get("workspace_id") or "local-workspace"),
            Json(payload),
        ),
    )


def list_reconciliation_candidates(
    conn: psycopg.Connection,
    user_id: str,
    workspace_id: str,
) -> list[dict[str, Any]]:
    rows = conn.execute(
        """
        SELECT current_contribution.pipeline_run_id,
               current_contribution.document_id,
               current_contribution.user_id,
               current_contribution.workspace_id,
               current_contribution.payload,
               current_contribution.structural_reconciled_at,
               COALESCE(previous.payloads, '[]'::jsonb) AS previous_payloads,
               COALESCE(linked_concepts.slugs, ARRAY[]::text[]) AS linked_concept_slugs
        FROM wiki_source_contributions current_contribution
        LEFT JOIN LATERAL (
            SELECT jsonb_agg(payload ORDER BY created_at) AS payloads
            FROM wiki_source_contributions previous
            WHERE previous.document_id = current_contribution.document_id
              AND previous.pipeline_run_id <> current_contribution.pipeline_run_id
              AND NOT previous.active
        ) previous ON true
        LEFT JOIN LATERAL (
            SELECT array_agg(page.slug ORDER BY page.slug) AS slugs
            FROM document_wiki_links link
            JOIN wiki_pages page ON page.id = link.wiki_page_id
            WHERE link.document_id = current_contribution.document_id
              AND link.relation_type = 'extracted_concept'
              AND page.page_type = 'concept'
        ) linked_concepts ON true
        WHERE current_contribution.user_id = %s
          AND current_contribution.workspace_id = %s
          AND current_contribution.active
          AND jsonb_array_length(
              COALESCE(
                  current_contribution.payload->'source_block_changes'->'invalidated_block_ids',
                  '[]'::jsonb
              )
          ) > 0
        ORDER BY current_contribution.created_at,
                 current_contribution.pipeline_run_id
        """,
        (user_id, workspace_id),
    ).fetchall()
    return [_reconciliation_candidate(row) for row in rows]


def apply_structural_reconciliation(
    conn: psycopg.Connection,
    candidates: list[dict[str, Any]],
    active_relation_keys: set[tuple[str, str, str]],
) -> list[dict[str, Any]]:
    applied: list[dict[str, Any]] = []
    for candidate in candidates:
        if candidate["structural_reconciled"]:
            continue
        removed_concepts, removed_embedding_units = _remove_stale_document_concepts(
            conn,
            candidate["document_id"],
            candidate["stale_concept_slugs"],
        )
        removed_relations = _remove_stale_relations(
            conn,
            candidate["stale_relations"],
            active_relation_keys,
            candidate["user_id"],
            candidate["workspace_id"],
        )
        conn.execute(
            """
            UPDATE wiki_source_contributions
            SET structural_reconciled_at = now()
            WHERE pipeline_run_id = %s
            """,
            (candidate["pipeline_run_id"],),
        )
        applied.append(
            {
                "pipeline_run_id": candidate["pipeline_run_id"],
                "document_id": candidate["document_id"],
                "removed_concept_slugs": removed_concepts,
                "removed_embedding_unit_count": removed_embedding_units,
                "removed_relations": removed_relations,
            }
        )
    return applied


def active_relation_keys(
    conn: psycopg.Connection,
    user_id: str,
    workspace_id: str,
) -> set[tuple[str, str, str]]:
    rows = conn.execute(
        """
        SELECT payload
        FROM wiki_source_contributions
        WHERE user_id = %s
          AND workspace_id = %s
          AND active
        """,
        (user_id, workspace_id),
    ).fetchall()
    return {
        _relation_key(relation)
        for row in rows
        for relation in _relations(row.get("payload") or {})
    }


def _contribution_payload(manifest: dict[str, Any]) -> dict[str, Any]:
    concept_slugs = []
    for page in manifest.get("concept_pages", []):
        if isinstance(page, dict) and page.get("slug"):
            concept_slugs.append(str(page["slug"]))
        elif isinstance(page, (str, Path)):
            concept_slugs.append(Path(page).stem)
    meaning_clusters = manifest.get("meaning_clusters")
    return {
        "concept_slugs": list(dict.fromkeys(concept_slugs)),
        "links": _manifest_links(manifest),
        "source_block_changes": manifest.get("source_block_changes") or {},
        "active_cluster_markdown": (
            meaning_clusters.get("active_markdown", "")
            if isinstance(meaning_clusters, dict)
            else ""
        ),
    }


def _manifest_links(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    links = manifest.get("links")
    if not isinstance(links, list):
        return []
    return [
        {
            "source": str(link.get("source") or ""),
            "target": str(link.get("target") or ""),
            "relation": str(link.get("relation") or "related_to"),
        }
        for link in links
        if isinstance(link, dict)
    ]


def _reconciliation_candidate(row: dict[str, Any]) -> dict[str, Any]:
    payload = row.get("payload") or {}
    previous_payloads = row.get("previous_payloads") or []
    document_id = str(row["document_id"])
    invalidated_ids = (
        payload.get("source_block_changes", {}).get("invalidated_block_ids", [])
    )
    current_concepts = set(payload.get("concept_slugs", []))
    previous_concepts = set(row.get("linked_concept_slugs", []))
    current_relation_keys = {_relation_key(item) for item in _relations(payload)}
    stale_relations_by_key = {
        _relation_key(item): item
        for previous_payload in previous_payloads
        if isinstance(previous_payload, dict)
        for item in _relations(previous_payload)
        if _relation_key(item) not in current_relation_keys
    }
    return {
        "pipeline_run_id": str(row["pipeline_run_id"]),
        "document_id": document_id,
        "user_id": str(row["user_id"]),
        "workspace_id": str(row["workspace_id"]),
        "invalidated_source_refs": [
            f"{document_id}:{block_id}" for block_id in invalidated_ids
        ],
        "stale_concept_slugs": sorted(previous_concepts - current_concepts),
        "stale_relations": list(stale_relations_by_key.values()),
        "structural_reconciled": row.get("structural_reconciled_at") is not None,
        "_current_claim_signatures": _claim_signatures(
            payload.get("active_cluster_markdown", "")
        ),
        "_current_relation_signatures": _relation_signatures(
            payload.get("active_cluster_markdown", "")
        ),
    }


def _relations(payload: dict[str, Any]) -> list[dict[str, str]]:
    links = payload.get("links", [])
    return [item for item in links if isinstance(item, dict)]


def _relation_key(relation: dict[str, Any]) -> tuple[str, str, str]:
    return (
        str(relation.get("source") or ""),
        str(relation.get("target") or ""),
        str(relation.get("relation") or "related_to"),
    )


def _claim_signatures(markdown: Any) -> list[list[str]]:
    if not isinstance(markdown, str):
        return []
    return [
        [cluster["id"], str(claim.get("id") or ""), str(claim.get("text") or "")]
        for cluster in parse_active_cluster_lint(markdown)
        for claim in cluster.get("claims", [])
    ]


def _relation_signatures(markdown: Any) -> list[list[Any]]:
    if not isinstance(markdown, str):
        return []
    return [
        [
            cluster["id"],
            str(relation.get("target") or ""),
            str(relation.get("relation") or ""),
            [str(item) for item in relation.get("evidence", [])],
        ]
        for cluster in parse_active_cluster_lint(markdown)
        for relation in cluster.get("relations", [])
    ]


def _remove_stale_document_concepts(
    conn: psycopg.Connection,
    document_id: str,
    concept_slugs: list[str],
) -> tuple[list[str], int]:
    if not concept_slugs:
        return [], 0
    rows = conn.execute(
        """
        DELETE FROM document_wiki_links link
        USING wiki_pages page
        WHERE link.document_id = %s
          AND link.relation_type = 'extracted_concept'
          AND page.id = link.wiki_page_id
          AND page.page_type = 'concept'
          AND page.slug = ANY(%s)
        RETURNING page.id, page.slug
        """,
        (document_id, concept_slugs),
    ).fetchall()
    page_ids = [str(row["id"]) for row in rows]
    removed_embedding_units = 0
    if page_ids:
        result = conn.execute(
            """
            DELETE FROM wiki_embedding_units
            WHERE source_document_id = %s
              AND page_id = ANY(%s)
            """,
            (document_id, page_ids),
        )
        removed_embedding_units = result.rowcount
    return (
        sorted(str(row["slug"]) for row in rows),
        removed_embedding_units,
    )


def _remove_stale_relations(
    conn: psycopg.Connection,
    relations: list[dict[str, str]],
    active_keys: set[tuple[str, str, str]],
    user_id: str,
    workspace_id: str,
) -> list[dict[str, str]]:
    removed: list[dict[str, str]] = []
    for relation in relations:
        if _relation_key(relation) in active_keys:
            continue
        row = conn.execute(
            """
            DELETE FROM wiki_page_links link
            USING wiki_pages source_page, wiki_pages target_page
            WHERE source_page.id = link.from_page_id
              AND target_page.id = link.to_page_id
              AND source_page.page_type = split_part(%s, ':', 1)
              AND source_page.slug = split_part(%s, ':', 2)
              AND target_page.page_type = split_part(%s, ':', 1)
              AND target_page.slug = split_part(%s, ':', 2)
              AND link.link_type = %s
              AND source_page.user_id = %s
              AND source_page.workspace_id = %s
              AND target_page.user_id = %s
              AND target_page.workspace_id = %s
            RETURNING link.from_page_id
            """,
            (
                relation["source"],
                relation["source"],
                relation["target"],
                relation["target"],
                relation["relation"],
                user_id,
                workspace_id,
                user_id,
                workspace_id,
            ),
        ).fetchone()
        if row:
            removed.append(relation)
    return removed
