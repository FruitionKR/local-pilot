from dataclasses import dataclass


@dataclass(frozen=True)
class SkillSafetyIssue:
    category: str
    text: str
    reason: str
    severity: str = "blocked"


BLOCKED_INSTRUCTION_MARKERS = {
    "approval_bypass": ("승인 없이", "승인을 생략", "bypass approval", "without approval"),
    "permission_escalation": ("권한을 무시", "권한 우회", "ignore permission", "bypass permission"),
    "forbidden_tool": ("shell 실행", "sql 실행", "run shell", "execute sql"),
    "policy_weakening": ("시스템 정책을 무시", "ignore system policy", "ignore previous instructions"),
}


def inspect_skill_instructions(instructions_markdown: str) -> tuple[SkillSafetyIssue, ...]:
    lowered = instructions_markdown.lower()
    issues: list[SkillSafetyIssue] = []
    for category, markers in BLOCKED_INSTRUCTION_MARKERS.items():
        marker = next((candidate for candidate in markers if candidate in lowered), None)
        if marker:
            issues.append(
                SkillSafetyIssue(
                    category=category,
                    text=marker,
                    reason="Skill은 시스템 권한·승인·tool 정책을 변경할 수 없습니다.",
                )
            )
    return tuple(issues)
