import uuid

from app.modules.wiki_schema.application.build_schema_preview import build_schema_preview
from app.modules.wiki_schema.application.organize_schema import OrganizeSchemaUseCase
from app.modules.wiki_schema.application.ports import SchemaOrganizerPort, WikiSchemaRepositoryPort
from app.modules.wiki_schema.domain.entities import WikiSchemaRecord


class CreateSchemaDraftUseCase:
    def __init__(self, organizer: SchemaOrganizerPort, repository: WikiSchemaRepositoryPort) -> None:
        self._organize_schema = OrganizeSchemaUseCase(organizer)
        self._repository = repository

    def execute(self, raw_markdown: str, project_id: str = "default", name: str = "default") -> WikiSchemaRecord:
        if not project_id.strip():
            raise ValueError("project_id is required.")
        if not name.strip():
            raise ValueError("name is required.")

        result = self._organize_schema.execute(raw_markdown)
        record = WikiSchemaRecord(
            id=str(uuid.uuid4()),
            project_id=project_id.strip(),
            name=name.strip(),
            raw_markdown=raw_markdown,
            fragments=result.fragments,
            preview_markdown=build_schema_preview(result),
            issues=result.issues,
            status="draft",
        )
        return self._repository.save(record)
