from typing import cast

from app.modules.skill.application.manage_skill import ManageSkillUseCase
from app.modules.skill.application.ports import (
    SkillAuthoringGeneratorPort,
    SkillReferenceReaderPort,
)
from app.modules.skill.domain.entities import (
    SkillAuthoringReference,
    SkillAuthoringResult,
    SkillCapability,
    SkillScopeType,
    SkillTool,
)
from app.modules.skill.domain.policy import (
    CAPABILITY_TOOLS,
    validate_allowed_tools,
    with_required_planning_reads,
)
from app.modules.skill.domain.safety import inspect_skill_instructions


MAX_INSTRUCTION_CHARS = 4_000
MAX_REFERENCE_COUNT = 3
MAX_REFERENCE_CHARS = 40_000
MAX_TOTAL_REFERENCE_CHARS = 80_000
MAX_NAME_CHARS = 100
MAX_DESCRIPTION_CHARS = 500
MAX_INSTRUCTIONS_CHARS = 30_000
MAX_INSTRUCTIONS_LINES = 500
MAX_QUESTION_CHARS = 500


class AuthorSkillUseCase:
    def __init__(
        self,
        generator: SkillAuthoringGeneratorPort,
        reference_reader: SkillReferenceReaderPort,
        skill_manager: ManageSkillUseCase,
    ) -> None:
        self._generator = generator
        self._reference_reader = reference_reader
        self._skill_manager = skill_manager

    def execute(
        self,
        *,
        workspace_id: str,
        user_id: str,
        scope_type: SkillScopeType,
        instruction: str,
        reference_document_ids: tuple[str, ...],
    ) -> SkillAuthoringResult:
        instruction = instruction.strip()
        if not instruction or len(instruction) > MAX_INSTRUCTION_CHARS:
            raise ValueError(f"instruction must contain 1-{MAX_INSTRUCTION_CHARS} characters.")
        if len(reference_document_ids) > MAX_REFERENCE_COUNT:
            raise ValueError(f"reference_document_ids supports at most {MAX_REFERENCE_COUNT} documents.")
        if len(set(reference_document_ids)) != len(reference_document_ids):
            raise ValueError("reference_document_ids must not contain duplicates.")
        if any(not document_id.strip() for document_id in reference_document_ids):
            raise ValueError("reference_document_ids must contain non-empty ids.")
        if inspect_skill_instructions(instruction):
            raise ValueError("Skill authoring request contains blocked safety instructions.")

        references = tuple(
            self._reference_reader.read(
                workspace_id=workspace_id,
                user_id=user_id,
                document_id=document_id,
            )
            for document_id in reference_document_ids
        )
        _validate_references(references)

        candidate = self._generator.generate(instruction, references)
        status = candidate.get("status")
        if status == "clarification_required":
            question = _required_text(candidate, "question", MAX_QUESTION_CHARS)
            if inspect_skill_instructions(question):
                raise ValueError("Skill authoring question contains blocked safety instructions.")
            return SkillAuthoringResult(
                status="clarification_required",
                question=question,
            )
        if status != "draft_created":
            raise ValueError("Skill authoring result has an unsupported status.")

        name = _required_text(candidate, "name", MAX_NAME_CHARS)
        description = _required_text(candidate, "description", MAX_DESCRIPTION_CHARS)
        instructions = _required_text(candidate, "instructions_markdown", MAX_INSTRUCTIONS_CHARS)
        if len(instructions.splitlines()) > MAX_INSTRUCTIONS_LINES:
            raise ValueError(f"Skill instructions support at most {MAX_INSTRUCTIONS_LINES} lines.")
        capabilities = _capabilities(candidate.get("capabilities"))
        allowed_tools = with_required_planning_reads(_tools(candidate.get("allowed_tools")))
        validate_allowed_tools(capabilities, allowed_tools)
        if inspect_skill_instructions("\n".join((name, description, instructions))):
            raise ValueError("Generated Skill contains blocked safety instructions.")
        _reject_reference_literals(
            (name, description, instructions),
            tuple(reference.id for reference in references),
        )

        skill = self._skill_manager.create_draft(
            workspace_id=workspace_id,
            user_id=user_id,
            scope_type=scope_type,
            slug=_required_text(candidate, "slug", 63),
            name=name,
            description=description,
            instructions_markdown=instructions,
            capabilities=capabilities,
            allowed_tools=allowed_tools,
        )
        return SkillAuthoringResult(status="draft_created", skill=skill)


def _validate_references(references: tuple[SkillAuthoringReference, ...]) -> None:
    total_chars = 0
    for reference in references:
        markdown = reference.markdown
        if not markdown.strip():
            raise ValueError("Reference document must contain Markdown.")
        if len(markdown) > MAX_REFERENCE_CHARS:
            raise ValueError(f"Each reference document supports at most {MAX_REFERENCE_CHARS} characters.")
        total_chars += len(markdown)
        if inspect_skill_instructions(f"{reference.name}\n{markdown}"):
            raise ValueError("Reference document contains blocked safety instructions.")
    if total_chars > MAX_TOTAL_REFERENCE_CHARS:
        raise ValueError(f"Reference documents support at most {MAX_TOTAL_REFERENCE_CHARS} characters in total.")


def _required_text(candidate: dict[str, object], key: str, max_chars: int) -> str:
    value = candidate.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"Skill authoring result {key} is required.")
    normalized = value.strip()
    if len(normalized) > max_chars:
        raise ValueError(f"Skill authoring result {key} supports at most {max_chars} characters.")
    return normalized


def _capabilities(value: object) -> tuple[SkillCapability, ...]:
    if not isinstance(value, list) or not value or not all(isinstance(item, str) for item in value):
        raise ValueError("Skill authoring result capabilities are required.")
    if any(item not in CAPABILITY_TOOLS for item in value):
        raise ValueError("Skill authoring result contains an unsupported capability.")
    return tuple(cast(SkillCapability, item) for item in value)


def _tools(value: object) -> tuple[SkillTool, ...]:
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise ValueError("Skill authoring result allowed_tools must be an array.")
    known_tools = {tool for tools in CAPABILITY_TOOLS.values() for tool in tools}
    if any(item not in known_tools for item in value):
        raise ValueError("Skill authoring result contains an unsupported tool.")
    return tuple(cast(SkillTool, item) for item in value)


def _reject_reference_literals(values: tuple[str, ...], literals: tuple[str, ...]) -> None:
    output = "\n".join(values).casefold()
    if any(literal.strip() and literal.strip().casefold() in output for literal in literals):
        raise ValueError("Generated Skill contains a fixed reference value.")
