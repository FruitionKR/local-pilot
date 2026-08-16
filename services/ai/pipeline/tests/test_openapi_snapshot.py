"""커밋된 api-specs 명세가 현재 코드와 일치하는지 본다. 계약이 바뀌면 여기서 먼저 걸린다.

텍스트로 비교하는 이유: 의미만 비교하면 직렬화 스타일·순서가 흔들려도 통과해 diff가 신호를 잃는다.
backend(access-svc·document-svc)의 스냅샷 테스트와 같은 규칙이다.
"""

import copy
import os
from pathlib import Path

import yaml

import api


REPOSITORY_ROOT = Path(__file__).resolve().parents[4]
SNAPSHOT_PATH = REPOSITORY_ROOT / "api-specs/pipeline/openapi.yaml"


def render_openapi_yaml() -> str:
    spec = copy.deepcopy(api.app.openapi())
    # servers는 실행 호스트에 따라 달라져 계약과 무관하다.
    spec.pop("servers", None)
    return yaml.safe_dump(
        spec,
        allow_unicode=True,
        # 키 순서를 고정한다 — 순서가 흔들리면 diff가 계약 변경을 못 드러낸다.
        sort_keys=True,
        default_flow_style=False,
        # 긴 한글 설명이 임의 지점에서 접히면 줄 단위 diff가 무의미해진다.
        width=10**9,
    )


def test_openapi_matches_committed_snapshot() -> None:
    actual = render_openapi_yaml()

    # UPDATE_OPENAPI_SNAPSHOT=1 로 실행하면 비교 대신 커밋 대상 파일을 갱신한다.
    if os.environ.get("UPDATE_OPENAPI_SNAPSHOT"):
        SNAPSHOT_PATH.parent.mkdir(parents=True, exist_ok=True)
        SNAPSHOT_PATH.write_text(actual, encoding="utf-8")
        return

    expected = SNAPSHOT_PATH.read_text(encoding="utf-8")
    assert actual == expected, (
        f"OpenAPI 명세가 {SNAPSHOT_PATH}와 다릅니다. "
        "UPDATE_OPENAPI_SNAPSHOT=1 pytest tests/test_openapi_snapshot.py 로 갱신하세요."
    )
