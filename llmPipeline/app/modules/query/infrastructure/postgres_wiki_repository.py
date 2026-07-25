from app.modules.query.application.ports import WikiRepositoryPort
from app.modules.query.domain.entities import WikiEmbeddingUnit, WikiPage, WikiPageLink
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database


class PostgresWikiRepository(WikiRepositoryPort):
    def list_candidate_pages(
        self,
        workspace_id: str,
        query: str,
        source_limit: int,
        concept_limit: int,
    ) -> list[WikiPage]:
        with database.connect() as conn:
            rows = conn.execute(
                """
                WITH metadata_ranked AS (
                    SELECT
                        p.id,
                        p.page_type,
                        p.title,
                        p.slug,
                        p.summary,
                        p.markdown_uri,
                        p.updated_at,
                        CASE
                            WHEN lower(p.title) = lower(%s)
                              OR lower(p.slug) = lower(%s)
                            THEN 1
                            ELSE 0
                        END AS exact_match,
                        row_number() OVER (
                            PARTITION BY p.page_type
                            ORDER BY
                                CASE
                                    WHEN lower(p.title) = lower(%s)
                                      OR lower(p.slug) = lower(%s)
                                    THEN 1
                                    ELSE 0
                                END DESC,
                                ts_rank_cd(
                                    to_tsvector(
                                        'simple',
                                        concat_ws(' ', p.title, p.slug, p.summary)
                                    ),
                                    plainto_tsquery('simple', %s)
                                ) DESC,
                                p.updated_at DESC
                        ) AS type_rank
                    FROM wiki_pages p
                    WHERE p.status = 'active'
                      AND p.workspace_id = %s
                      AND p.page_type IN ('source', 'concept')
                ),
                metadata_candidates AS (
                    SELECT *
                    FROM metadata_ranked
                    WHERE (page_type = 'source' AND type_rank <= %s)
                       OR (page_type = 'concept' AND type_rank <= %s)
                ),
                unit_scores AS (
                    SELECT
                        p.id,
                        p.page_type,
                        max(
                            ts_rank_cd(
                                to_tsvector('simple', eu.text),
                                plainto_tsquery('simple', %s)
                            )
                        ) AS text_rank
                    FROM wiki_embedding_units eu
                    JOIN wiki_pages p ON p.id = eu.page_id
                    WHERE p.status = 'active'
                      AND p.workspace_id = %s
                      AND p.page_type IN ('source', 'concept')
                      AND to_tsvector('simple', eu.text)
                          @@ plainto_tsquery('simple', %s)
                    GROUP BY p.id, p.page_type
                ),
                unit_ranked AS (
                    SELECT
                        *,
                        row_number() OVER (
                            PARTITION BY page_type
                            ORDER BY text_rank DESC, id
                        ) AS type_rank
                    FROM unit_scores
                ),
                candidate_ids AS (
                    SELECT id
                    FROM metadata_candidates
                    UNION
                    SELECT id
                    FROM unit_ranked
                    WHERE (page_type = 'source' AND type_rank <= %s)
                       OR (page_type = 'concept' AND type_rank <= %s)
                ),
                candidate_pages AS (
                    SELECT
                        p.id,
                        p.page_type,
                        p.title,
                        p.slug,
                        p.summary,
                        p.markdown_uri,
                        p.updated_at,
                        coalesce(m.exact_match, 0) AS exact_match
                    FROM candidate_ids c
                    JOIN wiki_pages p ON p.id = c.id
                    LEFT JOIN metadata_candidates m ON m.id = c.id
                ),
                page_text AS (
                    SELECT
                        p.id,
                        p.page_type,
                        p.title,
                        p.slug,
                        p.summary,
                        p.markdown_uri,
                        p.updated_at,
                        p.exact_match,
                        concat_ws(
                            ' ',
                            p.title,
                            p.slug,
                            p.summary,
                            string_agg(eu.text, ' ')
                        ) AS searchable_text
                    FROM candidate_pages p
                    LEFT JOIN wiki_embedding_units eu ON eu.page_id = p.id
                    GROUP BY
                        p.id,
                        p.page_type,
                        p.title,
                        p.slug,
                        p.summary,
                        p.markdown_uri,
                        p.updated_at,
                        p.exact_match
                ),
                scored_pages AS (
                    SELECT
                        *,
                        ts_rank_cd(
                            to_tsvector('simple', searchable_text),
                            plainto_tsquery('simple', %s)
                        ) AS text_rank
                    FROM page_text
                ),
                ranked_pages AS (
                    SELECT
                        *,
                        row_number() OVER (
                            PARTITION BY page_type
                            ORDER BY
                                exact_match DESC,
                                text_rank DESC,
                                updated_at DESC
                        ) AS type_rank
                    FROM scored_pages
                )
                SELECT id, page_type, title, slug, summary, markdown_uri
                FROM ranked_pages
                WHERE (page_type = 'source' AND type_rank <= %s)
                   OR (page_type = 'concept' AND type_rank <= %s)
                ORDER BY exact_match DESC, text_rank DESC, updated_at DESC
                """,
                (
                    query,
                    query,
                    query,
                    query,
                    query,
                    workspace_id,
                    source_limit,
                    concept_limit,
                    query,
                    workspace_id,
                    query,
                    source_limit,
                    concept_limit,
                    query,
                    source_limit,
                    concept_limit,
                ),
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

    def list_links_for_page_ids(
        self,
        workspace_id: str,
        page_ids: list[str],
        limit: int,
        excluded_page_ids: list[str] | None = None,
    ) -> list[WikiPageLink]:
        if not page_ids:
            return []
        excluded_page_ids = excluded_page_ids or []
        with database.connect() as conn:
            rows = conn.execute(
                """
                SELECT l.from_page_id, l.to_page_id, l.link_type, l.label, l.confidence
                FROM wiki_page_links l
                JOIN wiki_pages from_page ON from_page.id = l.from_page_id
                JOIN wiki_pages to_page ON to_page.id = l.to_page_id
                WHERE from_page.status = 'active'
                  AND to_page.status = 'active'
                  AND l.link_type <> 'source_related_to'
                  AND from_page.workspace_id = %s
                  AND to_page.workspace_id = %s
                  AND (
                      (
                          l.from_page_id = ANY(%s)
                          AND NOT (l.to_page_id = ANY(%s))
                      )
                      OR (
                          l.to_page_id = ANY(%s)
                          AND NOT (l.from_page_id = ANY(%s))
                      )
                  )
                ORDER BY l.confidence DESC, l.from_page_id, l.to_page_id
                LIMIT %s
                """,
                (
                    workspace_id,
                    workspace_id,
                    page_ids,
                    excluded_page_ids,
                    page_ids,
                    excluded_page_ids,
                    limit,
                ),
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

    def list_pages_by_ids(
        self,
        workspace_id: str,
        page_ids: list[str],
    ) -> list[WikiPage]:
        if not page_ids:
            return []
        with database.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, page_type, title, slug, summary, markdown_uri
                FROM wiki_pages
                WHERE status = 'active'
                  AND workspace_id = %s
                  AND id = ANY(%s)
                """,
                (workspace_id, page_ids),
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
