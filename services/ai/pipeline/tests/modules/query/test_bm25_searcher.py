import math

import pytest

from app.modules.query.infrastructure.bm25_searcher import Bm25Searcher


def test_score_uses_number_of_documents_for_idf() -> None:
    scores = Bm25Searcher().score("rare common", ["rare", "common", "common"])

    rare_idf = math.log(1 + (3 - 1 + 0.5) / (1 + 0.5))
    common_idf = math.log(1 + (3 - 2 + 0.5) / (2 + 0.5))

    assert scores == pytest.approx([1.0, common_idf / rare_idf, common_idf / rare_idf])
