import logging
import threading
import time

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
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_ingestion_repository as database


_EMBEDDING_LOCK_NAME = "wiki-page-embedding-worker"
_MAX_ATTEMPTS = 3


class ThreadedWikiEmbeddingJob:
    def __init__(self, logger: logging.Logger) -> None:
        self._logger = logger

    def start(self, run_id: str, page_ids: list[str]) -> None:
        thread = threading.Thread(
            target=self._execute,
            args=(run_id, page_ids),
            name=f"wiki-page-embedding-{run_id}",
            daemon=True,
        )
        thread.start()

    def _execute(self, run_id: str, page_ids: list[str]) -> None:
        try:
            repository = PostgresWikiPageEmbeddingRepository()
            model_name = BgeM3EmbeddingModel().model_name
            with database.connect() as lock_connection:
                lock_connection.execute(
                    "SELECT pg_advisory_lock(hashtext(%s))",
                    (_EMBEDDING_LOCK_NAME,),
                )
                try:
                    retryable_page_ids = repository.list_retryable_page_ids(model_name)
                    targets = list(dict.fromkeys([*page_ids, *retryable_page_ids]))
                    if not targets:
                        return
                    for attempt in range(1, _MAX_ATTEMPTS + 1):
                        try:
                            result = _build_embeddings(targets)
                            if not result["failed_count"]:
                                self._logger.info(
                                    "wiki page embedding job completed run_id=%s result=%s",
                                    run_id,
                                    result,
                                )
                                return
                            error = f'{result["failed_count"]} page(s) failed'
                        except Exception as exc:
                            error = str(exc)
                        self._logger.warning(
                            "wiki page embedding job retry run_id=%s attempt=%s error=%s",
                            run_id,
                            attempt,
                            error,
                        )
                        if attempt < _MAX_ATTEMPTS:
                            time.sleep(attempt)
                    self._logger.error(
                        "wiki page embedding job failed run_id=%s error=%s",
                        run_id,
                        error,
                    )
                finally:
                    lock_connection.execute(
                        "SELECT pg_advisory_unlock(hashtext(%s))",
                        (_EMBEDDING_LOCK_NAME,),
                    )
        except Exception as exc:
            self._logger.error(
                "wiki page embedding worker failed run_id=%s error=%s",
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
