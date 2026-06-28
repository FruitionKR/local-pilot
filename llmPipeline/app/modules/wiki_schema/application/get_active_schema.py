from app.modules.wiki_schema.application.ports import WikiSchemaRepositoryPort
from app.modules.wiki_schema.domain.entities import WikiSchemaRecord


class GetActiveSchemaUseCase:
    def __init__(self, repository: WikiSchemaRepositoryPort) -> None:
        self._repository = repository

    def execute(self, project_id: str = "default") -> WikiSchemaRecord | None:
        if not project_id.strip():
            raise ValueError("project_id is required.")
        return self._repository.get_active(project_id.strip())
