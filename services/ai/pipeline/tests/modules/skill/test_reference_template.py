import unittest

from app.modules.skill.domain.reference_template import extract_markdown_structure


class ReferenceTemplateTest(unittest.TestCase):
    def test_preserves_checkbox_state_and_ordinary_list_structure(self) -> None:
        markdown = (
            "  - [ ] pending\n"
            "   - [x] done\n"
            "\t* [X] upper\n"
            "- ordinary\n"
            "1. ordered\n"
        )

        self.assertEqual(
            extract_markdown_structure(markdown),
            "  - [ ] [item]\n"
            "   - [x] [item]\n"
            "\t* [X] [item]\n"
            "- [item]\n"
            "1. [item]",
        )


if __name__ == "__main__":
    unittest.main()
