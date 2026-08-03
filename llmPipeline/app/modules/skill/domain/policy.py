from app.modules.skill.domain.entities import SkillCapability, SkillTool


READ_TOOLS: frozenset[SkillTool] = frozenset(
    {
        "list_root_items",
        "list_folder_children",
        "search_hierarchy",
        "get_breadcrumb",
        "get_document_metadata",
    }
)
FOLDER_MUTATION_TOOLS: frozenset[SkillTool] = frozenset(
    {"create_folder", "rename_folder", "move_folder", "move_document", "rename_document"}
)
CAPABILITY_TOOLS: dict[SkillCapability, frozenset[SkillTool]] = {
    "document-create": frozenset(),
    "document-edit": frozenset(),
    "folder-organize": READ_TOOLS | FOLDER_MUTATION_TOOLS,
    "template": frozenset(),
}


def validate_allowed_tools(
    capabilities: tuple[SkillCapability, ...],
    allowed_tools: tuple[SkillTool, ...],
) -> None:
    allowed_by_capability = frozenset(
        tool for capability in capabilities for tool in CAPABILITY_TOOLS[capability]
    )
    unsupported = set(allowed_tools) - allowed_by_capability
    if unsupported:
        raise ValueError(f"allowed_tools contains unsupported tools: {sorted(unsupported)}")
