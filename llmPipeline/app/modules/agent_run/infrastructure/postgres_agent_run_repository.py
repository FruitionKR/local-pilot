from __future__ import annotations

from typing import Any

from psycopg.types.json import Json

from app.modules.agent_run.application.ports import AgentApprovalRepositoryPort, AgentRunRepositoryPort
from app.modules.agent_run.domain.entities import AgentRun
from app.modules.agent_run.domain.plan import AgentPlan, AgentPlanOperation
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database


class PostgresAgentRunRepository(AgentRunRepositoryPort, AgentApprovalRepositoryPort):
    def create_with_planning_job(self, run: AgentRun, job_id: str) -> AgentRun:
        with database.connect() as conn:
            row = conn.execute(
                """
                INSERT INTO agent_runs (
                    id, workspace_id, user_id, action, skill_version_id, status, request_summary
                ) VALUES (%s, %s, %s, %s, %s, 'queued', %s)
                RETURNING *
                """,
                (
                    run.id,
                    run.workspace_id,
                    run.user_id,
                    run.action,
                    run.skill_version_id,
                    run.request_summary,
                ),
            ).fetchone()
            conn.execute(
                """
                INSERT INTO agent_jobs (id, run_id, job_type, status)
                VALUES (%s, %s, 'planning', 'queued')
                """,
                (job_id, run.id),
            )
        return _row_to_run(row)

    def get_for_user(self, workspace_id: str, user_id: str, run_id: str) -> AgentRun | None:
        with database.connect() as conn:
            row = conn.execute(
                """
                SELECT * FROM agent_runs
                WHERE id = %s AND workspace_id = %s AND user_id = %s
                """,
                (run_id, workspace_id, user_id),
            ).fetchone()
        return _row_to_run(row) if row else None

    def get_current_plan_for_user(
        self, workspace_id: str, user_id: str, run_id: str
    ) -> tuple[AgentRun, AgentPlan] | None:
        with database.connect() as conn:
            run_row = conn.execute(
                """
                SELECT * FROM agent_runs
                WHERE id = %s AND workspace_id = %s AND user_id = %s
                """,
                (run_id, workspace_id, user_id),
            ).fetchone()
            if run_row is None or run_row["current_plan_id"] is None:
                return None
            plan_row = conn.execute(
                "SELECT * FROM agent_plans WHERE id = %s AND run_id = %s",
                (run_row["current_plan_id"], run_id),
            ).fetchone()
            operation_rows = conn.execute(
                """
                SELECT * FROM agent_plan_operations
                WHERE plan_id = %s ORDER BY sequence
                """,
                (run_row["current_plan_id"],),
            ).fetchall()
        if plan_row is None:
            return None
        return _row_to_run(run_row), _rows_to_plan(plan_row, operation_rows)

    def save_plan(self, run_id: str, plan: AgentPlan) -> None:
        with database.connect() as conn:
            run = conn.execute("SELECT status FROM agent_runs WHERE id = %s FOR UPDATE", (run_id,)).fetchone()
            if run is None or run["status"] not in {"queued", "planning", "clarification_required"}:
                raise ValueError("AgentRun cannot accept a new plan.")
            conn.execute(
                "UPDATE agent_plans SET status = 'superseded' WHERE run_id = %s AND status <> 'approved'",
                (run_id,),
            )
            conn.execute(
                """
                INSERT INTO agent_plans (id, run_id, version, summary, operation_hash, status)
                VALUES (%s, %s, %s, %s, %s, 'awaiting_approval')
                """,
                (plan.id, run_id, plan.version, plan.summary, plan.operation_hash),
            )
            for operation in plan.operations:
                conn.execute(
                    """
                    INSERT INTO agent_plan_operations (
                        id, plan_id, sequence, tool_name, target_type, target_id, base_version,
                        source_parent_id, destination_parent_id, arguments, reason, depends_on, status
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'pending')
                    """,
                    (
                        operation.id,
                        plan.id,
                        operation.sequence,
                        operation.tool_name,
                        operation.target_type,
                        operation.target_id,
                        operation.base_version,
                        operation.source_parent_id,
                        operation.destination_parent_id,
                        Json(operation.arguments),
                        operation.reason,
                        list(operation.depends_on),
                    ),
                )
            conn.execute(
                """
                UPDATE agent_runs
                SET current_plan_id = %s, status = 'awaiting_approval', updated_at = now()
                WHERE id = %s
                """,
                (plan.id, run_id),
            )

    def approve_and_enqueue(
        self,
        run: AgentRun,
        plan: AgentPlan,
        approval_id: str,
        job_id: str,
    ) -> AgentRun:
        with database.connect() as conn:
            locked = conn.execute(
                "SELECT * FROM agent_runs WHERE id = %s AND user_id = %s FOR UPDATE",
                (run.id, run.user_id),
            ).fetchone()
            current_plan = conn.execute(
                "SELECT * FROM agent_plans WHERE id = %s AND run_id = %s FOR UPDATE",
                (plan.id, run.id),
            ).fetchone()
            if (
                locked is None
                or current_plan is None
                or locked["status"] != "awaiting_approval"
                or locked["current_plan_id"] != plan.id
                or current_plan["status"] != "awaiting_approval"
                or current_plan["version"] != plan.version
                or current_plan["operation_hash"] != plan.operation_hash
            ):
                raise ValueError("Agent plan changed and must be reviewed again.")
            conn.execute(
                """
                INSERT INTO agent_approvals (
                    id, run_id, plan_id, plan_version, operation_hash, user_id, decision
                ) VALUES (%s, %s, %s, %s, %s, %s, 'approved')
                """,
                (approval_id, run.id, plan.id, plan.version, plan.operation_hash, run.user_id),
            )
            conn.execute("UPDATE agent_plans SET status = 'approved' WHERE id = %s", (plan.id,))
            updated = conn.execute(
                """
                UPDATE agent_runs SET status = 'executing', updated_at = now()
                WHERE id = %s RETURNING *
                """,
                (run.id,),
            ).fetchone()
            conn.execute(
                """
                INSERT INTO agent_jobs (id, run_id, job_type, status)
                VALUES (%s, %s, 'execution', 'queued')
                """,
                (job_id, run.id),
            )
        return _row_to_run(updated)

    def reject(self, workspace_id: str, user_id: str, run_id: str, approval_id: str) -> AgentRun:
        with database.connect() as conn:
            run = _lock_user_run(conn, workspace_id, user_id, run_id)
            if run is None or run["status"] != "awaiting_approval" or run["current_plan_id"] is None:
                raise ValueError("Agent plan is not awaiting approval.")
            plan = conn.execute(
                "SELECT * FROM agent_plans WHERE id = %s AND run_id = %s FOR UPDATE",
                (run["current_plan_id"], run_id),
            ).fetchone()
            if plan is None or plan["status"] != "awaiting_approval":
                raise ValueError("Agent plan is not awaiting approval.")
            conn.execute(
                """
                INSERT INTO agent_approvals (
                    id, run_id, plan_id, plan_version, operation_hash, user_id, decision
                ) VALUES (%s, %s, %s, %s, %s, %s, 'rejected')
                """,
                (approval_id, run_id, plan["id"], plan["version"], plan["operation_hash"], user_id),
            )
            conn.execute("UPDATE agent_plans SET status = 'rejected' WHERE id = %s", (plan["id"],))
            updated = conn.execute(
                """
                UPDATE agent_runs
                SET status = 'rejected', updated_at = now(), finished_at = now()
                WHERE id = %s RETURNING *
                """,
                (run_id,),
            ).fetchone()
        return _row_to_run(updated)

    def cancel(self, workspace_id: str, user_id: str, run_id: str) -> AgentRun:
        with database.connect() as conn:
            run = _lock_user_run(conn, workspace_id, user_id, run_id)
            if run is None:
                raise ValueError("AgentRun not found.")
            if run["status"] in {"completed", "partial_failed", "failed", "conflicted", "rejected", "cancelled"}:
                raise ValueError("Completed AgentRun cannot be cancelled.")
            conn.execute(
                """
                UPDATE agent_jobs SET status = 'cancelled', updated_at = now()
                WHERE run_id = %s AND status = 'queued'
                """,
                (run_id,),
            )
            conn.execute(
                """
                UPDATE agent_plan_operations SET status = 'cancelled', updated_at = now()
                WHERE plan_id = %s AND status = 'pending'
                """,
                (run["current_plan_id"],),
            )
            updated = conn.execute(
                """
                UPDATE agent_runs
                SET status = 'cancelled', updated_at = now(), finished_at = now()
                WHERE id = %s RETURNING *
                """,
                (run_id,),
            ).fetchone()
        return _row_to_run(updated)

    def revise(
        self,
        workspace_id: str,
        user_id: str,
        run_id: str,
        instruction: str,
        job_id: str,
    ) -> AgentRun:
        with database.connect() as conn:
            run = _lock_user_run(conn, workspace_id, user_id, run_id)
            if run is None or run["status"] not in {"awaiting_approval", "clarification_required"}:
                raise ValueError("AgentRun cannot be revised in its current state.")
            if run["current_plan_id"]:
                conn.execute(
                    "UPDATE agent_plans SET status = 'superseded' WHERE id = %s",
                    (run["current_plan_id"],),
                )
            updated = conn.execute(
                """
                UPDATE agent_runs
                SET status = 'queued', current_plan_id = NULL, request_summary = %s,
                    error_code = NULL, updated_at = now(), finished_at = NULL
                WHERE id = %s RETURNING *
                """,
                (instruction.strip()[:1000], run_id),
            ).fetchone()
            conn.execute(
                """
                INSERT INTO agent_jobs (id, run_id, job_type, status)
                VALUES (%s, %s, 'planning', 'queued')
                """,
                (job_id, run_id),
            )
        return _row_to_run(updated)


def _row_to_run(row: dict[str, Any]) -> AgentRun:
    return AgentRun(
        id=row["id"],
        workspace_id=row["workspace_id"],
        user_id=row["user_id"],
        action=row["action"],
        skill_version_id=row["skill_version_id"],
        status=row["status"],
        request_summary=row["request_summary"],
        current_plan_id=row["current_plan_id"],
        error_code=row["error_code"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
        finished_at=row["finished_at"],
    )


def _rows_to_plan(plan_row: dict[str, Any], operation_rows: list[dict[str, Any]]) -> AgentPlan:
    return AgentPlan(
        id=plan_row["id"],
        run_id=plan_row["run_id"],
        version=plan_row["version"],
        summary=plan_row["summary"],
        operation_hash=plan_row["operation_hash"],
        status=plan_row["status"],
        operations=tuple(
            AgentPlanOperation(
                id=row["id"],
                sequence=row["sequence"],
                tool_name=row["tool_name"],
                target_type=row["target_type"],
                target_id=row["target_id"],
                base_version=row["base_version"],
                source_parent_id=row["source_parent_id"],
                destination_parent_id=row["destination_parent_id"],
                arguments=row["arguments"] or {},
                reason=row["reason"],
                depends_on=tuple(row["depends_on"] or ()),
                status=row["status"],
                error_code=row["error_code"],
            )
            for row in operation_rows
        ),
    )


def _lock_user_run(
    conn: Any,
    workspace_id: str,
    user_id: str,
    run_id: str,
) -> dict[str, Any] | None:
    return conn.execute(
        """
        SELECT * FROM agent_runs
        WHERE id = %s AND workspace_id = %s AND user_id = %s
        FOR UPDATE
        """,
        (run_id, workspace_id, user_id),
    ).fetchone()
