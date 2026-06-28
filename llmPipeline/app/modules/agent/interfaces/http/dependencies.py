from functools import lru_cache

from app.modules.agent.application.handle_agent_turn import HandleAgentTurnUseCase
from app.modules.agent.infrastructure.chat_completions_turn_router import build_agent_turn_router
from app.modules.markdown_edit.application.generate_markdown_edit import GenerateMarkdownEditUseCase
from app.modules.markdown_edit.infrastructure.chat_completions_markdown_editor import build_markdown_editor
from app.modules.query.interfaces.http.dependencies import get_answer_query_use_case


@lru_cache(maxsize=1)
def get_handle_agent_turn_use_case() -> HandleAgentTurnUseCase:
    return HandleAgentTurnUseCase(
        router=build_agent_turn_router(),
        query_use_case=get_answer_query_use_case(),
        markdown_edit_use_case=GenerateMarkdownEditUseCase(build_markdown_editor()),
    )
