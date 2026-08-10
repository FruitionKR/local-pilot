from __future__ import annotations

import json
from typing import Any

from psycopg.types.json import Json

from app.modules.agent_run.application.ports import (
    AgentApprovalRepositoryPort,
    AgentRunRepositoryPort,
)
from app.modules.agent_run.domain.entities import AgentRun
from app.modules.agent_run.domain.plan import AgentPlan, AgentPlanOperation
from app.modules.wiki_ingestion.infrastructure import (
    postgres_wiki_ingestion_repository as database,
)


class PostgresAgentRunRepository(AgentRunRepositoryPort, AgentApprovalRepositoryPort):
    def create_with_planning_job(self, run: AgentRun, job_id: str) -> AgentRun:
        with database.connect_ai() as conn:
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
        with database.connect_ai() as conn:
            row = conn.execute(
                """
                SELECT * FROM agent_runs
                WHERE id = %s AND workspace_id = %s AND user_id = %s
                """,
                (run_id, workspace_id, user_id),
            ).fetchone()
        return _row_to_run(row) if row else None

    def authorize_tool_read(self, workspace_id: str, user_id: str, run_id: str) -> bool:
        with database.connect_ai() as conn:
            row = conn.execute(
                """
                SELECT 1 FROM agent_runs
                WHERE id = %s AND workspace_id = %s AND user_id = %s
                """,
                (run_id, workspace_id, user_id),
            ).fetchone()
        return row is not None

    def authorize_tool_execute(
        self,
        *,
        run_id: str,
        workspace_id: str,
        user_id: str,
        plan_id: str,
        plan_version: int,
        operation_hash: str,
        operation_id: str,
        tool_name: str,
        arguments: dict[str, object],
    ) -> bool:
        with database.connect_ai() as conn:
            operation = conn.execute(
                """
                SELECT operation.arguments
                FROM agent_runs run
                JOIN agent_plans plan
                  ON plan.id = run.current_plan_id AND plan.run_id = run.id
                JOIN agent_plan_operations operation
                  ON operation.plan_id = plan.id
                WHERE run.id = %s AND run.workspace_id = %s AND run.user_id = %s
                  AND run.status IN ('executing', 'verifying')
                  AND plan.id = %s AND plan.version = %s AND plan.operation_hash = %s
                  AND plan.status = 'approved'
                  AND operation.id = %s AND operation.tool_name = %s
                  AND operation.status = 'running'
                  AND EXISTS (
                      SELECT 1 FROM agent_approvals approval
                      WHERE approval.run_id = run.id AND approval.plan_id = plan.id
                        AND approval.user_id = run.user_id
                        AND approval.decision = 'approved'
                        AND approval.plan_version = plan.version
                        AND approval.operation_hash = plan.operation_hash
                  )
                """,
                (
                    run_id,
                    workspace_id,
                    user_id,
                    plan_id,
                    plan_version,
                    operation_hash,
                    operation_id,
                    tool_name,
                ),
            ).fetchone()
            if operation is None:
                return False
            result_rows = conn.execute(
                """
                SELECT DISTINCT ON (execution.operation_id)
                       execution.operation_id, execution.response_metadata
                FROM agent_tool_executions execution
                JOIN agent_plan_operations operation
                  ON operation.id = execution.operation_id
                 AND operation.plan_id = execution.plan_id
                WHERE execution.run_id = %s AND execution.plan_id = %s
                  AND execution.status = 'succeeded' AND operation.status = 'succeeded'
                ORDER BY execution.operation_id, execution.attempt DESC
                """,
                (run_id, plan_id),
            ).fetchall()
        results = {
            row["operation_id"]: row["response_metadata"] or {}
            for row in result_rows
        }
        try:
            expected = _resolve_approved_arguments(operation["arguments"], results)
            return _canonical_json(expected) == _canonical_json(arguments)
        except (TypeError, ValueError):
            return False

    def get_markdown_turn_status(
        self, workspace_id: str, user_id: str, run_id: str
    ) -> dict[str, object] | None:
        with database.connect_ai() as conn:
            row = conn.execute(
                """
                SELECT id, document_id, base_version, apply_operation_id,
                       status, result, error_code
                FROM agent_runs
                WHERE id = %s AND workspace_id = %s AND user_id = %s
                  AND action = 'markdown_turn'
                """,
                (run_id, workspace_id, user_id),
            ).fetchone()
        return dict(row) if row else None

    def get_current_plan_for_user(
        self, workspace_id: str, user_id: str, run_id: str
    ) -> tuple[AgentRun, AgentPlan] | None:
        with database.connect_ai() as conn:
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
        with database.connect_ai() as conn:
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
        with database.connect_ai() as conn:
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
        with database.connect_ai() as conn:
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
        with database.connect_ai() as conn:
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
        with database.connect_ai() as conn:
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


def _resolve_approved_arguments(value: object, results: dict[str, dict[str, object]]) -> object:
    if isinstance(value, dict):
        if set(value) == {"$operation_result", "field"}:
            operation_id = value["$operation_result"]
            field = value["field"]
            if (
                not isinstance(operation_id, str)
                or not isinstance(field, str)
                or operation_id not in results
                or field not in results[operation_id]
            ):
                raise ValueError("Approved Agent operation result cannot be resolved.")
            return results[operation_id][field]
        return {
            key: _resolve_approved_arguments(item, results)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_resolve_approved_arguments(item, results) for item in value]
    return value


def _canonical_json(value: object) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )
