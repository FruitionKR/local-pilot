import psycopg
from datetime import datetime

from app.core.error_text import truncate_error
from app.modules.wiki_embedding.application.ports import WikiPageEmbeddingRepositoryPort
from app.modules.wiki_embedding.domain.entities import WikiPageEmbeddingTarget
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database


def _lock_active_page(conn: psycopg.Connection, page_id: str, updated_at: datetime) -> bool:
    return conn.execute(
        "SELECT 1 FROM wiki_pages WHERE id = %s AND status = 'active' AND updated_at = %s FOR UPDATE",
        (page_id, updated_at),
    ).fetchone() is not None


class PostgresWikiPageEmbeddingRepository(WikiPageEmbeddingRepositoryPort):
    def list_active_pages_by_ids(self, page_ids: list[str]) -> list[WikiPageEmbeddingTarget]:
        if not page_ids:
            return []
        with database.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, title, summary, markdown_uri, updated_at
                FROM wiki_pages
                WHERE status = 'active'
                  AND id = ANY(%s)
                ORDER BY id
                """,
                (page_ids,),
            ).fetchall()
        return [
            WikiPageEmbeddingTarget(
                page_id=row["id"],
                title=row["title"],
                summary=row["summary"],
                markdown_uri=row["markdown_uri"],
                updated_at=row["updated_at"],
            )
            for row in rows
        ]

    def existing_hashes(self, page_ids: list[str], embedding_model: str) -> dict[str, str]:
        if not page_ids:
            return {}
        with database.connect() as conn:
            rows = conn.execute(
                """
                SELECT page_id, representation_hash
                FROM wiki_page_embeddings
                WHERE embedding_model = %s
                  AND status = 'completed'
                  AND page_id = ANY(%s)
                """,
                (embedding_model, page_ids),
            ).fetchall()
        return {row["page_id"]: row["representation_hash"] for row in rows}

    def upsert_embedding(
        self,
        page_id: str,
        embedding_model: str,
        representation_hash: str,
        embedding_vector: list[float],
        source_updated_at: datetime,
    ) -> None:
        with database.connect() as conn:
            if not _lock_active_page(conn, page_id, source_updated_at):
                return
            conn.execute(
                """
                INSERT INTO wiki_page_embeddings (
                    page_id,
                    embedding_model,
                    representation_hash,
                    embedding_vector,
                    embedding_dimension,
                    status,
                    error,
                    created_at,
                    updated_at
                )
                VALUES (%s, %s, %s, %s, %s, 'completed', NULL, now(), now())
                ON CONFLICT (page_id, embedding_model) DO UPDATE SET
                    representation_hash = EXCLUDED.representation_hash,
                    embedding_vector = EXCLUDED.embedding_vector,
                    embedding_dimension = EXCLUDED.embedding_dimension,
                    status = 'completed',
                    error = NULL,
                    updated_at = now()
                """,
                (page_id, embedding_model, representation_hash, embedding_vector, len(embedding_vector)),
            )

    def mark_failed(self, page_id: str, embedding_model: str, representation_hash: str,
                    error: str, source_updated_at: datetime) -> None:
        error_message = truncate_error(error)
        with database.connect() as conn:
            if not _lock_active_page(conn, page_id, source_updated_at):
                return
            try:
                conn.execute(
                    """
                    INSERT INTO wiki_page_embeddings (
                        page_id,
                        embedding_model,
                        representation_hash,
                        embedding_vector,
                        embedding_dimension,
                        status,
                        error,
                        created_at,
                        updated_at
                    )
                    VALUES (%s, %s, %s, ARRAY[]::DOUBLE PRECISION[], 0, 'failed', %s, now(), now())
                    ON CONFLICT (page_id, embedding_model) DO UPDATE SET
                        representation_hash = EXCLUDED.representation_hash,
                        embedding_vector = ARRAY[]::DOUBLE PRECISION[],
                        embedding_dimension = 0,
                        status = 'failed',
                        error = EXCLUDED.error,
                        updated_at = now()
                    """,
                    (page_id, embedding_model, representation_hash, error_message),
                )
            except psycopg.Error:
                return
