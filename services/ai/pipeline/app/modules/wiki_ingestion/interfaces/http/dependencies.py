import logging
from functools import lru_cache

from app.modules.wiki_embedding.infrastructure.threaded_wiki_embedding_job import (
    SynchronousWikiEmbeddingJob,
    ThreadedWikiEmbeddingJob,
)
from app.modules.wiki_ingestion.application.ports import (
    PipelineLogReaderPort,
    PipelineRunRepositoryPort,
    PipelineSourceReaderPort,
    WikiMaintenancePort,
)
from app.modules.wiki_ingestion.application.run_pipeline import RunPipelineUseCase
from app.modules.wiki_ingestion.application.restore_wiki_pages import (
    RestoreWikiPagesUseCase,
)
from app.modules.wiki_ingestion.infrastructure.pipeline_run_adapters import (
    LocalPipelineLogReader,
    ObjectStoragePipelineSourceReader,
    PostgresPipelineRunRepository,
    RunLabPipelineRunner,
)
from app.modules.wiki_ingestion.infrastructure.postgres_wiki_ingestion_repository import (
    apply_restored_wiki_state_and_cleanup,
)
from app.modules.wiki_ingestion.infrastructure.object_storage import (
    read_text_object,
    write_text_object,
)
from app.modules.wiki_ingestion.infrastructure.wiki_page_restore import (
    ObjectStorageWikiPageRestore,
)
from app.modules.wiki_ingestion.infrastructure.wiki_maintenance import PostgresWikiMaintenance


logger = logging.getLogger("fruition.pipeline")


@lru_cache(maxsize=1)
def get_pipeline_run_repository() -> PipelineRunRepositoryPort:
    return PostgresPipelineRunRepository()


@lru_cache(maxsize=1)
def get_pipeline_run_use_case() -> RunPipelineUseCase:
    return RunPipelineUseCase(
        runner=RunLabPipelineRunner(),
        repository=get_pipeline_run_repository(),
        embedding_job=ThreadedWikiEmbeddingJob(logger),
    )


@lru_cache(maxsize=1)
def get_pipeline_source_reader() -> PipelineSourceReaderPort:
    return ObjectStoragePipelineSourceReader()


@lru_cache(maxsize=1)
def get_restore_wiki_pages_use_case() -> RestoreWikiPagesUseCase:
    return RestoreWikiPagesUseCase(
        ObjectStorageWikiPageRestore(
            read_text_object,
            write_text_object,
            apply_restored_wiki_state_and_cleanup,
        ),
        SynchronousWikiEmbeddingJob(logger),
    )


@lru_cache(maxsize=1)
def get_pipeline_log_reader() -> PipelineLogReaderPort:
    return LocalPipelineLogReader()


@lru_cache(maxsize=1)
def get_wiki_maintenance() -> WikiMaintenancePort:
    return PostgresWikiMaintenance(ThreadedWikiEmbeddingJob(logger))
