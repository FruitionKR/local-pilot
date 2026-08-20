from __future__ import annotations

from typing import Any
from uuid import uuid4

from psycopg.types.json import Json

from app.modules.agent_run.domain.entities import AgentJob, AgentRunContext
from app.modules.agent_run.domain.plan import AgentPlan, AgentPlanOperation
from app.modules.agent_run.infrastructure.postgres_agent_run_repository import _row_to_run, _rows_to_plan
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database

class PostgresAgentJobRepository:
    def list_expired_run_ids(self) -> tuple[str, ...]:
        with database.connect_ai() as conn:
            rows = conn.execute(
                """
                SELECT id FROM agent_runs
                WHERE finished_at < now() - interval '90 days'
                  AND status = ANY(%s)
                """,
                (["completed", "partial_failed", "failed", "conflicted", "rejected", "cancelled"],),
            ).fetchall()
        return tuple(row["id"] for row in rows)

    def delete_expired_runs(self, run_ids: tuple[str, ...]) -> int:
        if not run_ids:
            return 0
        with database.connect_ai() as conn:
            rows = conn.execute(
                """
                DELETE FROM agent_runs
                WHERE id = ANY(%s)
                  AND finished_at < now() - interval '90 days'
                  AND status = ANY(%s)
                RETURNING id
                """,
                (
                    list(run_ids),
                    ["completed", "partial_failed", "failed", "conflicted", "rejected", "cancelled"],
                ),
            ).fetchall()
        return len(rows)

    def claim_next(self, worker_id: str) -> AgentJob | None:
        lease_token = str(uuid4())
        with database.connect_ai() as conn:
            row = conn.execute(
                """
                WITH candidate AS (
                    SELECT pending.id
                    FROM agent_jobs pending
                    WHERE pending.attempt_count < 3
                      AND pending.job_type IN ('planning', 'execution')
                      AND pending.available_at <= now()
                      AND (
                          pending.status = 'queued'
                          OR (pending.status = 'leased' AND pending.leased_until < now())
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM agent_jobs predecessor
                          WHERE predecessor.run_id = pending.run_id
                            AND predecessor.attempt_count < 3
                            AND predecessor.status IN ('queued', 'leased')
                            AND (predecessor.created_at, predecessor.id)
                                < (pending.created_at, pending.id)
                      )
                    ORDER BY pending.created_at, pending.id
                    FOR UPDATE OF pending SKIP LOCKED
                    LIMIT 1
                )
                UPDATE agent_jobs job
                SET status = 'leased', lease_owner = %s, lease_token = %s,
                    leased_until = now() + interval '90 seconds', heartbeat_at = now(),
                    attempt_count = attempt_count + 1, updated_at = now()
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.*
                """,
                (worker_id, lease_token),
            ).fetchone()
        if row is None:
            return None
        return AgentJob(
            id=row["id"],
            run_id=row["run_id"],
            job_type=row["job_type"],
            attempt_count=row["attempt_count"],
            lease_token=row["lease_token"],
            leased_until=row["leased_until"],
        )

    def heartbeat(self, job: AgentJob) -> bool:
        with database.connect_ai() as conn:
            row = conn.execute(
                """
                UPDATE agent_jobs
                SET leased_until = now() + interval '90 seconds', heartbeat_at = now(), updated_at = now()
                WHERE id = %s AND status = 'leased' AND lease_token = %s
                RETURNING id
                """,
                (job.id, job.lease_token),
            ).fetchone()
        return row is not None

    def complete(self, job: AgentJob) -> None:
        with database.connect_ai() as conn:
            updated = conn.execute(
                """
                UPDATE agent_jobs SET status = 'completed', updated_at = now()
                WHERE id = %s AND status = 'leased' AND lease_token = %s
                RETURNING id
                """,
                (job.id, job.lease_token),
            ).fetchone()
            if updated is None:
                raise ValueError("Agent job lease was lost.")

    def fail(self, job: AgentJob, error_code: str) -> None:
        terminal = job.attempt_count >= 3
        with database.connect_ai() as conn:
            updated = conn.execute(
                """
                UPDATE agent_jobs
                SET status = %s, available_at = now() + interval '5 seconds', updated_at = now()
                WHERE id = %s AND status = 'leased' AND lease_token = %s
                RETURNING id
                """,
                ("failed" if terminal else "queued", job.id, job.lease_token),
            ).fetchone()
            if updated is None:
                return
            if terminal:
                conn.execute(
                    """
                    WITH active_run AS (
                        SELECT current_plan_id
                        FROM agent_runs
                        WHERE id = %s
                          AND status NOT IN (
                              'completed', 'partial_failed', 'failed', 'conflicted', 'rejected', 'cancelled'
                          )
                    )
                    UPDATE agent_plan_operations
                    SET status = 'failed', error_code = %s, updated_at = now()
                    WHERE plan_id = (SELECT current_plan_id FROM active_run)
                      AND status = 'running'
                    """,
                    (job.run_id, error_code),
                )
                conn.execute(
                    """
                    UPDATE agent_runs
                    SET status = 'failed', error_code = %s, updated_at = now(), finished_at = now()
                    WHERE id = %s
                      AND status NOT IN (
                          'completed', 'partial_failed', 'failed', 'conflicted', 'rejected', 'cancelled'
                      )
                    """,
                    (error_code, job.run_id),
                )

    def load_context(self, run_id: str) -> AgentRunContext:
        with database.connect_ai() as conn:
            row = conn.execute(
                """
                SELECT run.*, version.instructions_markdown, version.allowed_tools
                FROM agent_runs run
                LEFT JOIN skill_versions version ON version.id = run.skill_version_id
                WHERE run.id = %s
                """,
                (run_id,),
            ).fetchone()
        if row is None:
            raise ValueError("AgentRun not found.")
        return AgentRunContext(
            run=_row_to_run(row),
            skill_instructions=row["instructions_markdown"],
            allowed_tools=tuple(row["allowed_tools"] or ()),
        )

    def mark_run_status(self, run_id: str, expected: tuple[str, ...], status: str) -> bool:
        with database.connect_ai() as conn:
            row = conn.execute(
                """
                UPDATE agent_runs SET status = %s, updated_at = now()
                WHERE id = %s AND status = ANY(%s) RETURNING id
                """,
                (status, run_id, list(expected)),
            ).fetchone()
        return row is not None

    def reserve_tool_call(self, run_id: str) -> bool:
        with database.connect_ai() as conn:
            row = conn.execute(
                """
                UPDATE agent_runs
                SET tool_call_count = tool_call_count + 1, updated_at = now()
                WHERE id = %s AND tool_call_count < 40 RETURNING tool_call_count
                """,
                (run_id,),
            ).fetchone()
        return row is not None

    def remaining_tool_calls(self, run_id: str) -> int:
        with database.connect_ai() as conn:
            row = conn.execute(
                "SELECT GREATEST(40 - tool_call_count, 0) AS remaining FROM agent_runs WHERE id = %s",
                (run_id,),
            ).fetchone()
        if row is None:
            raise ValueError("AgentRun not found.")
        return row["remaining"]

    def request_clarification(self, run_id: str, error_code: str) -> bool:
        with database.connect_ai() as conn:
            row = conn.execute(
                """
                UPDATE agent_runs
                SET status = 'clarification_required', error_code = %s, updated_at = now()
                WHERE id = %s AND status = 'executing' RETURNING id
                """,
                (error_code, run_id),
            ).fetchone()
        return row is not None

    def next_plan_version(self, run_id: str) -> int:
        with database.connect_ai() as conn:
            row = conn.execute(
                "SELECT COALESCE(max(version), 0) + 1 AS version FROM agent_plans WHERE run_id = %s",
                (run_id,),
            ).fetchone()
        return row["version"]

    def load_current_plan(self, run_id: str) -> AgentPlan:
        with database.connect_ai() as conn:
            run = conn.execute("SELECT current_plan_id FROM agent_runs WHERE id = %s", (run_id,)).fetchone()
            if run is None or run["current_plan_id"] is None:
                raise ValueError("AgentRun current plan not found.")
            plan = conn.execute("SELECT * FROM agent_plans WHERE id = %s", (run["current_plan_id"],)).fetchone()
            operations = conn.execute(
                "SELECT * FROM agent_plan_operations WHERE plan_id = %s ORDER BY sequence",
                (run["current_plan_id"],),
            ).fetchall()
        return _rows_to_plan(plan, operations)

    def mark_operation(self, operation_id: str, from_statuses: tuple[str, ...], status: str, error_code: str | None = None) -> bool:
        with database.connect_ai() as conn:
            row = conn.execute(
                """
                UPDATE agent_plan_operations
                SET status = %s, error_code = %s, updated_at = now()
                WHERE id = %s AND status = ANY(%s) RETURNING id
                """,
                (status, error_code, operation_id, list(from_statuses)),
            ).fetchone()
        return row is not None

    def save_tool_execution(
        self,
        *,
        run_id: str,
        plan_id: str,
        operation_id: str,
        tool_name: str,
        idempotency_key: str,
        attempt: int,
        status: str,
        response_metadata: dict[str, object],
        error_code: str | None,
    ) -> None:
        with database.connect_ai() as conn:
            conn.execute(
                """
                INSERT INTO agent_tool_executions (
                    id, run_id, plan_id, operation_id, tool_name, idempotency_key,
                    attempt, status, response_metadata, error_code, finished_at
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, now())
                ON CONFLICT (idempotency_key) DO UPDATE
                SET attempt = EXCLUDED.attempt, status = EXCLUDED.status,
                    response_metadata = EXCLUDED.response_metadata,
                    error_code = EXCLUDED.error_code, finished_at = now()
                """,
                (
                    str(uuid4()), run_id, plan_id, operation_id, tool_name, idempotency_key,
                    attempt, status, Json(response_metadata), error_code,
                ),
            )

    def load_operation_results(self, run_id: str, plan_id: str) -> dict[str, dict[str, object]]:
        with database.connect_ai() as conn:
            rows = conn.execute(
                """
                SELECT operation_id, response_metadata
                FROM agent_tool_executions
                WHERE run_id = %s AND plan_id = %s AND status = 'succeeded'
                """,
                (run_id, plan_id),
            ).fetchall()
        return {row["operation_id"]: row["response_metadata"] or {} for row in rows}

    def finish_run_from_operations(self, run_id: str) -> None:
        with database.connect_ai() as conn:
            counts = conn.execute(
                """
                SELECT operation.status, count(*) AS count
                FROM agent_plan_operations operation
                JOIN agent_runs run ON run.current_plan_id = operation.plan_id
                WHERE run.id = %s GROUP BY operation.status
                """,
                (run_id,),
            ).fetchall()
            values = {row["status"]: row["count"] for row in counts}
            if values.get("conflicted", 0):
                status = "conflicted"
            elif any(values.get(key, 0) for key in ("failed", "forbidden", "verification_failed", "skipped")):
                status = "partial_failed" if values.get("succeeded", 0) else "failed"
            else:
                status = "completed"
            conn.execute(
                """
                UPDATE agent_runs SET status = %s, updated_at = now(), finished_at = now()
                WHERE id = %s AND status = 'verifying'
                """,
                (status, run_id),
            )
