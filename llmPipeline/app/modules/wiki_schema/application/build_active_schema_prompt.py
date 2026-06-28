from app.modules.wiki_schema.application.build_project_schema_prompt import build_project_schema_prompt
from app.modules.wiki_schema.application.ports import WikiSchemaRepositoryPort
from app.modules.wiki_schema.domain.entities import SchemaFeature


def build_active_schema_prompt(
    repository: WikiSchemaRepositoryPort,
    feature: SchemaFeature,
    project_id: str = "default",
) -> str:
    active_schema = repository.get_active(project_id)
    if active_schema is None:
        return ""
    return build_project_schema_prompt(active_schema.fragments, feature)
