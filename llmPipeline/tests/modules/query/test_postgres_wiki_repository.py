import unittest
from unittest.mock import patch

from app.modules.query.infrastructure.postgres_wiki_repository import PostgresWikiRepository


class FakeCursor:
    def fetchall(self) -> list[dict]:
        return []


class FakeConnection:
    def __init__(self) -> None:
        self.calls: list[tuple[str, tuple[str, ...]]] = []

    def __enter__(self) -> "FakeConnection":
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        return None

    def execute(self, sql: str, params: tuple[str, ...]) -> FakeCursor:
        self.calls.append((sql, params))
        return FakeCursor()


class PostgresWikiRepositoryTest(unittest.TestCase):
    def test_active_pages_are_filtered_by_workspace(self) -> None:
        connection = FakeConnection()

        with patch(
            "app.modules.query.infrastructure.postgres_wiki_repository.database.connect",
            return_value=connection,
        ):
            PostgresWikiRepository().list_active_pages("ws_target")

        sql, params = connection.calls[0]
        self.assertIn("workspace_id = %s", sql)
        self.assertEqual(params, ("ws_target",))

    def test_active_links_require_both_pages_in_workspace(self) -> None:
        connection = FakeConnection()

        with patch(
            "app.modules.query.infrastructure.postgres_wiki_repository.database.connect",
            return_value=connection,
        ):
            PostgresWikiRepository().list_active_links("ws_target")

        sql, params = connection.calls[0]
        self.assertIn("from_page.workspace_id = %s", sql)
        self.assertIn("to_page.workspace_id = %s", sql)
        self.assertEqual(params, ("ws_target", "ws_target"))


if __name__ == "__main__":
    unittest.main()
