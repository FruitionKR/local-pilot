from app.modules.wiki_ingestion.application.ports import WikiPageRepositoryPort
from app.modules.wiki_ingestion.domain.wiki_page import (
    WikiPageRenameResult,
    slugify_wiki_page_title,
    validate_wiki_page_title,
)


class RenameWikiPageUseCase:
    def __init__(self, repository: WikiPageRepositoryPort) -> None:
        self._repository = repository

    def execute(
        self,
        *,
        wiki_page_id: str,
        user_id: str,
        workspace_id: str,
        title: str,
        update_slug: bool,
    ) -> WikiPageRenameResult:
        normalized_title = validate_wiki_page_title(title)
        return self._repository.rename(
            wiki_page_id=wiki_page_id,
            user_id=user_id,
            workspace_id=workspace_id,
            title=normalized_title,
            slug=(slugify_wiki_page_title(normalized_title) if update_slug else None),
        )
