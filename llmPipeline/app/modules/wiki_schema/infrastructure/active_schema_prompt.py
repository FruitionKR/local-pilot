import os

from app.modules.wiki_schema.application.build_active_schema_prompt import build_active_schema_prompt
from app.modules.wiki_schema.domain.entities import SchemaFeature
from app.modules.wiki_schema.infrastructure.postgres_wiki_schema_repository import PostgresWikiSchemaRepository


def get_active_schema_prompt(feature: SchemaFeature) -> str:
    project_id = os.environ.get("WIKI_SCHEMA_PROJECT_ID", "default")
    try:
        return build_active_schema_prompt(
            repository=PostgresWikiSchemaRepository(),
            feature=feature,
            project_id=project_id,
        )
    except Exception:
        return ""
