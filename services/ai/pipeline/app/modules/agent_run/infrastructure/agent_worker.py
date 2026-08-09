from __future__ import annotations

import logging
import threading
from typing import Annotated, Any, TypedDict
from uuid import uuid4

from langgraph.channels import UntrackedValue
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt
from langsmith import tracing_context

from app.modules.agent_run.application.ports import (
    AgentJobRepositoryPort,
    AgentExecutionDeciderPort,
    AgentPlanGeneratorPort,
    AgentPlanRepositoryPort,
    AgentToolGatewayPort,
    ToolGatewayError,
)
from app.modules.agent_run.domain.entities import (
    AgentJob,
    AgentRunContext,
    ContentArtifactReference,
)
from app.modules.agent_run.domain.plan import AgentPlan, AgentPlanOperation


logger = logging.getLogger(__name__)

_MAX_EXECUTION_STEPS = 40
_GRAPH_RECURSION_LIMIT = 64
_TERMINAL_RUN_STATUSES = frozenset(
    {"completed", "partial_failed", "failed", "conflicted", "rejected", "cancelled"}
)
class _ToolBudgetExhausted(Exception):
    """재시도 도중 tool 호출 예산이 소진되었음을 알리는 내부 신호.
    execute 단계가 이를 잡아 request_clarification 경로로 처리한다."""


_READ_TOOL_ARGUMENTS = {
    "list_root_items": frozenset(),
    "list_folder_children": frozenset({"folder_id"}),
    "get_document_metadata": frozenset({"document_id"}),
    "get_document_content": frozenset({"document_id"}),
}


class AgentRunGraphState(TypedDict, total=False):
    run_id: str
    event: str
    plan_id: str
    plan_version: int
    operation_hash: str
    observations: Annotated[list[dict[str, object]], UntrackedValue(list)]
    allowed_read_tools: list[str]
    steps: int
    outcome: str
    error_code: str


class AgentWorker:
    def __init__(
        self,
        repository: AgentJobRepositoryPort,
        run_repository: AgentPlanRepositoryPort,
        tool_gateway: AgentToolGatewayPort,
        plan_generator: AgentPlanGeneratorPort,
        execution_decider: AgentExecutionDeciderPort,
        checkpointer: BaseCheckpointSaver[str] | None = None,
    ) -> None:
        self._repository = repository
        self._run_repository = run_repository
        self._tool_gateway = tool_gateway
        self._plan_generator = plan_generator
        self._execution_decider = execution_decider
        self._graph = self._build_graph().compile(checkpointer=checkpointer or InMemorySaver())

    def process(self, job: AgentJob) -> None:
        stop_heartbeat = threading.Event()
        heartbeat = threading.Thread(
            target=self._heartbeat_loop,
            args=(job, stop_heartbeat),
            daemon=True,
        )
        heartbeat.start()
        try:
            self._run_job(job)
            self._repository.complete(job)
        except Exception as exc:
            logger.exception("Agent job 처리 실패: job_id=%s job_type=%s", job.id, job.job_type)
            self._repository.fail(job, type(exc).__name__)
        finally:
            stop_heartbeat.set()
            heartbeat.join(timeout=1)

    def _build_graph(self) -> StateGraph:
        graph = StateGraph(AgentRunGraphState)
        graph.add_node("plan", self._plan_node)
        graph.add_node("wait_for_user", self._wait_for_user_node)
        graph.add_node("start_execution", self._start_execution_node)
        graph.add_node("execute_step", self._execute_step_node)
        graph.add_node("verify", self._verify_node)
        graph.add_conditional_edges(
            START,
            _route_job_event,
            {
                "planning": "plan",
                "execution": "start_execution",
                "verification": "verify",
            },
        )
        graph.add_edge("plan", "wait_for_user")
        graph.add_conditional_edges(
            "wait_for_user",
            _route_user_decision,
            {"approved": "start_execution", "revise": "plan"},
        )
        graph.add_edge("start_execution", "execute_step")
        graph.add_conditional_edges(
            "execute_step",
            _route_execution,
            {
                "continue": "execute_step",
                "verify": "verify",
                "wait_for_user": "wait_for_user",
                "finished": END,
            },
        )
        graph.add_edge("verify", END)
        return graph

    def _run_job(self, job: AgentJob) -> None:
        if job.job_type not in {"planning", "execution", "verification"}:
            raise ValueError("Unsupported Agent job type.")
        config = {
            "configurable": {"thread_id": job.run_id},
            "recursion_limit": _GRAPH_RECURSION_LIMIT,
            "run_name": f"agent_{job.job_type}",
        }
        snapshot = self._graph.get_state(config)
        pending_interrupt = any(task.interrupts for task in snapshot.tasks)
        if job.job_type == "planning" and pending_interrupt:
            status = self._repository.load_context(job.run_id).run.status
            if status != "queued":
                return
            graph_input: AgentRunGraphState | Command | None = Command(resume={"decision": "revise"})
        elif job.job_type == "execution" and pending_interrupt:
            status = self._repository.load_context(job.run_id).run.status
            if status != "executing":
                return
            graph_input = Command(resume={"decision": "approved"})
        elif snapshot.next:
            next_nodes = set(snapshot.next)
            if job.job_type == "execution" and next_nodes == {"wait_for_user"}:
                status = self._repository.load_context(job.run_id).run.status
                if status in _TERMINAL_RUN_STATUSES:
                    return
                if status not in {"clarification_required", "queued"}:
                    raise ValueError("Agent checkpoint is not ready for this job type.")
                graph_input = None
            else:
                allowed_nodes = {
                    "planning": {"plan", "wait_for_user"},
                    "execution": {"start_execution", "execute_step", "verify"},
                    "verification": {"verify"},
                }[job.job_type]
                if not next_nodes <= allowed_nodes:
                    raise ValueError("Agent checkpoint is not ready for this job type.")
                graph_input = None
        else:
            if (
                snapshot.values
                and self._repository.load_context(job.run_id).run.status in _TERMINAL_RUN_STATUSES
            ):
                return
            graph_input = {"run_id": job.run_id, "event": job.job_type}
        with tracing_context(enabled=False):
            self._graph.invoke(graph_input, config=config, durability="sync")

    def _plan_node(self, state: AgentRunGraphState) -> AgentRunGraphState:
        self._plan_run(state["run_id"])
        return {"steps": 0, "outcome": ""}

    def _wait_for_user_node(self, state: AgentRunGraphState) -> AgentRunGraphState:
        decision = interrupt(
            {
                "run_id": state["run_id"],
                "reason": state.get("error_code") or "plan_approval_required",
            }
        )
        if not isinstance(decision, dict) or decision.get("decision") not in {"approved", "revise"}:
            raise ValueError("Unsupported Agent resume decision.")
        return {"event": str(decision["decision"]), "error_code": ""}

    def _start_execution_node(self, state: AgentRunGraphState) -> AgentRunGraphState:
        context = self._repository.load_context(state["run_id"])
        if context.run.status == "cancelled":
            return {"outcome": "finished"}
        plan = self._repository.load_current_plan(state["run_id"])
        if plan.status != "approved" or context.run.status != "executing":
            raise ValueError("Only an approved Agent plan can execute.")
        results = self._repository.load_operation_results(state["run_id"], plan.id)
        return {
            "plan_id": plan.id,
            "plan_version": plan.version,
            "operation_hash": plan.operation_hash,
            "observations": [
                {"action": "execute_operation", "operation_id": operation_id, "result": result}
                for operation_id, result in results.items()
            ],
            "allowed_read_tools": list(self._allowed_read_tools(context)),
            "steps": 0,
            "outcome": "continue",
        }

    def _heartbeat_loop(self, job: AgentJob, stop: threading.Event) -> None:
        while not stop.wait(30):
            if not self._repository.heartbeat(job):
                return

    def _plan(self, job: AgentJob) -> None:
        self._plan_run(job.run_id)

    def _plan_run(self, run_id: str) -> None:
        context = self._repository.load_context(run_id)
        if context.run.status not in {"queued", "planning", "clarification_required"}:
            return
        if not self._repository.mark_run_status(
            run_id,
            ("queued", "planning", "clarification_required"),
            "planning",
        ):
            raise ValueError("AgentRun cannot enter planning.")
        hierarchy = self._inspect_hierarchy(context)
        content_artifacts = self._load_content_artifacts(context)
        plan_id = str(uuid4())
        plan = self._plan_generator.generate(
            run_id=run_id,
            plan_id=plan_id,
            version=self._repository.next_plan_version(run_id),
            instruction=context.run.request_summary,
            hierarchy=hierarchy,
            skill_instructions=context.skill_instructions,
            allowed_tools=(
                context.allowed_tools
                if context.run.skill_version_id is not None
                else None
            ),
            content_artifacts=content_artifacts,
        )
        if context.run.skill_version_id is not None:
            unsupported = {operation.tool_name for operation in plan.operations} - set(context.allowed_tools)
            if unsupported:
                raise ValueError("Agent plan contains tools not allowed by the selected Skill.")
        self._run_repository.save_plan(run_id, plan)

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

    def _load_content_artifacts(
        self,
        context: AgentRunContext,
    ) -> tuple[ContentArtifactReference, ...]:
        if context.run.action != "workspace_workflow":
            return ()
        if not self._repository.reserve_tool_call(context.run.id):
            raise ValueError("AgentRun tool call limit exceeded.")
        response = self._tool_gateway.read(
            "list_agent_run_artifacts",
            run_id=context.run.id,
            workspace_id=context.run.workspace_id,
            user_id=context.run.user_id,
            arguments={},
        )
        items = response.get("items")
        if not isinstance(items, list) or len(items) > 20:
            raise ValueError("AgentRun artifact response is invalid.")
        return tuple(_content_artifact(item) for item in items)

    def _execute(self, job: AgentJob) -> None:
        with tracing_context(enabled=False):
            self._graph.invoke(
                {"run_id": job.run_id, "event": "execution"},
                config={
                    "configurable": {"thread_id": job.run_id},
                    "recursion_limit": _GRAPH_RECURSION_LIMIT,
                    "run_name": "agent_execution",
                },
                durability="sync",
            )

    def _execute_step_node(self, state: AgentRunGraphState) -> AgentRunGraphState:
        run_id = state["run_id"]
        steps = state.get("steps", 0)
        context = self._repository.load_context(run_id)
        if context.run.status == "clarification_required":
            return {
                "outcome": "wait_for_user",
                "error_code": context.run.error_code or "clarification_required",
                "steps": steps + 1,
            }
        if context.run.status in _TERMINAL_RUN_STATUSES or context.run.status in {"queued", "verifying"}:
            return {"outcome": "finished", "steps": steps + 1}
        if context.run.status != "executing":
            raise ValueError("AgentRun left the executing state.")
        if steps >= _MAX_EXECUTION_STEPS:
            return self._request_execution_clarification(
                run_id,
                "react_step_limit_exceeded",
                "Agent execution step limit exceeded.",
                steps,
            )

        plan = self._repository.load_current_plan(run_id)
        if (
            plan.id != state["plan_id"]
            or plan.version != state["plan_version"]
            or plan.operation_hash != state["operation_hash"]
            or plan.status != "approved"
        ):
            raise ValueError("Approved Agent plan changed during execution.")

        results = self._repository.load_operation_results(run_id, plan.id)
        observations = list(state.get("observations", []))
        observed_operation_ids = {
            str(item.get("operation_id"))
            for item in observations
            if item.get("action") == "execute_operation"
        }
        observations.extend(
            {"action": "execute_operation", "operation_id": operation_id, "result": result}
            for operation_id, result in results.items()
            if operation_id not in observed_operation_ids
        )
        remaining = {
            operation.id: operation
            for operation in plan.operations
            if operation.status in {"pending", "running"}
        }
        failed = {
            operation.id
            for operation in plan.operations
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
            return {"observations": observations, "steps": steps + 1, "outcome": "continue"}
        if not remaining:
            return {"observations": observations, "steps": steps + 1, "outcome": "verify"}

        remaining_tool_calls = self._repository.remaining_tool_calls(run_id)
        if remaining_tool_calls < len(remaining):
            return self._request_execution_clarification(
                run_id,
                "react_tool_budget_insufficient",
                "AgentRun cannot request Tool budget clarification.",
                steps,
            )
        configured_read_tools = tuple(state.get("allowed_read_tools", []))
        allowed_read_tools = configured_read_tools if remaining_tool_calls > len(remaining) else ()
        ready = tuple(
            operation
            for operation in remaining.values()
            if all(dependency in results for dependency in operation.depends_on)
        )
        if remaining and not ready:
            raise ValueError("Agent plan dependency graph cannot make progress.")

        decision = self._execution_decider.decide(
            instruction=context.run.request_summary,
            plan=plan,
            ready_operations=ready,
            observations=tuple(observations),
            allowed_read_tools=allowed_read_tools,
        )
        if decision.action == "read":
            latest_context = self._active_execution_context(run_id)
            if latest_context is None:
                return {"outcome": "finished", "steps": steps + 1}
            known_ids = _known_ids(plan, observations)
            tool_name, arguments = self._validate_read_decision(
                decision.tool_name,
                decision.arguments,
                allowed_read_tools,
                known_ids,
            )
            observations.append(
                {
                    "action": "read",
                    "tool_name": tool_name,
                    "arguments": arguments,
                    "result": self._read_tool(latest_context, tool_name, arguments),
                }
            )
            return {"observations": observations, "steps": steps + 1, "outcome": "continue"}
        if decision.action == "execute_operation":
            latest_context = self._active_execution_context(run_id)
            if latest_context is None:
                return {"outcome": "finished", "steps": steps + 1}
            operation = next((item for item in ready if item.id == decision.operation_id), None)
            if operation is None:
                raise ValueError("Agent selected an operation that is not ready.")
            try:
                response = self._execute_operation(latest_context, plan, operation, results)
            except _ToolBudgetExhausted:
                return self._request_execution_clarification(
                    run_id,
                    "react_tool_budget_insufficient",
                    "AgentRun cannot request Tool budget clarification.",
                    steps,
                )
            observation: dict[str, object] = {
                "action": "execute_operation",
                "operation_id": operation.id,
                "status": "succeeded" if response is not None else "failed",
            }
            if response is not None:
                observation["result"] = response
            observations.append(observation)
            return {"observations": observations, "steps": steps + 1, "outcome": "continue"}
        if decision.action == "request_replan":
            return self._request_execution_clarification(
                run_id,
                f"react_replan_{decision.reason}",
                "AgentRun cannot request a new plan.",
                steps,
            )
        raise ValueError("Unsupported Agent execution action.")

    def _active_execution_context(self, run_id: str) -> AgentRunContext | None:
        context = self._repository.load_context(run_id)
        if context.run.status == "cancelled":
            return None
        if context.run.status != "executing":
            raise ValueError("AgentRun left the executing state.")
        return context

    def _request_execution_clarification(
        self,
        run_id: str,
        error_code: str,
        failure_message: str,
        steps: int,
    ) -> AgentRunGraphState:
        if not self._repository.request_clarification(run_id, error_code):
            raise ValueError(failure_message)
        return {
            "outcome": "wait_for_user",
            "error_code": error_code,
            "steps": steps + 1,
        }

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
            if not self._repository.reserve_tool_call(context.run.id):
                # 예산 소진을 예외로 던지지 않고 operation을 pending으로 되돌려
                # 호출부가 request_clarification으로 우아하게 처리하도록 한다.
                self._repository.mark_operation(operation.id, ("running",), "pending")
                raise _ToolBudgetExhausted()
            try:
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
        self._verify_run(job.run_id)

    def _verify_node(self, state: AgentRunGraphState) -> AgentRunGraphState:
        self._verify_run(state["run_id"])
        return {"outcome": "finished"}

    def _verify_run(self, run_id: str) -> None:
        context = self._repository.load_context(run_id)
        if context.run.status in _TERMINAL_RUN_STATUSES:
            return
        if context.run.status == "executing":
            if not self._repository.mark_run_status(run_id, ("executing",), "verifying"):
                raise ValueError("AgentRun cannot enter verification.")
        elif context.run.status != "verifying":
            raise ValueError("AgentRun cannot enter verification.")
        plan = self._repository.load_current_plan(run_id)
        results = self._repository.load_operation_results(run_id, plan.id)
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
        self._repository.finish_run_from_operations(run_id)

    def _verify_operation(
        self,
        context: AgentRunContext,
        operation: AgentPlanOperation,
        response: dict[str, object],
    ) -> bool:
        target_id = str(response.get("id") or operation.target_id or "")
        if not target_id:
            return False
        if operation.tool_name == "apply_document_edit":
            current = self._read_tool(context, "get_document_content", {"document_id": target_id})
            expected_version = response.get("current_version")
            expected_hash = response.get("content_hash")
            return (
                isinstance(expected_version, int)
                and not isinstance(expected_version, bool)
                and isinstance(expected_hash, str)
                and bool(expected_hash)
                and current.get("current_version") == expected_version
                and current.get("content_hash") == expected_hash
            )
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
        known_ids: set[str],
    ) -> tuple[str, dict[str, object]]:
        if tool_name not in allowed_read_tools or tool_name not in _READ_TOOL_ARGUMENTS:
            raise ValueError("Agent selected a read tool that is not allowed.")
        if arguments is None or set(arguments) != _READ_TOOL_ARGUMENTS[tool_name]:
            raise ValueError("Agent read arguments do not match the tool contract.")
        if any(not isinstance(value, str) or not value.strip() for value in arguments.values()):
            raise ValueError("Agent read arguments must contain non-empty ids.")
        # document_id/folder_id 값이 승인된 plan이나 이전 tool 응답으로 실제 확인된 id인지 대조한다.
        # (문서 본문 등 신뢰할 수 없는 observations 텍스트만으로는 임의 id를 읽을 수 없다.)
        if any(value not in known_ids for value in arguments.values()):
            raise ValueError("Agent read target id is not part of the known workspace state.")
        return tool_name, arguments


def _route_job_event(state: AgentRunGraphState) -> str:
    event = state.get("event")
    if event not in {"planning", "execution", "verification"}:
        raise ValueError("Unsupported Agent graph event.")
    return event


def _route_user_decision(state: AgentRunGraphState) -> str:
    decision = state.get("event")
    if decision not in {"approved", "revise"}:
        raise ValueError("Unsupported Agent resume decision.")
    return decision


def _route_execution(state: AgentRunGraphState) -> str:
    outcome = state.get("outcome")
    if outcome not in {"continue", "verify", "wait_for_user", "finished"}:
        raise ValueError("Unsupported Agent execution outcome.")
    return outcome


def _known_ids(plan: AgentPlan, observations: list[dict[str, object]]) -> set[str]:
    """read 대상 id 검증에 쓸 known id 집합을 만든다.
    승인된 plan의 operation id들과, 지금까지 실제 backend 응답(observations)에
    등장한 id만 포함한다. LLM이 관찰 텍스트로부터 지어낸 id는 포함되지 않는다."""
    known: set[str] = set()
    for operation in plan.operations:
        known.update(
            value
            for value in (operation.target_id, operation.source_parent_id, operation.destination_parent_id)
            if value is not None
        )
    for observation in observations:
        known.update(_extract_observed_ids(observation.get("result")))
    return known


def _extract_observed_ids(value: object) -> set[str]:
    ids: set[str] = set()
    if isinstance(value, dict):
        item_id = value.get("id")
        if isinstance(item_id, str) and item_id.strip():
            ids.add(item_id)
        items = value.get("items")
        if isinstance(items, list):
            for item in items:
                ids.update(_extract_observed_ids(item))
    return ids


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


def _content_artifact(value: object) -> ContentArtifactReference:
    allowed_fields = {
        "id",
        "content_hash",
        "purpose",
        "document_id",
        "base_version",
        "target",
    }
    if not isinstance(value, dict) or not {"id", "content_hash", "purpose"}.issubset(value):
        raise ValueError("AgentRun artifact item is invalid.")
    if set(value) - allowed_fields:
        raise ValueError("AgentRun artifact item contains unsupported fields.")
    artifact_id = value.get("id")
    content_hash = value.get("content_hash")
    purpose = value.get("purpose")
    if not isinstance(artifact_id, str) or not artifact_id.strip():
        raise ValueError("AgentRun artifact id is invalid.")
    if not isinstance(content_hash, str) or not content_hash.strip():
        raise ValueError("AgentRun artifact content_hash is invalid.")
    if purpose not in {"create_document", "apply_document_edit"}:
        raise ValueError("AgentRun artifact purpose is invalid.")

    document_id = value.get("document_id")
    base_version = value.get("base_version")
    target = value.get("target")
    if purpose == "create_document":
        if document_id is not None or base_version is not None or target is not None:
            raise ValueError("Document creation artifact cannot have an edit target.")
    elif (
        not isinstance(document_id, str)
        or not document_id.strip()
        or not isinstance(base_version, int)
        or isinstance(base_version, bool)
        or base_version < 1
        or not _is_document_target(target)
    ):
        raise ValueError("Document edit artifact target is invalid.")
    return ContentArtifactReference(
        id=artifact_id.strip(),
        content_hash=content_hash.strip(),
        purpose=purpose,  # type: ignore[arg-type]
        document_id=document_id,
        base_version=base_version,
        target=target,
    )


def _is_document_target(value: object) -> bool:
    if not isinstance(value, dict) or set(value) != {"type", "start_line", "end_line"}:
        return False
    start_line = value.get("start_line")
    end_line = value.get("end_line")
    return (
        value.get("type") in {"selection", "current_section", "whole_document"}
        and isinstance(start_line, int)
        and not isinstance(start_line, bool)
        and isinstance(end_line, int)
        and not isinstance(end_line, bool)
        and start_line >= 1
        and end_line >= start_line
    )
