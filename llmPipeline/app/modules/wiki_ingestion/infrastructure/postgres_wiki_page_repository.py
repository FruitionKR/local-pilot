from datetime import datetime

from psycopg.errors import UniqueViolation

from app.modules.wiki_ingestion.application.ports import WikiPageRepositoryPort
from app.modules.wiki_ingestion.domain.wiki_page import (
    WikiPageNotFoundError,
    WikiPageRenameResult,
    WikiPageSlugConflictError,
)
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database


class PostgresWikiPageRepository(WikiPageRepositoryPort):
    def rename(
        self,
        *,
        wiki_page_id: str,
        user_id: str,
        workspace_id: str,
        title: str,
        slug: str | None,
    ) -> WikiPageRenameResult:
        try:
            with database.connect() as conn:
                page = conn.execute(
                    """
                    SELECT id, page_type, title, slug
                    FROM wiki_pages
                    WHERE id = %s
                      AND user_id = %s
                      AND workspace_id = %s
                      AND status = 'active'
                    FOR UPDATE
                    """,
                    (wiki_page_id, user_id, workspace_id),
                ).fetchone()
                if page is None:
                    raise WikiPageNotFoundError("Wiki page not found.")

                previous_slug = str(page["slug"])
                resolved_slug = slug or previous_slug
                if slug is not None:
                    conflict = conn.execute(
                        """
                        SELECT id
                        FROM wiki_pages
                        WHERE user_id = %s
                          AND workspace_id = %s
                          AND page_type = %s
                          AND slug = %s
                          AND id <> %s
                        LIMIT 1
                        """,
                        (user_id, workspace_id, page["page_type"], slug, wiki_page_id),
                    ).fetchone()
                    if conflict is not None:
                        raise WikiPageSlugConflictError(f"Wiki page slug already exists: {slug}")

                updated = conn.execute(
                    """
                    UPDATE wiki_pages
                    SET title = %s, slug = %s, updated_at = now()
                    WHERE id = %s
                    RETURNING id, page_type, title, slug, updated_at
                    """,
                    (title, resolved_slug, wiki_page_id),
                ).fetchone()
                if updated is None:
                    raise WikiPageNotFoundError("Wiki page not found.")
        except UniqueViolation as exc:
            raise WikiPageSlugConflictError(
                f"Wiki page slug already exists: {slug}"
            ) from exc

        updated_at = updated["updated_at"]
        if not isinstance(updated_at, datetime):
            raise RuntimeError("Wiki page rename result has an invalid updated_at.")
        return WikiPageRenameResult(
            id=str(updated["id"]),
            page_type=str(updated["page_type"]),
            title=str(updated["title"]),
            previous_title=str(page["title"]),
            slug=str(updated["slug"]),
            previous_slug=previous_slug,
            slug_updated=slug is not None and str(updated["slug"]) != previous_slug,
            updated_at=updated_at,
        )
