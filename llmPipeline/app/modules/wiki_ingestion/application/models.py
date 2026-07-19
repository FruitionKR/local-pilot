from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class PipelineRunRegistration:
    run_id: str
    document_id: str | None
    input_source: str
    output_dir: str
    mode: str


@dataclass(frozen=True)
class PipelineRunCommand:
    run_id: str | None
    input: str
    input_name: str
    out: str
    user_id: str
    workspace_id: str
    source_document_id: str | None = None
    selection_mode: str | None = None
    input_markdown: str | None = None
    mode: str = "api"
    provider: str = "upstage"
    env_file: str | None = None
    source_page_mode: str = "auto"
    concept_page_mode: str = "auto"
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
    system_prompt: str = "prompts/semantic_extraction.system.md"
    concept_system_prompt: str = "prompts/concept_page_generation.system.md"
    concept_resolution_system_prompt: str = "prompts/concept_resolution.system.md"
    section_polish_system_prompt: str = "prompts/section_polish.system.md"
    source_accumulation_system_prompt: str = "prompts/source_accumulation_evaluator.system.md"
    wiki_evaluator_system_prompt: str = "prompts/wiki_generation_evaluator.system.md"
    wiki_patch_system_prompt: str = "prompts/wiki_generation_patch.system.md"
    existing_wiki_dir: str | None = None
    existing_concept_index: list[dict[str, Any]] = field(default_factory=list)
    existing_source_artifact: dict[str, Any] | None = None
    existing_source_markdown: str | None = None
    wiki_evaluation_loop: bool = True
    max_eval_attempts: int = 2
    save_debug_json: bool = False
    log_path: str | None = None
    log_callback_url: str | None = None
