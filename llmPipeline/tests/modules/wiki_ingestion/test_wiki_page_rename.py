from datetime import UTC, datetime
from unittest.mock import MagicMock

import api
from fastapi import HTTPException
from psycopg.errors import UniqueViolation

from app.modules.wiki_ingestion.application.rename_wiki_page import RenameWikiPageUseCase
from app.modules.wiki_ingestion.domain.wiki_page import (
    WikiPageNotFoundError,
    WikiPageRenameResult,
    WikiPageSlugConflictError,
)
from app.modules.wiki_ingestion.infrastructure import postgres_wiki_page_repository as repository_module
from app.modules.wiki_ingestion.infrastructure.postgres_wiki_page_repository import (
    PostgresWikiPageRepository,
)
from app.modules.wiki_ingestion.interfaces.http.routes import rename_wiki_page
from app.modules.wiki_ingestion.interfaces.http.schemas import WikiPageRenameIn


UPDATED_AT = datetime(2026, 7, 28, tzinfo=UTC)


class RecordingRepository:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    def rename(self, **values: object) -> WikiPageRenameResult:
        self.calls.append(values)
        return WikiPageRenameResult(
            id=str(values["wiki_page_id"]),
            page_type="concept",
            title=str(values["title"]),
            previous_title="이전 제목",
            slug=str(values["slug"] or "previous-slug"),
            previous_slug="previous-slug",
            slug_updated=values["slug"] not in {None, "previous-slug"},
            updated_at=UPDATED_AT,
        )


def test_rename_use_case_normalizes_title_and_requested_slug() -> None:
    repository = RecordingRepository()
    use_case = RenameWikiPageUseCase(repository)

    result = use_case.execute(
        wiki_page_id="page-1",
        user_id="user-1",
        workspace_id="workspace-1",
        title="  새 제목! A&B  ",
        update_slug=True,
    )

    assert result.title == "새 제목! A&B"
    assert repository.calls == [
        {
            "wiki_page_id": "page-1",
            "user_id": "user-1",
            "workspace_id": "workspace-1",
            "title": "새 제목! A&B",
            "slug": "새-제목-ab",
        }
    ]


def test_rename_use_case_preserves_slug_when_update_is_not_requested() -> None:
    repository = RecordingRepository()

    RenameWikiPageUseCase(repository).execute(
        wiki_page_id="page-1",
        user_id="user-1",
        workspace_id="workspace-1",
        title="새 제목",
        update_slug=False,
    )

    assert repository.calls[0]["slug"] is None


def test_rename_use_case_rejects_blank_or_oversized_title() -> None:
    use_case = RenameWikiPageUseCase(RecordingRepository())

    for title in ("   ", "가" * 256):
        try:
            use_case.execute(
                wiki_page_id="page-1",
                user_id="user-1",
                workspace_id="workspace-1",
                title=title,
                update_slug=False,
            )
        except ValueError:
            continue
        raise AssertionError("잘못된 Wiki 제목은 거절해야 한다")


class _Cursor:
    def __init__(self, row: dict[str, object] | None) -> None:
        self._row = row

    def fetchone(self) -> dict[str, object] | None:
        return self._row


def _connection(rows: list[dict[str, object] | BaseException | None]) -> MagicMock:
    connection = MagicMock()
    connection.__enter__.return_value = connection
    connection.execute.side_effect = [
        row if isinstance(row, BaseException) else _Cursor(row)
        for row in rows
    ]
    return connection


def _page() -> dict[str, object]:
    return {
        "id": "page-1",
        "page_type": "concept",
        "title": "이전 제목",
        "slug": "previous-slug",
    }


def _updated_page(slug: str = "new-slug") -> dict[str, object]:
    return {
        "id": "page-1",
        "page_type": "concept",
        "title": "새 제목",
        "slug": slug,
        "updated_at": UPDATED_AT,
    }


def test_postgres_repository_updates_title_and_slug_in_one_connection(monkeypatch) -> None:
    connection = _connection([_page(), None, _updated_page()])
    monkeypatch.setattr(repository_module.database, "connect", lambda: connection)

    result = PostgresWikiPageRepository().rename(
        wiki_page_id="page-1",
        user_id="user-1",
        workspace_id="workspace-1",
        title="새 제목",
        slug="new-slug",
    )

    assert result.slug_updated is True
    assert result.previous_title == "이전 제목"
    assert result.previous_slug == "previous-slug"
    assert connection.execute.call_count == 3
    select_sql, select_params = connection.execute.call_args_list[0].args
    assert "FOR UPDATE" in select_sql
    assert select_params == ("page-1", "user-1", "workspace-1")
    conflict_sql, conflict_params = connection.execute.call_args_list[1].args
    assert "page_type = %s" in conflict_sql
    assert "id <> %s" in conflict_sql
    assert conflict_params == ("user-1", "workspace-1", "concept", "new-slug", "page-1")


def test_postgres_repository_allows_same_slug_and_reports_no_slug_change(monkeypatch) -> None:
    connection = _connection([_page(), None, _updated_page("previous-slug")])
    monkeypatch.setattr(repository_module.database, "connect", lambda: connection)

    result = PostgresWikiPageRepository().rename(
        wiki_page_id="page-1",
        user_id="user-1",
        workspace_id="workspace-1",
        title="새 제목",
        slug="previous-slug",
    )

    assert result.slug_updated is False


def test_postgres_repository_rejects_missing_page_and_slug_conflict(monkeypatch) -> None:
    missing_connection = _connection([None])
    monkeypatch.setattr(repository_module.database, "connect", lambda: missing_connection)

    try:
        PostgresWikiPageRepository().rename(
            wiki_page_id="missing",
            user_id="user-1",
            workspace_id="workspace-1",
            title="새 제목",
            slug=None,
        )
    except WikiPageNotFoundError:
        pass
    else:
        raise AssertionError("소속 범위에서 찾을 수 없는 페이지는 거절해야 한다")

    conflict_connection = _connection([_page(), {"id": "page-2"}])
    monkeypatch.setattr(repository_module.database, "connect", lambda: conflict_connection)

    try:
        PostgresWikiPageRepository().rename(
            wiki_page_id="page-1",
            user_id="user-1",
            workspace_id="workspace-1",
            title="새 제목",
            slug="duplicate",
        )
    except WikiPageSlugConflictError:
        pass
    else:
        raise AssertionError("같은 범위의 slug 충돌은 거절해야 한다")


def test_postgres_repository_maps_concurrent_unique_violation_to_conflict(monkeypatch) -> None:
    connection = _connection([_page(), None, UniqueViolation()])
    monkeypatch.setattr(repository_module.database, "connect", lambda: connection)

    try:
        PostgresWikiPageRepository().rename(
            wiki_page_id="page-1",
            user_id="user-1",
            workspace_id="workspace-1",
            title="새 제목",
            slug="concurrent-slug",
        )
    except WikiPageSlugConflictError:
        return
    raise AssertionError("동시 slug 생성의 unique violation은 충돌로 변환해야 한다")


def test_wiki_page_rename_route_matches_backend_contract() -> None:
    response = rename_wiki_page(
        "page-1",
        WikiPageRenameIn(
            user_id="user-1",
            workspace_id="workspace-1",
            title="새 제목",
            update_slug=True,
        ),
        use_case=RenameWikiPageUseCase(RecordingRepository()),
    )

    assert response.model_dump() == {
        "id": "page-1",
        "page_type": "concept",
        "title": "새 제목",
        "previous_title": "이전 제목",
        "slug": "새-제목",
        "previous_slug": "previous-slug",
        "slug_updated": True,
        "updated_at": UPDATED_AT,
    }
    assert "patch" in api.app.openapi()["paths"]["/wiki/pages/{wiki_page_id}/rename"]


def test_wiki_page_rename_route_maps_expected_errors() -> None:
    class FailingUseCase:
        def __init__(self, error: Exception) -> None:
            self.error = error

        def execute(self, **_values: object) -> WikiPageRenameResult:
            raise self.error

    payload = WikiPageRenameIn(
        user_id="user-1",
        workspace_id="workspace-1",
        title="새 제목",
        update_slug=True,
    )
    cases = (
        (ValueError("invalid title"), 400),
        (WikiPageNotFoundError("missing"), 404),
        (WikiPageSlugConflictError("duplicate"), 409),
    )

    for error, status_code in cases:
        try:
            rename_wiki_page("page-1", payload, use_case=FailingUseCase(error))  # type: ignore[arg-type]
        except HTTPException as exc:
            assert exc.status_code == status_code
            continue
        raise AssertionError(f"{type(error).__name__}은 HTTP {status_code}이어야 한다")
