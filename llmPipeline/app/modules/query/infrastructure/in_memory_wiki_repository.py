from app.modules.query.application.ports import WikiRepositoryPort
from app.modules.query.domain.entities import WikiPage, WikiPageLink


class InMemoryWikiRepository(WikiRepositoryPort):
    def __init__(self, pages: list[WikiPage], links: list[WikiPageLink]) -> None:
        self._pages = pages
        self._links = links

    def list_active_pages(self, workspace_id: str) -> list[WikiPage]:
        return list(self._pages)

    def list_active_links(self, workspace_id: str) -> list[WikiPageLink]:
        return list(self._links)
