import math

import pytest

from app.modules.query.infrastructure.bm25_searcher import Bm25Searcher


def test_score_uses_number_of_documents_for_idf() -> None:
    scores = Bm25Searcher().score("rare common", ["rare", "common", "common"])

    rare_idf = math.log(1 + (3 - 1 + 0.5) / (1 + 0.5))
    common_idf = math.log(1 + (3 - 2 + 0.5) / (2 + 0.5))

    assert scores == pytest.approx([1.0, common_idf / rare_idf, common_idf / rare_idf])


def test_score_normalizes_korean_particles_attached_to_structured_terms() -> None:
    scores = Bm25Searcher().score(
        "index.md log.md",
        [
            "index.md는 페이지 카탈로그이고 log.md는 추가 전용 기록이다.",
            "관련 없는 문서",
        ],
    )

    assert scores == [1.0, 0.0]
