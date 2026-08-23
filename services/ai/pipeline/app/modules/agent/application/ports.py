from typing import Protocol

from app.modules.agent.domain.entities import (
    AgentTurnRequest,
    AgentTurnRoute,
    QuerySpecialistDecision,
    RetrievalSource,
)


class AgentTurnRouterPort(Protocol):
    def route(self, request: AgentTurnRequest) -> AgentTurnRoute:
        ...


class ConversationReplierPort(Protocol):
    def reply(self, request: AgentTurnRequest) -> str:
        ...


class QuerySpecialistPort(Protocol):
    def decide(
        self,
        request: AgentTurnRequest,
        *,
        retrieval_source: RetrievalSource,
    ) -> QuerySpecialistDecision:
        ...
