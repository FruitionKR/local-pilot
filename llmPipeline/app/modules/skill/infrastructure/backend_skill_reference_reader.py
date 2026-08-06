from uuid import uuid4

from app.modules.agent_run.application.ports import ToolGatewayError
from app.modules.agent_run.infrastructure.backend_tool_gateway import (
    BackendToolGateway,
    build_backend_tool_gateway,
)
from app.modules.skill.domain.entities import SkillAuthoringReference


class BackendSkillReferenceReader:
    def __init__(self, gateway: BackendToolGateway) -> None:
        self._gateway = gateway

    def read(
        self,
        *,
        workspace_id: str,
        user_id: str,
        document_id: str,
    ) -> SkillAuthoringReference:
        request_id = f"skill-authoring-{uuid4()}"
        try:
            metadata = self._gateway.read(
                "get_document_metadata",
                run_id=request_id,
                workspace_id=workspace_id,
                user_id=user_id,
                arguments={"document_id": document_id},
            )
            content = self._gateway.read(
                "get_document_content",
                run_id=request_id,
                workspace_id=workspace_id,
                user_id=user_id,
                arguments={"document_id": document_id},
            )
        except ToolGatewayError as exc:
            raise ValueError("Reference document is not accessible.") from exc
        name = metadata.get("name") or metadata.get("display_name")
        markdown = content.get("content") or content.get("markdown")
        if not isinstance(name, str) or not isinstance(markdown, str):
            raise ValueError("Reference document response is invalid.")
        return SkillAuthoringReference(id=document_id, name=name, markdown=markdown)


def build_skill_reference_reader() -> BackendSkillReferenceReader:
    return BackendSkillReferenceReader(build_backend_tool_gateway())
