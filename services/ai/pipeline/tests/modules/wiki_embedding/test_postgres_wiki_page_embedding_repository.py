from unittest.mock import Mock, patch
from datetime import UTC, datetime

import pytest

from app.modules.wiki_embedding.infrastructure.postgres_wiki_page_embedding_repository import (
    PostgresWikiPageEmbeddingRepository,
)
from app.modules.wiki_ingestion.infrastructure import (
    postgres_wiki_ingestion_repository as database,
)


@pytest.mark.parametrize("method", ["upsert_embedding", "mark_failed"])
def test_embedding_write_skips_deleted_page(method: str) -> None:
    connection = Mock()
    connection.execute.return_value.fetchone.return_value = None
    connection.__enter__ = Mock(return_value=connection)
    connection.__exit__ = Mock(return_value=False)
    repository = PostgresWikiPageEmbeddingRepository()

    with patch.object(database, "connect", return_value=connection):
        if method == "upsert_embedding":
            repository.upsert_embedding(
                "page-1", "model-1", "hash-1", [0.1], datetime(2026, 8, 10, tzinfo=UTC)
            )
        else:
            repository.mark_failed(
                "page-1", "model-1", "hash-1", "failed", datetime(2026, 8, 10, tzinfo=UTC)
            )

    assert connection.execute.call_count == 1
    assert "FOR UPDATE" in connection.execute.call_args.args[0]
