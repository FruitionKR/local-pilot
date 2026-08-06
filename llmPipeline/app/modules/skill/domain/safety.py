import re
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
    "policy_weakening": (
        "시스템 정책을 무시",
        "이전 지시를 무시",
        "ignore system policy",
        "ignore previous instructions",
        "forget previous instructions",
    ),
    "hidden_prompt": (
        "시스템 프롬프트를 보여",
        "시스템 프롬프트를 출력",
        "reveal system prompt",
        "show system prompt",
    ),
    "role_override": ("act as system", "developer message로 행동", "시스템 역할로 행동"),
}
CREDENTIAL_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", re.IGNORECASE),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b"),
    re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b"),
    re.compile(
        r"\b(?:api[_ -]?key|access[_ -]?token|client[_ -]?secret|password)\s*[:=]\s*['\"]?[^\s'\"]{8,}",
        re.IGNORECASE,
    ),
)


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
    if any(pattern.search(instructions_markdown) for pattern in CREDENTIAL_PATTERNS):
        issues.append(
            SkillSafetyIssue(
                category="credential",
                text="[credential]",
                reason="Skill에는 인증정보를 포함할 수 없습니다.",
            )
        )
    return tuple(issues)
