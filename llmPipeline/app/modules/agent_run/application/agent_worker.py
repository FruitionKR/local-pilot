from __future__ import annotations

import logging
import threading
from typing import Any
from uuid import uuid4

from app.modules.agent_run.application.ports import (
    AgentJobRepositoryPort,
    AgentExecutionDeciderPort,
    AgentPlanGeneratorPort,
    AgentPlanRepositoryPort,
    AgentToolGatewayPort,
    ToolGatewayError,
)
from app.modules.agent_run.domain.entities import AgentJob, AgentRunContext
from app.modules.agent_run.domain.plan import AgentPlan, AgentPlanOperation


logger = logging.getLogger(__name__)

_MAX_EXECUTION_STEPS = 40
_READ_TOOL_ARGUMENTS = {
    "list_root_items": frozenset(),
    "list_folder_children": frozenset({"folder_id"}),
    "get_document_metadata": frozenset({"document_id"}),
}


class AgentWorker:
    def __init__(
        self,
        repository: AgentJobRepositoryPort,
        run_repository: AgentPlanRepositoryPort,
        tool_gateway: AgentToolGatewayPort,
        plan_generator: AgentPlanGeneratorPort,
        execution_decider: AgentExecutionDeciderPort,
    ) -> None:
        self._repository = repository
        self._run_repository = run_repository
        self._tool_gateway = tool_gateway
        self._plan_generator = plan_generator
        self._execution_decider = execution_decider

    def process(self, job: AgentJob) -> None:
        stop_heartbeat = threading.Event()
        heartbeat = threading.Thread(
            target=self._heartbeat_loop,
            args=(job, stop_heartbeat),
            daemon=True,
        )
        heartbeat.start()
        try:
            if job.job_type == "planning":
                self._plan(job)
            elif job.job_type == "execution":
                self._execute(job)
            elif job.job_type == "verification":
                self._verify(job)
            else:
                raise ValueError("Unsupported Agent job type.")
            self._repository.complete(job)
        except Exception as exc:
            logger.exception("Agent job 처리 실패: job_id=%s job_type=%s", job.id, job.job_type)
            self._repository.fail(job, type(exc).__name__)
        finally:
            stop_heartbeat.set()
            heartbeat.join(timeout=1)

    def _heartbeat_loop(self, job: AgentJob, stop: threading.Event) -> None:
        while not stop.wait(30):
            if not self._repository.heartbeat(job):
                return

    def _plan(self, job: AgentJob) -> None:
        context = self._repository.load_context(job.run_id)
        if context.run.status == "cancelled":
            return
        if not self._repository.mark_run_status(
            job.run_id,
            ("queued", "planning", "clarification_required"),
            "planning",
        ):
            raise ValueError("AgentRun cannot enter planning.")
        hierarchy = self._inspect_hierarchy(context)
        plan_id = str(uuid4())
        plan = self._plan_generator.generate(
            run_id=job.run_id,
            plan_id=plan_id,
            version=self._repository.next_plan_version(job.run_id),
            instruction=context.run.request_summary,
            hierarchy=hierarchy,
            skill_instructions=context.skill_instructions,
            allowed_tools=(
                context.allowed_tools
                if context.run.skill_version_id is not None
                else None
            ),
        )
        if context.run.skill_version_id is not None:
            unsupported = {operation.tool_name for operation in plan.operations} - set(context.allowed_tools)
            if unsupported:
                raise ValueError("Agent plan contains tools not allowed by the selected Skill.")
        self._run_repository.save_plan(job.run_id, plan)

    def _inspect_hierarchy(self, context: AgentRunContext) -> list[dict[str, object]]:
        root = self._read_tool(context, "list_root_items", {})
        items = root.get("items", [])
        if not isinstance(items, list):
            raise ValueError("Hierarchy root response is invalid.")
        snapshot: list[dict[str, object]] = []
        queue: list[tuple[str | None, dict[str, object]]] = [
            (None, item) for item in items if isinstance(item, dict)
        ]
        while queue:
            parent_id, item = queue.pop(0)
            normalized = {**item, "parent_id": parent_id}
            snapshot.append(normalized)
            if item.get("type") == "folder" and item.get("has_children") is True:
                folder_id = str(item.get("id"))
                children = self._read_tool(context, "list_folder_children", {"folder_id": folder_id})
                child_items = children.get("items", [])
                if not isinstance(child_items, list):
                    raise ValueError("Hierarchy children response is invalid.")
                queue.extend((folder_id, child) for child in child_items if isinstance(child, dict))
        return snapshot

    def _execute(self, job: AgentJob) -> None:
        context = self._repository.load_context(job.run_id)
        if context.run.status == "cancelled":
            return
        plan = self._repository.load_current_plan(job.run_id)
        if plan.status != "approved" or context.run.status != "executing":
            raise ValueError("Only an approved Agent plan can execute.")
        results = self._repository.load_operation_results(job.run_id, plan.id)
        observations: list[dict[str, object]] = [
            {"action": "execute_operation", "operation_id": operation_id, "result": result}
            for operation_id, result in results.items()
        ]
        configured_read_tools = self._allowed_read_tools(context)
        for _ in range(_MAX_EXECUTION_STEPS):
            current_context = self._repository.load_context(job.run_id)
            if current_context.run.status == "cancelled":
                return
            if current_context.run.status != "executing":
                raise ValueError("AgentRun left the executing state.")
            current_plan = self._repository.load_current_plan(job.run_id)
            if (
                current_plan.id != plan.id
                or current_plan.version != plan.version
                or current_plan.operation_hash != plan.operation_hash
                or current_plan.status != "approved"
            ):
                raise ValueError("Approved Agent plan changed during execution.")
            remaining = {
                operation.id: operation
                for operation in current_plan.operations
                if operation.status in {"pending", "running"}
            }
            failed = {
                operation.id
                for operation in current_plan.operations
                if operation.status
                in {"failed", "forbidden", "conflicted", "skipped", "cancelled", "verification_failed"}
            }
            blocked = [
                operation
                for operation in remaining.values()
                if any(dependency in failed for dependency in operation.depends_on)
            ]
            for operation in blocked:
                self._repository.mark_operation(
                    operation.id,
                    ("pending", "running"),
                    "skipped",
                    "dependency_failed",
                )
                observations.append(
                    {
                        "action": "operation_status",
                        "operation_id": operation.id,
                        "status": "skipped",
                        "error_code": "dependency_failed",
                    }
                )
            if blocked:
                continue
            if not remaining:
                self._repository.enqueue_verification(job.run_id)
                return
            remaining_tool_calls = self._repository.remaining_tool_calls(job.run_id)
            if remaining_tool_calls < len(remaining):
                if not self._repository.request_clarification(
                    job.run_id,
                    "react_tool_budget_insufficient",
                ):
                    raise ValueError("AgentRun cannot request Tool budget clarification.")
                return
            allowed_read_tools = (
                configured_read_tools
                if remaining_tool_calls > len(remaining)
                else ()
            )
            ready = tuple(
                operation
                for operation in remaining.values()
                if all(dependency in results for dependency in operation.depends_on)
            )
            if remaining and not ready:
                raise ValueError("Agent plan dependency graph cannot make progress.")
            decision = self._execution_decider.decide(
                instruction=context.run.request_summary,
                plan=current_plan,
                ready_operations=ready,
                observations=tuple(observations),
                allowed_read_tools=allowed_read_tools,
            )
            if decision.action == "read":
                latest_context = self._repository.load_context(job.run_id)
                if latest_context.run.status == "cancelled":
                    return
                if latest_context.run.status != "executing":
                    raise ValueError("AgentRun left the executing state.")
                tool_name, arguments = self._validate_read_decision(
                    decision.tool_name,
                    decision.arguments,
                    allowed_read_tools,
                )
                observations.append(
                    {
                        "action": "read",
                        "tool_name": tool_name,
                        "arguments": arguments,
                        "result": self._read_tool(latest_context, tool_name, arguments),
                    }
                )
                continue
            if decision.action == "execute_operation":
                latest_context = self._repository.load_context(job.run_id)
                if latest_context.run.status == "cancelled":
                    return
                if latest_context.run.status != "executing":
                    raise ValueError("AgentRun left the executing state.")
                operation = next(
                    (item for item in ready if item.id == decision.operation_id),
                    None,
                )
                if operation is None:
                    raise ValueError("Agent selected an operation that is not ready.")
                response = self._execute_operation(latest_context, current_plan, operation, results)
                observation: dict[str, object] = {
                    "action": "execute_operation",
                    "operation_id": operation.id,
                    "status": "succeeded" if response is not None else "failed",
                }
                if response is not None:
                    results[operation.id] = response
                    observation["result"] = response
                observations.append(observation)
                continue
            if decision.action == "request_replan":
                if not self._repository.request_clarification(
                    job.run_id,
                    f"react_replan_{decision.reason}",
                ):
                    raise ValueError("AgentRun cannot request a new plan.")
                return
            raise ValueError("Unsupported Agent execution action.")
        if not self._repository.request_clarification(
            job.run_id,
            "react_step_limit_exceeded",
        ):
            raise ValueError("Agent execution step limit exceeded.")

    def _execute_operation(
        self,
        context: AgentRunContext,
        plan: AgentPlan,
        operation: AgentPlanOperation,
        results: dict[str, dict[str, object]],
    ) -> dict[str, object] | None:
        if not self._repository.mark_operation(operation.id, ("pending", "running"), "running"):
            return None
        idempotency_key = f"agent:{context.run.id}:{plan.id}:{operation.id}"
        try:
            arguments = _resolve_operation_references(operation.arguments, results)
        except ValueError:
            self._repository.mark_operation(operation.id, ("running",), "failed", "unresolved_dependency")
            return None
        for attempt in range(1, 4):
            try:
                if not self._repository.reserve_tool_call(context.run.id):
                    raise ValueError("AgentRun tool call limit exceeded.")
                response = self._tool_gateway.execute(
                    operation.tool_name,
                    run_id=context.run.id,
                    workspace_id=context.run.workspace_id,
                    user_id=context.run.user_id,
                    plan_id=plan.id,
                    plan_version=plan.version,
                    operation_hash=plan.operation_hash,
                    operation_id=operation.id,
                    idempotency_key=idempotency_key,
                    arguments=arguments,
                )
                self._repository.save_tool_execution(
                    run_id=context.run.id,
                    plan_id=plan.id,
                    operation_id=operation.id,
                    tool_name=operation.tool_name,
                    idempotency_key=idempotency_key,
                    attempt=attempt,
                    status="succeeded",
                    response_metadata=response,
                    error_code=None,
                )
                self._repository.mark_operation(operation.id, ("running",), "succeeded")
                return response
            except ToolGatewayError as exc:
                error_code = f"tool_http_{exc.status_code or 'unavailable'}"
                if exc.retryable and attempt < 3:
                    continue
                status = "forbidden" if exc.status_code == 403 else "conflicted" if exc.status_code == 409 else "failed"
                self._repository.save_tool_execution(
                    run_id=context.run.id,
                    plan_id=plan.id,
                    operation_id=operation.id,
                    tool_name=operation.tool_name,
                    idempotency_key=idempotency_key,
                    attempt=attempt,
                    status="failed",
                    response_metadata={},
                    error_code=error_code,
                )
                self._repository.mark_operation(operation.id, ("running",), status, error_code)
                return None
        return None

    def _verify(self, job: AgentJob) -> None:
        context = self._repository.load_context(job.run_id)
        if context.run.status == "cancelled":
            return
        plan = self._repository.load_current_plan(job.run_id)
        results = self._repository.load_operation_results(job.run_id, plan.id)
        for operation in plan.operations:
            if operation.status != "succeeded":
                continue
            response = results.get(operation.id, {})
            if not self._verify_operation(context, operation, response):
                self._repository.mark_operation(
                    operation.id,
                    ("succeeded",),
                    "verification_failed",
                    "state_mismatch",
                )
        self._repository.finish_run_from_operations(job.run_id)

    def _verify_operation(
        self,
        context: AgentRunContext,
        operation: AgentPlanOperation,
        response: dict[str, object],
    ) -> bool:
        target_id = str(response.get("id") or operation.target_id or "")
        if not target_id:
            return False
        if operation.target_type == "document" and operation.tool_name == "rename_document":
            current = self._read_tool(context, "get_document_metadata", {"document_id": target_id})
            return _response_name(current) == _response_name(response)
        parent_id = response.get("parent_folder_id") if operation.target_type == "folder" else response.get("folder_id")
        tool_name = "list_root_items" if parent_id is None else "list_folder_children"
        arguments = {} if parent_id is None else {"folder_id": str(parent_id)}
        current = self._read_tool(context, tool_name, arguments)
        items = current.get("items", [])
        return isinstance(items, list) and any(
            isinstance(item, dict)
            and str(item.get("id")) == target_id
            and (not _response_name(response) or item.get("name") == _response_name(response))
            for item in items
        )

    def _read_tool(
        self,
        context: AgentRunContext,
        tool_name: str,
        arguments: dict[str, object],
    ) -> dict[str, object]:
        if context.run.skill_version_id is not None and tool_name not in context.allowed_tools:
            raise ValueError("Selected Skill does not allow the required read tool.")
        if not self._repository.reserve_tool_call(context.run.id):
            raise ValueError("AgentRun tool call limit exceeded.")
        return self._tool_gateway.read(
            tool_name,
            run_id=context.run.id,
            workspace_id=context.run.workspace_id,
            user_id=context.run.user_id,
            arguments=arguments,
        )

    def _allowed_read_tools(self, context: AgentRunContext) -> tuple[str, ...]:
        if context.run.skill_version_id is None:
            return tuple(_READ_TOOL_ARGUMENTS)
        return tuple(tool_name for tool_name in _READ_TOOL_ARGUMENTS if tool_name in context.allowed_tools)

    def _validate_read_decision(
        self,
        tool_name: str | None,
        arguments: dict[str, object] | None,
        allowed_read_tools: tuple[str, ...],
    ) -> tuple[str, dict[str, object]]:
        if tool_name not in allowed_read_tools or tool_name not in _READ_TOOL_ARGUMENTS:
            raise ValueError("Agent selected a read tool that is not allowed.")
        if arguments is None or set(arguments) != _READ_TOOL_ARGUMENTS[tool_name]:
            raise ValueError("Agent read arguments do not match the tool contract.")
        if any(not isinstance(value, str) or not value.strip() for value in arguments.values()):
            raise ValueError("Agent read arguments must contain non-empty ids.")
        return tool_name, arguments


def _resolve_operation_references(
    value: object,
    results: dict[str, dict[str, object]],
) -> Any:
    if isinstance(value, dict):
        reference = value.get("$operation_result")
        field = value.get("field")
        if isinstance(reference, str) and isinstance(field, str) and len(value) == 2:
            if reference not in results or field not in results[reference]:
                raise ValueError("Agent operation result reference cannot be resolved.")
            return results[reference][field]
        return {key: _resolve_operation_references(item, results) for key, item in value.items()}
    if isinstance(value, list):
        return [_resolve_operation_references(item, results) for item in value]
    return value


def _response_name(response: dict[str, object]) -> object | None:
    return response.get("name") or response.get("display_name") or response.get("displayName")
