import unittest

from app.modules.skill.domain.entities import SkillCapability
from app.modules.skill.domain.policy import validate_allowed_tools


class SkillPolicyTest(unittest.TestCase):
    def test_accepts_tools_allowed_by_capability(self) -> None:
        validate_allowed_tools(
            capabilities=("folder-organize",),
            allowed_tools=("list_folder_children", "move_document"),
        )

    def test_rejects_tool_outside_capability(self) -> None:
        with self.assertRaisesRegex(ValueError, "allowed_tools"):
            validate_allowed_tools(
                capabilities=("folder-organize",),
                allowed_tools=("delete_folder",),
            )

    def test_defines_only_mvp_capabilities(self) -> None:
        capabilities: tuple[SkillCapability, ...] = (
            "document-create",
            "document-edit",
            "folder-organize",
            "template",
        )

        self.assertEqual(len(capabilities), 4)


if __name__ == "__main__":
    unittest.main()
