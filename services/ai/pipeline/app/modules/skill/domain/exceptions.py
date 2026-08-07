class SkillNotFoundError(ValueError):
    code = "SKILL_NOT_FOUND"

    def __init__(self, skill_reference: str) -> None:
        super().__init__(f"Skill not found: {skill_reference}")


class SkillDisabledError(ValueError):
    code = "SKILL_DISABLED"

    def __init__(self, skill_id: str) -> None:
        super().__init__(f"Skill is disabled: {skill_id}")
