from __future__ import annotations

import json
import os
import time
from contextlib import contextmanager
from functools import lru_cache
from typing import Any, Iterator
from uuid import uuid4

import redis


LOCK_TTL_MS = 120_000
INDEX_TTL_SECONDS = 300
_UNLOCK = """
if redis.call('get', KEYS[1]) == ARGV[1] then
  return redis.call('del', KEYS[1])
end
return 0
"""


@lru_cache(maxsize=1)
def _client() -> redis.Redis:
    url = os.environ.get("REDIS_URL") or (
        f"redis://{os.environ.get('REDIS_HOST', 'localhost')}:"
        f"{os.environ.get('REDIS_PORT', '6379')}/0"
    )
    return redis.Redis.from_url(url, decode_responses=True)


@contextmanager
def concept_write_lock(workspace_id: str, run_id: str) -> Iterator[None]:
    key = f"wiki:concept-lock:{workspace_id}"
    token = f"{run_id}:{uuid4()}"
    deadline = time.monotonic() + 60
    while not _client().set(key, token, nx=True, px=LOCK_TTL_MS):
        if time.monotonic() >= deadline:
            raise TimeoutError(f"workspace concept lock timeout: {workspace_id}")
        time.sleep(0.1)
    try:
        yield
    finally:
        _client().eval(_UNLOCK, 1, key, token)


def get_concept_index(workspace_id: str) -> list[dict[str, Any]] | None:
    value = _client().get(f"wiki:concept-index:{workspace_id}")
    return json.loads(value) if value else None


def put_concept_index(workspace_id: str, concepts: list[dict[str, Any]]) -> None:
    _client().setex(
        f"wiki:concept-index:{workspace_id}", INDEX_TTL_SECONDS,
        json.dumps(concepts, ensure_ascii=False),
    )


def invalidate_concept_index(workspace_id: str) -> None:
    _client().delete(f"wiki:concept-index:{workspace_id}")
