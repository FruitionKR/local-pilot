from dataclasses import dataclass
from typing import Literal


ExecutionAction = Literal["read", "execute_operation", "request_replan"]


@dataclass(frozen=True)
class AgentExecutionDecision:
    action: ExecutionAction
    operation_id: str | None = None
    tool_name: str | None = None
    arguments: dict[str, object] | None = None
    reason: str | None = None
