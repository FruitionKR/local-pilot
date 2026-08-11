from functools import lru_cache
import os

from app.modules.agent.application.handle_agent_turn import HandleAgentTurnUseCase
from app.modules.agent.infrastructure.chat_completions_turn_router import build_agent_turn_router
from app.modules.agent_run.application.start_agent_run import StartAgentRunUseCase
from app.modules.agent_run.infrastructure.postgres_agent_run_repository import PostgresAgentRunRepository
from app.modules.markdown_edit.application.generate_markdown_document import GenerateMarkdownDocumentUseCase
from app.modules.markdown_edit.application.generate_markdown_edit import GenerateMarkdownEditUseCase
from app.modules.markdown_edit.infrastructure.chat_completions_markdown_editor import build_markdown_editor
from app.modules.query.interfaces.http.dependencies import (
    get_answer_query_use_case,
    get_conversation_summarizer,
)
from app.modules.skill.application.select_skill import SelectSkillUseCase
from app.modules.skill.infrastructure.postgres_skill_repository import PostgresSkillRepository
from app.modules.skill.interfaces.http.dependencies import (
    get_author_skill_use_case,
    get_propose_skill_draft_use_case,
)


@lru_cache(maxsize=1)
def get_handle_agent_turn_use_case() -> HandleAgentTurnUseCase:
    markdown_editor = build_markdown_editor()
    feature_enabled = os.environ.get("AGENT_SKILLS_ENABLED", "false").lower() in {"1", "true", "yes", "on"}
    return HandleAgentTurnUseCase(
        router=build_agent_turn_router(),
        query_use_case=get_answer_query_use_case(),
        markdown_edit_use_case=GenerateMarkdownEditUseCase(markdown_editor),
        markdown_create_use_case=GenerateMarkdownDocumentUseCase(markdown_editor),
        skill_selector=SelectSkillUseCase(PostgresSkillRepository(), feature_enabled=feature_enabled),
        agent_run_starter=StartAgentRunUseCase(PostgresAgentRunRepository(), feature_enabled=feature_enabled),
        skill_authorer=(get_author_skill_use_case() if feature_enabled else None),
        skill_draft_proposer=(
            get_propose_skill_draft_use_case()
            if feature_enabled
            else None
        ),
        conversation_summarizer=get_conversation_summarizer(),
    )
