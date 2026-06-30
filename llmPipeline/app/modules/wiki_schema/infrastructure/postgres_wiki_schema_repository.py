from __future__ import annotations

from typing import Any

from psycopg.types.json import Json

from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database
from app.modules.wiki_schema.application.ports import WikiSchemaRepositoryPort
from app.modules.wiki_schema.domain.entities import SchemaFragments, SchemaIssue, WikiSchemaRecord


class PostgresWikiSchemaRepository(WikiSchemaRepositoryPort):
    def save(self, record: WikiSchemaRecord) -> WikiSchemaRecord:
        with database.connect() as conn:
            row = conn.execute(
                """
                INSERT INTO wiki_schemas (
                    id,
                    project_id,
                    name,
                    raw_markdown,
                    sanitized_global_markdown,
                    sanitized_query_markdown,
                    sanitized_ingest_markdown,
                    sanitized_edit_markdown,
                    sanitized_concept_markdown,
                    sanitized_template_markdown,
                    preview_markdown,
                    lint_result,
                    status,
                    schema_version
                )
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                RETURNING *
                """,
                (
                    record.id,
                    record.project_id,
                    record.name,
                    record.raw_markdown,
                    record.fragments.global_markdown,
                    record.fragments.query_markdown,
                    record.fragments.ingest_markdown,
                    record.fragments.edit_markdown,
                    record.fragments.concept_markdown,
                    record.fragments.template_markdown,
                    record.preview_markdown,
                    Json({"issues": [_issue_to_json(issue) for issue in record.issues]}),
                    record.status,
                    record.schema_version,
                ),
            ).fetchone()
        return _row_to_record(row)

    def get(self, schema_id: str) -> WikiSchemaRecord | None:
        with database.connect() as conn:
            row = conn.execute("SELECT * FROM wiki_schemas WHERE id = %s", (schema_id,)).fetchone()
        return _row_to_record(row) if row else None

    def activate(self, schema_id: str) -> WikiSchemaRecord:
        with database.connect() as conn:
            row = conn.execute("SELECT project_id FROM wiki_schemas WHERE id = %s", (schema_id,)).fetchone()
            if not row:
                raise ValueError("Schema not found.")
            project_id = row["project_id"]
            conn.execute(
                """
                UPDATE wiki_schemas
                SET status = 'draft', updated_at = now(), activated_at = NULL
                WHERE project_id = %s AND status = 'active'
                """,
                (project_id,),
            )
            activated = conn.execute(
                """
                UPDATE wiki_schemas
                SET status = 'active', updated_at = now(), activated_at = now()
                WHERE id = %s
                RETURNING *
                """,
                (schema_id,),
            ).fetchone()
        return _row_to_record(activated)

    def get_active(self, project_id: str) -> WikiSchemaRecord | None:
        with database.connect() as conn:
            row = conn.execute(
                """
                SELECT *
                FROM wiki_schemas
                WHERE project_id = %s AND status = 'active'
                ORDER BY activated_at DESC NULLS LAST, updated_at DESC
                LIMIT 1
                """,
                (project_id,),
            ).fetchone()
        return _row_to_record(row) if row else None


def init_db() -> None:
    with database.connect() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS wiki_schemas (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                name TEXT NOT NULL,
                raw_markdown TEXT NOT NULL,
                sanitized_global_markdown TEXT NOT NULL DEFAULT '',
                sanitized_query_markdown TEXT NOT NULL DEFAULT '',
                sanitized_ingest_markdown TEXT NOT NULL DEFAULT '',
                sanitized_edit_markdown TEXT NOT NULL DEFAULT '',
                sanitized_concept_markdown TEXT NOT NULL DEFAULT '',
                sanitized_template_markdown TEXT NOT NULL DEFAULT '',
                preview_markdown TEXT NOT NULL DEFAULT '',
                lint_result JSONB NOT NULL DEFAULT '{}'::jsonb,
                status TEXT NOT NULL,
                schema_version TEXT NOT NULL DEFAULT '1.0',
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                activated_at TIMESTAMPTZ
            )
            """
        )
        conn.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_wiki_schemas_project_status
            ON wiki_schemas (project_id, status)
            """
        )
        conn.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_wiki_schemas_one_active_per_project
            ON wiki_schemas (project_id)
            WHERE status = 'active'
            """
        )


def _row_to_record(row: dict[str, Any]) -> WikiSchemaRecord:
    lint_result = row.get("lint_result") or {}
    issue_values = lint_result.get("issues", []) if isinstance(lint_result, dict) else []
    return WikiSchemaRecord(
        id=row["id"],
        project_id=row["project_id"],
        name=row["name"],
        raw_markdown=row["raw_markdown"],
        fragments=SchemaFragments(
            global_markdown=row["sanitized_global_markdown"] or "",
            query_markdown=row["sanitized_query_markdown"] or "",
            ingest_markdown=row["sanitized_ingest_markdown"] or "",
            edit_markdown=row["sanitized_edit_markdown"] or "",
            concept_markdown=row["sanitized_concept_markdown"] or "",
            template_markdown=row["sanitized_template_markdown"] or "",
        ),
        preview_markdown=row["preview_markdown"] or "",
        issues=[_json_to_issue(issue) for issue in issue_values if isinstance(issue, dict)],
        status=row["status"],
        schema_version=row["schema_version"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
        activated_at=row["activated_at"],
    )


def _issue_to_json(issue: SchemaIssue) -> dict[str, Any]:
    return {
        "severity": issue.severity,
        "category": issue.category,
        "text": issue.text,
        "reason": issue.reason,
        "section": issue.section,
    }


def _json_to_issue(value: dict[str, Any]) -> SchemaIssue:
    return SchemaIssue(
        severity=value.get("severity", "blocked"),
        category=value.get("category", "organizer_blocked"),
        text=str(value.get("text") or ""),
        reason=str(value.get("reason") or ""),
        section=value.get("section"),
    )
