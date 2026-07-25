from app.modules.query.application.ports import WikiRepositoryPort
from app.modules.query.domain.entities import WikiPage, WikiPageLink


class InMemoryWikiRepository(WikiRepositoryPort):
    def __init__(self, pages: list[WikiPage], links: list[WikiPageLink]) -> None:
        self._pages = pages
        self._links = links

    def list_candidate_pages(
        self,
        workspace_id: str,
        query: str,
        source_limit: int,
        concept_limit: int,
    ) -> list[WikiPage]:
        del workspace_id, query
        source_pages = [page for page in self._pages if page.is_source][:source_limit]
        concept_pages = [page for page in self._pages if page.is_concept][:concept_limit]
        return source_pages + concept_pages

    def list_links_for_page_ids(
        self,
        workspace_id: str,
        page_ids: list[str],
        limit: int,
    ) -> list[WikiPageLink]:
        del workspace_id
        candidate_ids = set(page_ids)
        return [
            link
            for link in self._links
            if link.from_page_id in candidate_ids
            and link.to_page_id in candidate_ids
        ][:limit]
