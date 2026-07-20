from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


DOCUMENT_SEMANTIC_PROMPT = "prompts/semantic_extraction.system.md"
CHAT_SEMANTIC_PROMPT = "prompts/chat_semantic_extraction.system.md"
CHAT_APPEND_SEMANTIC_PROMPT = "prompts/chat_semantic_append.system.md"


class _PipelineRunBase(BaseModel):
    model_config = ConfigDict(extra="forbid")

    document_id: str
    input_name: str | None = None
    out: str | None = None
    mode: Literal["api", "generic-chat"] = "api"
    provider: Literal["upstage", "generic"] = "upstage"
    env_file: str | None = None
    source_page_mode: Literal["auto", "skeleton", "section-polish"] = "auto"
    concept_page_mode: Literal[
        "auto",
        "api",
        "full-llm",
        "skeleton",
        "section-polish",
    ] = Field(
        default="auto",
        description="auto는 backend skeleton concept page만 생성합니다. section-polish를 명시하면 concept별 LLM polish를 수행합니다.",
    )
    max_packet_chars: int = 7000
    overlap_blocks: int = 1
    endpoint: str | None = None
    api_base_url: str | None = None
    api_key_env: str | None = None
    api_key: str | None = None
    model: str | None = None
    temperature: float = 0.2
    timeout_seconds: int = 180
    max_tokens: int | None = None
    json_mode: bool = False
    concept_system_prompt: str = "prompts/concept_page_generation.system.md"
    concept_resolution_system_prompt: str = "prompts/concept_resolution.system.md"
    section_polish_system_prompt: str = "prompts/section_polish.system.md"
    source_accumulation_system_prompt: str = (
        "prompts/source_accumulation_evaluator.system.md"
    )
    wiki_evaluator_system_prompt: str = "prompts/wiki_generation_evaluator.system.md"
    existing_wiki_dir: str | None = None
    wiki_evaluation_loop: bool = True
    max_eval_attempts: int = 2
    save_debug_json: bool = Field(
        default=False,
        description="True이면 raw LLM output, packet, block_map 같은 디버그 JSON을 저장합니다.",
    )
    log_callback_url: str | None = Field(
        default=None,
        description="설정하면 pipeline.log 이벤트가 생길 때마다 이 URL로 JSON POST합니다.",
    )
    wait: bool = Field(
        default=False,
        description="True이면 요청 안에서 완료까지 기다립니다. False이면 백그라운드 실행 후 로그를 조회합니다.",
    )
    user_id: str | None = Field(
        default=None,
        description="기존 backend 요청 호환 필드이며 Wiki 저장 범위에는 사용하지 않습니다.",
    )
    workspace_id: str | None = Field(
        default=None,
        description="기존 backend 요청 호환 필드이며 Wiki 저장 범위에는 사용하지 않습니다.",
    )


class PipelineRunIn(_PipelineRunBase):
    system_prompt: str = DOCUMENT_SEMANTIC_PROMPT


class ChatWikiRunIn(_PipelineRunBase):
    selection_mode: Literal["full", "partial"] = Field(
        description="full은 기존 chat source page에 누적하고, partial은 독립 source page를 생성합니다.",
    )
    input_markdown: str | None = Field(
        default=None,
        description="기존 source page가 있는 full 누적에서 backend가 중복 필터링해 직렬화한 신규 pair Markdown입니다.",
    )
    chat_system_prompt: str = CHAT_SEMANTIC_PROMPT
    chat_append_system_prompt: str = CHAT_APPEND_SEMANTIC_PROMPT


class PipelineRunOut(BaseModel):
    run_id: str
    status: str
    manifest: dict[str, Any] | None = None
    output_dir: str
    log_path: str
