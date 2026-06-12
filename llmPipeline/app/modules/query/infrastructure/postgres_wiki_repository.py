from app.modules.query.application.ports import WikiRepositoryPort
from app.modules.query.domain.entities import WikiPage, WikiPageLink
from fruition_lab import database


class PostgresWikiRepository(WikiRepositoryPort):
    def list_active_pages(self) -> list[WikiPage]:
        with database.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, page_type, title, slug, summary, markdown_uri
                FROM wiki_pages
                WHERE status = 'active'
                ORDER BY updated_at DESC
                """
            ).fetchall()
        return [
            WikiPage(
                id=row["id"],
                page_type=row["page_type"],
                title=row["title"],
                slug=row["slug"],
                summary=row["summary"] or "",
                markdown_uri=row["markdown_uri"],
            )
            for row in rows
        ]

    def list_active_links(self) -> list[WikiPageLink]:
        with database.connect() as conn:
            rows = conn.execute(
                """
                SELECT l.from_page_id, l.to_page_id, l.link_type, l.label, l.confidence
                FROM wiki_page_links l
                JOIN wiki_pages from_page ON from_page.id = l.from_page_id
                JOIN wiki_pages to_page ON to_page.id = l.to_page_id
                WHERE from_page.status = 'active'
                  AND to_page.status = 'active'
                """
            ).fetchall()
        return [
            WikiPageLink(
                from_page_id=row["from_page_id"],
                to_page_id=row["to_page_id"],
                link_type=row["link_type"],
                label=row["label"],
                confidence=float(row["confidence"] or 1.0),
            )
            for row in rows
        ]
