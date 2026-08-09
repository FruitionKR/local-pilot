from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from dataclasses import dataclass
from pathlib import Path

import psycopg
from psycopg import sql


@dataclass(frozen=True)
class Table:
    name: str
    columns: tuple[str, ...]
    key: tuple[str, ...]


TABLES = (
    Table("pipeline_runs", ("id", "document_id", "user_id", "workspace_id", "input_source", "output_dir", "mode", "status", "manifest", "error", "created_at", "updated_at", "finished_at"), ("id",)),
    Table("wiki_pages", ("id", "page_type", "title", "slug", "summary", "markdown_uri", "user_id", "workspace_id", "status", "created_at", "updated_at"), ("id",)),
    Table("document_wiki_links", ("document_id", "wiki_page_id", "relation_type", "confidence", "workspace_id", "created_at"), ("document_id", "relation_type", "wiki_page_id")),
    Table("wiki_page_links", ("from_page_id", "to_page_id", "link_type", "label", "confidence", "workspace_id", "created_at", "updated_at"), ("from_page_id", "link_type", "to_page_id")),
    Table("source_blocks", ("document_id", "block_id", "text"), ("block_id", "document_id")),
    Table("wiki_page_embeddings", ("page_id", "embedding_model", "representation_hash", "embedding_vector", "embedding_dimension", "status", "error", "created_at", "updated_at"), ("page_id", "embedding_model")),
    Table("wiki_embedding_vectors", ("id", "embedding_model", "representation_hash", "representation_text", "embedding_vector", "embedding_dimension", "status", "error", "created_at", "updated_at"), ("id",)),
    Table("wiki_embedding_units", ("id", "embedding_vector_id", "page_id", "source_document_id", "unit_type", "block_refs", "text", "weight", "created_at", "updated_at"), ("id",)),
)

WIKI_TABLES = tuple(table.name for table in TABLES)
AGENT_TABLES = (
    "skills", "skill_versions", "skill_version_sources", "agent_runs",
    "agent_plans", "agent_plan_operations", "agent_approvals", "agent_jobs",
    "agent_tool_executions", "agent_run_artifacts", "checkpoint_migrations",
    "checkpoints", "checkpoint_blobs", "checkpoint_writes",
)
TERMINAL_RUN_STATUSES = ("succeeded", "failed", "notify_pending")
SCHEMA_PATH = Path(__file__).with_name("db") / "ai_schema.sql"


def _url(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"Set {name} before running the Wiki DB cutover")
    return value


def _role(name: str) -> str:
    value = os.environ.get(name)
    if not value or not re.fullmatch(r"[a-z_][a-z0-9_]*", value):
        raise RuntimeError(f"Set {name} to a PostgreSQL role identifier")
    return value


def _identifiers(values: tuple[str, ...]) -> sql.Composed:
    return sql.SQL(", ").join(sql.Identifier(value) for value in values)


def _stats(conn: psycopg.Connection, table: Table) -> dict[str, str | int]:
    digest = hashlib.sha256()
    query = sql.SQL(
        "SELECT jsonb_build_array({keys})::text, "
        "md5(jsonb_build_array({columns})::text) "
        "FROM {table} ORDER BY {keys}"
    ).format(
        keys=_identifiers(table.key),
        columns=_identifiers(table.columns),
        table=sql.Identifier(table.name),
    )
    count = 0
    with conn.cursor(name=f"cutover_hash_{table.name}") as cursor:
        cursor.execute(query)
        for key, row_hash in cursor:
            for value in (key, row_hash):
                encoded = value.encode("utf-8")
                digest.update(len(encoded).to_bytes(8, "big"))
                digest.update(encoded)
            count += 1
    return {"count": count, "digest": digest.hexdigest()}


def _copy_table(source: psycopg.Connection, target: psycopg.Connection, table: Table) -> None:
    columns = _identifiers(table.columns)
    export = sql.SQL("COPY (SELECT {columns} FROM {table} ORDER BY {keys}) TO STDOUT (FORMAT BINARY)").format(
        columns=columns,
        table=sql.Identifier(table.name),
        keys=_identifiers(table.key),
    )
    load = sql.SQL("COPY {table} ({columns}) FROM STDIN (FORMAT BINARY)").format(
        table=sql.Identifier(table.name),
        columns=columns,
    )
    with source.cursor().copy(export) as output, target.cursor().copy(load) as incoming:
        for chunk in output:
            incoming.write(chunk)


def _assert_no_active_runs(source: psycopg.Connection) -> None:
    count = source.execute(
        "SELECT count(*) FROM pipeline_runs WHERE status <> ALL(%s)",
        (list(TERMINAL_RUN_STATUSES),),
    ).fetchone()[0]
    if count:
        raise RuntimeError(f"Wiki write paths are not drained: {count} pipeline run(s) are active")


def _assert_cutover_ready(source: psycopg.Connection) -> None:
    _assert_no_active_runs(source)
    for role in (_role("CORE_DB_RUNTIME_USER"), _role("AI_DB_RUNTIME_USER")):
        _assert_table_write(source, role, WIKI_TABLES, False)


def _assert_no_orphans(target: psycopg.Connection) -> None:
    checks = {
        "document_wiki_links.wiki_page_id": "SELECT count(*) FROM document_wiki_links child LEFT JOIN wiki_pages parent ON parent.id=child.wiki_page_id WHERE parent.id IS NULL",
        "wiki_page_links.from_page_id": "SELECT count(*) FROM wiki_page_links child LEFT JOIN wiki_pages parent ON parent.id=child.from_page_id WHERE parent.id IS NULL",
        "wiki_page_links.to_page_id": "SELECT count(*) FROM wiki_page_links child LEFT JOIN wiki_pages parent ON parent.id=child.to_page_id WHERE parent.id IS NULL",
        "wiki_page_embeddings.page_id": "SELECT count(*) FROM wiki_page_embeddings child LEFT JOIN wiki_pages parent ON parent.id=child.page_id WHERE parent.id IS NULL",
        "wiki_embedding_units.page_id": "SELECT count(*) FROM wiki_embedding_units child LEFT JOIN wiki_pages parent ON parent.id=child.page_id WHERE parent.id IS NULL",
        "wiki_embedding_units.embedding_vector_id": "SELECT count(*) FROM wiki_embedding_units child LEFT JOIN wiki_embedding_vectors parent ON parent.id=child.embedding_vector_id WHERE parent.id IS NULL",
    }
    failures = [name for name, query in checks.items() if target.execute(query).fetchone()[0]]
    if failures:
        raise RuntimeError(f"orphan references found: {', '.join(failures)}")


def copy_data(args: argparse.Namespace) -> None:
    if not args.writes_stopped:
        raise RuntimeError("Stop ingest workers and lint/restore/reingest mutations, then pass --writes-stopped")
    with psycopg.connect(_url("CORE_DB_MIGRATION_URL")) as source, psycopg.connect(_url("AI_DB_MIGRATION_URL")) as target:
        target.execute(SCHEMA_PATH.read_text(encoding="utf-8"))
        source.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
        _assert_cutover_ready(source)
        source_stats = {table.name: _stats(source, table) for table in TABLES}
        target_stats = {table.name: _stats(target, table) for table in TABLES}
        target_has_data = any(item["count"] for item in target_stats.values())
        if target_has_data and target_stats != source_stats:
            raise RuntimeError("ai_db contains partial or mismatched cutover data; refusing to overwrite it")
        if not target_has_data:
            for table in TABLES:
                _copy_table(source, target, table)
            target_stats = {table.name: _stats(target, table) for table in TABLES}
        if target_stats != source_stats:
            raise RuntimeError("row count, primary key, or canonical content hash validation failed")
        _assert_no_orphans(target)
    print(json.dumps({
        "core_snapshot_id": args.core_snapshot_id,
        "ai_snapshot_id": args.ai_snapshot_id,
        "result": "validated_existing" if target_has_data else "copied",
        "tables": source_stats,
    }, ensure_ascii=False, sort_keys=True))


def _grant_agent_access(conn: psycopg.Connection, role: str) -> None:
    conn.execute(
        sql.SQL("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE {} TO {}").format(
            _identifiers(AGENT_TABLES), sql.Identifier(role)
        )
    )
    sequences = tuple(row[0] for row in conn.execute(
        """
        SELECT DISTINCT sequence.relname
        FROM pg_class table_relation
        JOIN pg_depend dependency ON dependency.refobjid = table_relation.oid
        JOIN pg_class sequence ON sequence.oid = dependency.objid AND sequence.relkind = 'S'
        WHERE table_relation.relname = ANY(%s)
          AND dependency.deptype IN ('a', 'i')
        """,
        (list(AGENT_TABLES),),
    ))
    if sequences:
        conn.execute(
            sql.SQL("GRANT USAGE, SELECT, UPDATE ON SEQUENCE {} TO {}").format(
                _identifiers(sequences), sql.Identifier(role)
            )
        )


def _assert_table_write(conn: psycopg.Connection, role: str, tables: tuple[str, ...], expected: bool) -> None:
    mismatches = []
    for table in tables:
        actual = conn.execute(
            "SELECT has_table_privilege(%s, %s, 'INSERT,UPDATE,DELETE')",
            (role, f"public.{table}"),
        ).fetchone()[0]
        if actual is not expected:
            mismatches.append(table)
    if mismatches:
        state = "granted" if expected else "revoked"
        raise RuntimeError(f"core write privilege was not {state}: {', '.join(mismatches)}")


def lock_core_wiki(_: argparse.Namespace) -> None:
    with psycopg.connect(_url("CORE_DB_MIGRATION_URL")) as conn:
        for role in (_role("CORE_DB_RUNTIME_USER"), _role("AI_DB_RUNTIME_USER")):
            conn.execute(sql.SQL("REVOKE INSERT, UPDATE, DELETE ON TABLE {} FROM {}").format(
                _identifiers(WIKI_TABLES), sql.Identifier(role)
            ))
            _assert_table_write(conn, role, WIKI_TABLES, False)


def finalize_core_permissions(args: argparse.Namespace) -> None:
    if set(args.smoke_tested) != {"ingest", "query", "lint", "restore"}:
        raise RuntimeError("Run ingest/query/lint/restore smoke tests before finalizing permissions")
    ai_role = _role("AI_DB_RUNTIME_USER")
    with psycopg.connect(_url("CORE_DB_MIGRATION_URL")) as conn:
        conn.execute(sql.SQL("REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM {}").format(sql.Identifier(ai_role)))
        conn.execute(sql.SQL("REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM {}").format(sql.Identifier(ai_role)))
        conn.execute(sql.SQL("ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM {}").format(sql.Identifier(ai_role)))
        conn.execute(sql.SQL("ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE USAGE, SELECT, UPDATE ON SEQUENCES FROM {}").format(sql.Identifier(ai_role)))
        conn.execute(sql.SQL("GRANT SELECT ON TABLE {} TO {}").format(_identifiers(WIKI_TABLES), sql.Identifier(ai_role)))
        _grant_agent_access(conn, ai_role)
        _assert_table_write(conn, ai_role, ("documents", *WIKI_TABLES), False)
        _assert_table_write(conn, ai_role, AGENT_TABLES, True)


def rollback_core_permissions(_: argparse.Namespace) -> None:
    ai_role = _role("AI_DB_RUNTIME_USER")
    with psycopg.connect(_url("CORE_DB_MIGRATION_URL")) as conn:
        conn.execute(sql.SQL("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE {} TO {}").format(
            _identifiers(WIKI_TABLES), sql.Identifier(ai_role)
        ))
        _grant_agent_access(conn, ai_role)
        _assert_table_write(conn, ai_role, WIKI_TABLES, True)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="Wiki current-state maintenance cutover")
    commands = result.add_subparsers(required=True)
    copy_parser = commands.add_parser("copy")
    copy_parser.add_argument("--core-snapshot-id", required=True)
    copy_parser.add_argument("--ai-snapshot-id", required=True)
    copy_parser.add_argument("--writes-stopped", action="store_true")
    copy_parser.set_defaults(action=copy_data)
    commands.add_parser("lock-core-wiki").set_defaults(action=lock_core_wiki)
    finalize = commands.add_parser("finalize-core-permissions")
    finalize.add_argument("--smoke-tested", nargs="+", default=[])
    finalize.set_defaults(action=finalize_core_permissions)
    commands.add_parser("rollback-core-permissions").set_defaults(action=rollback_core_permissions)
    return result


def main() -> None:
    args = parser().parse_args()
    args.action(args)


if __name__ == "__main__":
    main()
