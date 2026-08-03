import unittest

from app.core.llm_prompt import with_schema_and_skill_prompt


class LlmPromptTest(unittest.TestCase):
    def test_places_skill_after_schema_with_safety_boundary(self) -> None:
        prompt = with_schema_and_skill_prompt(
            "SYSTEM",
            "SCHEMA",
            "문서를 간결하게 작성한다.",
        )

        self.assertLess(prompt.index("SCHEMA"), prompt.index("선택된 Skill 지침"))
        self.assertIn("권한", prompt)
        self.assertIn("승인", prompt)
        self.assertTrue(prompt.endswith("문서를 간결하게 작성한다.\n"))

    def test_omits_empty_skill(self) -> None:
        prompt = with_schema_and_skill_prompt("SYSTEM", "SCHEMA", "")

        self.assertNotIn("선택된 Skill 지침", prompt)


if __name__ == "__main__":
    unittest.main()
