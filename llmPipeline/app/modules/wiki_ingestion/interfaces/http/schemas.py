from typing import Any, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.modules.wiki_ingestion.application.models import (
    IngestOperationRestoreCommand,
    LintOperationRestoreCommand,
    RebuildPageCommand,
    RestoreContributionCommand,
    SourceSnapshotRestoreCommand,
    WikiMaintenanceCommand,
)


DOCUMENT_SEMANTIC_PROMPT = "prompts/semantic_extraction.system.md"
CHAT_SEMANTIC_PROMPT = "prompts/chat_semantic_extraction.system.md"
CHAT_APPEND_SEMANTIC_PROMPT = "prompts/chat_semantic_append.system.md"


class _PipelineRunBase(BaseModel):
    model_config = ConfigDict(extra="forbid")

    document_id: str
    operation_id: str | None = None
    result_callback_url: str | None = None
    input_name: str | None = None
    out: str | None = None
    mode: Literal["api", "generic-chat"] = "api"
    provider: Literal["openai", "gemini", "claude", "upstage", "generic"] | None = None
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

    @model_validator(mode="after")
    def validate_operation_result_contract(self) -> Self:
        if bool(self.operation_id) != bool(self.result_callback_url):
            raise ValueError(
                "operation_id and result_callback_url must be provided together"
            )
        return self


class PipelineRunIn(_PipelineRunBase):
    system_prompt: str = DOCUMENT_SEMANTIC_PROMPT


class ReingestRunIn(_PipelineRunBase):
    input_markdown: str
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


class RestoreContributionIn(BaseModel):
    operation_id: str
    document_id: str


class RebuildPageIn(BaseModel):
    page_id: str
    keep_contributions: list[RestoreContributionIn]


class SourceSnapshotRestoreIn(BaseModel):
    page_id: str
    restore_from_operation_id: str | None


class _OperationRestoreIn(BaseModel):
    operation_id: str
    target_operation_id: str
    workspace_id: str
    result_callback_url: str
    rebuild_pages: list[RebuildPageIn]
    deleted_pages: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_operation_restore(self) -> Self:
        if not self.result_callback_url.strip():
            raise ValueError("result_callback_url must not be blank")
        if self.operation_id == self.target_operation_id:
            raise ValueError(
                "operation_id and target_operation_id must be different"
            )
        target_operation_id = self.target_operation_id
        if any(
            item.operation_id == target_operation_id
            for page in self.rebuild_pages
            for item in page.keep_contributions
        ):
            raise ValueError(
                "target_operation_id must not be included in keep_contributions"
            )
        return self

    def rebuild_commands(self) -> tuple[RebuildPageCommand, ...]:
        return tuple(
            RebuildPageCommand(
                page_id=page.page_id,
                keep_contributions=tuple(
                    RestoreContributionCommand(
                        operation_id=item.operation_id,
                        document_id=item.document_id,
                    )
                    for item in page.keep_contributions
                ),
            )
            for page in self.rebuild_pages
        )


class IngestOperationRestoreIn(_OperationRestoreIn):
    source_page: SourceSnapshotRestoreIn

    @model_validator(mode="after")
    def validate_source_snapshot(self) -> Self:
        if (
            self.source_page.restore_from_operation_id
            == self.target_operation_id
        ):
            raise ValueError(
                "target_operation_id cannot be restored as source snapshot"
            )
        return self

    def to_command(self) -> IngestOperationRestoreCommand:
        return IngestOperationRestoreCommand(
            operation_id=self.operation_id,
            target_operation_id=self.target_operation_id,
            workspace_id=self.workspace_id,
            result_callback_url=self.result_callback_url,
            source_page=SourceSnapshotRestoreCommand(
                page_id=self.source_page.page_id,
                restore_from_operation_id=(
                    self.source_page.restore_from_operation_id
                ),
            ),
            rebuild_pages=self.rebuild_commands(),
            deleted_pages=tuple(self.deleted_pages),
        )


class LintOperationRestoreIn(_OperationRestoreIn):
    def to_command(self) -> LintOperationRestoreCommand:
        return LintOperationRestoreCommand(
            operation_id=self.operation_id,
            target_operation_id=self.target_operation_id,
            workspace_id=self.workspace_id,
            result_callback_url=self.result_callback_url,
            rebuild_pages=self.rebuild_commands(),
            deleted_pages=tuple(self.deleted_pages),
        )


class WikiLintIn(BaseModel):
    user_id: str = "local-user"
    workspace_id: str = "local-workspace"
    operation_id: str | None = None
    materialize_promotions: bool = False
    dry_run: bool = True
    provider: Literal["openai", "gemini", "claude", "upstage", "generic"] | None = None
    endpoint: str | None = None
    api_base_url: str | None = None
    api_key_env: str | None = None
    api_key: str | None = None
    model: str | None = None
    temperature: float = 0.2
    timeout_seconds: int = 180
    max_tokens: int | None = None

    @model_validator(mode="after")
    def validate_operation_id(self) -> Self:
        if not self.dry_run and not str(self.operation_id or "").strip():
            raise ValueError("operation_id is required when dry_run is false")
        return self

    def to_command(self) -> WikiMaintenanceCommand:
        return WikiMaintenanceCommand(**self.model_dump())


class WikiLintOut(BaseModel):
    user_id: str
    workspace_id: str
    operation_id: str | None = None
    active_path: str
    cluster_count: int
    source_ref_count: int
    orphan_refs: list[str]
    promotion_candidates: list[str]
    needs_review: list[str]
    relation_candidates: list[dict[str, Any]]
    invalid_relations: list[dict[str, Any]]
    invalid_promotions: list[dict[str, Any]]
    reconciliation_candidates: list[dict[str, Any]]
    applied_reconciliations: list[dict[str, Any]]
    applied_cluster_reconciliation: dict[str, list[dict[str, Any]]]
    materialized_promotions: list[dict[str, Any]]
    merged_promotions: list[dict[str, Any]]
    materialized_relations: list[dict[str, Any]]
    orphan_link_candidates: list[dict[str, Any]] = Field(default_factory=list)
    removed_orphan_links: list[dict[str, Any]] = Field(default_factory=list)
    operation_artifacts: list[dict[str, Any]] = Field(default_factory=list)
    changed_pages: list[dict[str, Any]] = Field(default_factory=list)
