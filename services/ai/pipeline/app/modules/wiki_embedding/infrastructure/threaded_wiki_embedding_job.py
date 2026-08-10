import logging
import threading

from app.modules.wiki_embedding.application.build_wiki_page_embeddings import (
    BuildWikiPageEmbeddingsUseCase,
)
from app.modules.wiki_embedding.infrastructure.bge_m3_embedding_model import (
    BgeM3EmbeddingModel,
)
from app.modules.wiki_embedding.infrastructure.minio_markdown_reader import (
    MinioMarkdownReader,
)
from app.modules.wiki_embedding.infrastructure.postgres_wiki_page_embedding_repository import (
    PostgresWikiPageEmbeddingRepository,
)


class ThreadedWikiEmbeddingJob:
    def __init__(self, logger: logging.Logger) -> None:
        self._logger = logger

    def start(self, run_id: str, page_ids: list[str]) -> None:
        if not page_ids:
            return
        thread = threading.Thread(
            target=self._execute,
            args=(run_id, page_ids),
            name=f"wiki-page-embedding-{run_id}",
            daemon=True,
        )
        thread.start()

    def _execute(self, run_id: str, page_ids: list[str]) -> None:
        try:
            result = _build_embeddings(page_ids)
            self._logger.info(
                "wiki page embedding job completed run_id=%s result=%s",
                run_id,
                result,
            )
        except Exception as exc:
            self._logger.error(
                "wiki page embedding job failed run_id=%s error=%s",
                run_id,
                exc,
            )


class SynchronousWikiEmbeddingJob:
    def __init__(self, logger: logging.Logger) -> None:
        self._logger = logger

    def start(self, run_id: str, page_ids: list[str]) -> None:
        if not page_ids:
            return
        result = _build_embeddings(page_ids)
        if result["failed_count"]:
            raise RuntimeError(
                f'wiki restore embedding failed: {result["failed_count"]}'
            )
        self._logger.info(
            "wiki restore embedding completed run_id=%s result=%s",
            run_id,
            result,
        )


def _build_embeddings(page_ids: list[str]) -> dict:
    return BuildWikiPageEmbeddingsUseCase(
        repository=PostgresWikiPageEmbeddingRepository(),
        embedding_model=BgeM3EmbeddingModel(),
        markdown_reader=MinioMarkdownReader(),
    ).execute(page_ids)
