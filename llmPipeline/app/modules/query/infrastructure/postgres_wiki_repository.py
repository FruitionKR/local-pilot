from app.modules.query.application.ports import WikiRepositoryPort
from app.modules.query.domain.entities import WikiEmbeddingUnit, WikiPage, WikiPageLink
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database


class PostgresWikiRepository(WikiRepositoryPort):
    def list_active_pages(self, workspace_id: str) -> list[WikiPage]:
        with database.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, page_type, title, slug, summary, markdown_uri
                FROM wiki_pages
                WHERE status = 'active'
                  AND workspace_id = %s
                ORDER BY updated_at DESC
                """,
                (workspace_id,),
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

    def list_active_links(self, workspace_id: str) -> list[WikiPageLink]:
        with database.connect() as conn:
            rows = conn.execute(
                """
                SELECT l.from_page_id, l.to_page_id, l.link_type, l.label, l.confidence
                FROM wiki_page_links l
                JOIN wiki_pages from_page ON from_page.id = l.from_page_id
                JOIN wiki_pages to_page ON to_page.id = l.to_page_id
                WHERE from_page.status = 'active'
                  AND to_page.status = 'active'
                  AND from_page.workspace_id = %s
                  AND to_page.workspace_id = %s
                """,
                (workspace_id, workspace_id),
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

    def list_embedding_units_by_page_ids(self, page_ids: list[str]) -> dict[str, list[WikiEmbeddingUnit]]:
        if not page_ids:
            return {}
        with database.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, page_id, source_document_id, unit_type, block_refs, text, weight
                FROM wiki_embedding_units
                WHERE page_id = ANY(%s)
                ORDER BY page_id, weight DESC, id
                """,
                (page_ids,),
            ).fetchall()
        units_by_page_id: dict[str, list[WikiEmbeddingUnit]] = {}
        for row in rows:
            unit = WikiEmbeddingUnit(
                id=row["id"],
                page_id=row["page_id"],
                source_document_id=row["source_document_id"],
                unit_type=row["unit_type"],
                source_block_ids=list(row["block_refs"] or []),
                text=row["text"],
                weight=float(row["weight"] or 1.0),
            )
            units_by_page_id.setdefault(unit.page_id, []).append(unit)
        return units_by_page_id
