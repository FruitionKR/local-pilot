import logging
from unittest.mock import patch

import pytest

from app.modules.wiki_embedding.infrastructure import threaded_wiki_embedding_job


def test_restore_embedding_failure_prevents_success() -> None:
    job = threaded_wiki_embedding_job.SynchronousWikiEmbeddingJob(
        logging.getLogger("test")
    )

    with (
        patch.object(
            threaded_wiki_embedding_job,
            "_build_embeddings",
            return_value={"failed_count": 1},
        ),
        pytest.raises(RuntimeError, match="embedding failed"),
    ):
        job.start("restore-1", ["page-1"])
