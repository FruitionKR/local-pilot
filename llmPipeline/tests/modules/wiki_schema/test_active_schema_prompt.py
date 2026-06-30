import unittest

from app.modules.wiki_schema.application.build_active_schema_prompt import build_active_schema_prompt
from app.modules.wiki_schema.domain.entities import SchemaFragments, WikiSchemaRecord


class FakeWikiSchemaRepository:
    def __init__(self, record: WikiSchemaRecord | None) -> None:
        self.record = record
        self.project_ids: list[str] = []

    def get_active(self, project_id: str) -> WikiSchemaRecord | None:
        self.project_ids.append(project_id)
        return self.record


class ActiveSchemaPromptTest(unittest.TestCase):
    def test_builds_feature_scoped_schema_prompt(self) -> None:
        repository = FakeWikiSchemaRepository(
            WikiSchemaRecord(
                id="schema-1",
                project_id="project-1",
                name="기본 schema",
                raw_markdown="raw",
                fragments=SchemaFragments(
                    global_markdown="- 한국어로 작성한다.",
                    query_markdown="- 근거를 함께 제시한다.",
                    edit_markdown="- 수식과 단위는 보존한다.",
                ),
                preview_markdown="preview",
                issues=[],
                status="active",
            )
        )

        prompt = build_active_schema_prompt(repository, "query", "project-1")  # type: ignore[arg-type]

        self.assertIn("<project_schema>", prompt)
        self.assertIn("한국어로 작성", prompt)
        self.assertIn("근거를 함께 제시", prompt)
        self.assertNotIn("수식과 단위", prompt)
        self.assertEqual(repository.project_ids, ["project-1"])

    def test_returns_empty_prompt_without_active_schema(self) -> None:
        prompt = build_active_schema_prompt(FakeWikiSchemaRepository(None), "query", "project-1")  # type: ignore[arg-type]

        self.assertEqual(prompt, "")


if __name__ == "__main__":
    unittest.main()
