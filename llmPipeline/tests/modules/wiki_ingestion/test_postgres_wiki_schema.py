from app.modules.wiki_ingestion.infrastructure.postgres_wiki_schema import (
    initialize_wiki_schema,
)


class FakeConnection:
    def __init__(self) -> None:
        self.statements: list[str] = []

    def execute(self, statement: str) -> None:
        self.statements.append(" ".join(statement.split()))


def test_initialize_wiki_schema_keeps_all_pipeline_tables_and_indexes() -> None:
    conn = FakeConnection()

    initialize_wiki_schema(conn)  # type: ignore[arg-type]

    statements = "\n".join(conn.statements)
    assert len(conn.statements) == 15
    for name in (
        "documents",
        "wiki_pages",
        "document_wiki_links",
        "source_blocks",
        "wiki_page_links",
        "pipeline_runs",
        "wiki_page_embeddings",
        "wiki_embedding_vectors",
        "wiki_embedding_units",
    ):
        assert name in statements
    assert conn.statements[-1].startswith(
        "CREATE INDEX IF NOT EXISTS idx_wiki_embedding_units_vector"
    )
