package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Schema(description = "Markdown Agent 편집 요청. 접수되면 202로 돌아오고 실제 편집안은 비동기로 만들어진다. "
        + "skill_* 필드는 snake_case이고 나머지는 camelCase다.")
public record AgentTurnRequest(
        @NotBlank
        @Schema(description = "편집 대상 문서 ID", example = "doc_1b9f4c7e2a8d4f1e6c3b0a97d25e4f83")
        String documentId,

        @NotNull @Min(0)
        @Schema(description = "편집 기준으로 삼을 문서 버전", minimum = "0", example = "3")
        Long baseVersion,

        @NotBlank
        @Schema(description = "사용자 지시문", example = "이 문단을 표로 정리해줘")
        String message,

        @Schema(description = "LLM provider. model과 짝을 이뤄야 하며 생략하면 설정값을 쓴다.",
                allowableValues = {"openai", "gemini", "claude"}, example = "openai")
        String provider,

        @Schema(description = "모델명. provider와 짝을 이뤄야 한다.", example = "gpt-5-nano")
        String model,

        @JsonProperty("skill_mode") @Pattern(regexp = "auto|explicit|off")
        @Schema(description = "Skill 적용 방식. explicit이면 skill_id가 필수이고, auto·off이면 skill_id를 넣을 수 없다.",
                allowableValues = {"auto", "explicit", "off"}, defaultValue = "auto", example = "auto")
        String skillMode,

        @JsonProperty("skill_id") @Size(max = 128)
        @Schema(description = "explicit 모드에서 적용할 Skill ID(128자 이하)", maxLength = 128)
        String skillId,

        @Schema(description = "이전 대화 맥락. 있으면 편집 판단에 함께 쓴다.")
        ConversationContext conversationContext,

        @JsonProperty("skill_draft_sources") @Size(max = 3)
        @Schema(description = "Skill 초안을 뽑아낼 이전 run 목록(최대 3개)")
        List<@Valid SkillDraftSourceSelector> skillDraftSources,

        @JsonProperty("skill_draft_user_directives")
        @Schema(description = "Skill 초안에 반영할 사용자 지시 목록")
        List<String> skillDraftUserDirectives,

        @JsonProperty("skill_draft_excluded_literals")
        @Schema(description = "Skill 초안에서 빼야 하는 리터럴 목록")
        List<String> skillDraftExcludedLiterals,

        @JsonProperty("skill_scope_type")
        @Schema(description = "Skill을 적용할 범위 종류")
        String skillScopeType,

        @NotNull @Valid
        @Schema(description = "편집 시작 시점의 에디터 상태")
        EditorSnapshot editorSnapshot
) {
    private static final int MAX_SKILL_ID_LENGTH = 128;
    private static final Set<String> SKILL_MODES = Set.of("auto", "explicit", "off");

    public AgentTurnRequest(String documentId, Long baseVersion, String message, String provider, String model,
                            ConversationContext conversationContext, EditorSnapshot editorSnapshot) {
        this(documentId, baseVersion, message, provider, model, "auto", null, conversationContext,
                List.of(), List.of(), List.of(), null, editorSnapshot);
    }

    public AgentTurnRequest(String documentId, Long baseVersion, String message, String provider, String model,
                            ConversationContext conversationContext,
                            List<SkillDraftSourceSelector> skillDraftSources,
                            List<String> skillDraftUserDirectives,
                            List<String> skillDraftExcludedLiterals,
                            String skillScopeType,
                            EditorSnapshot editorSnapshot) {
        this(documentId, baseVersion, message, provider, model, "auto", null, conversationContext,
                skillDraftSources, skillDraftUserDirectives, skillDraftExcludedLiterals, skillScopeType,
                editorSnapshot);
    }

    public AgentTurnRequest {
        skillMode = skillMode == null ? "auto" : skillMode;
        if (!SKILL_MODES.contains(skillMode)) {
            throw new IllegalArgumentException("skill_mode must be auto, explicit, or off");
        }
        if (skillId != null && (skillId.isBlank() || skillId.length() > MAX_SKILL_ID_LENGTH)) {
            throw new IllegalArgumentException("skill_id must be nonblank and at most "
                    + MAX_SKILL_ID_LENGTH + " characters");
        }
        if ("explicit".equals(skillMode) && skillId == null) {
            throw new IllegalArgumentException("explicit skill_mode requires skill_id");
        }
        if ("auto".equals(skillMode) && skillId != null) {
            throw new IllegalArgumentException("auto skill_mode must not include skill_id");
        }
        if ("off".equals(skillMode) && skillId != null) {
            throw new IllegalArgumentException("off skill_mode must not include skill_id");
        }
        skillDraftSources = skillDraftSources == null ? List.of() : List.copyOf(skillDraftSources);
        skillDraftUserDirectives = skillDraftUserDirectives == null ? List.of() : List.copyOf(skillDraftUserDirectives);
        skillDraftExcludedLiterals = skillDraftExcludedLiterals == null ? List.of() : List.copyOf(skillDraftExcludedLiterals);
    }

    @Schema(description = "이전 대화 맥락")
    public record ConversationContext(
            @Schema(description = "최근 대화 요약")
            String recentConversationSummary,

            @Schema(description = "참조 문맥. 키·값 형태의 자유 구조다.")
            Map<String, Object> referenceContext,

            @Schema(description = "사용자 확인을 기다리는 Skill 제안")
            PendingSkillProposal pendingSkillProposal
    ) {
        @Schema(description = "사용자 확인 대기 중인 Skill 제안")
        public record PendingSkillProposal(
                @JsonProperty("scope_type")
                @Schema(description = "제안된 Skill의 적용 범위 종류")
                String scopeType,

                @Schema(description = "제안된 Skill 이름")
                String name,

                @Schema(description = "제안된 Skill 설명")
                String description,

                @JsonProperty("instructions_markdown")
                @Schema(description = "제안된 Skill의 지침 Markdown")
                String instructionsMarkdown
        ) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "Skill 초안의 출처가 되는 이전 run")
    public record SkillDraftSourceSelector(
            @JsonProperty("run_id") @NotBlank
            @Schema(description = "초안 근거로 쓸 Agent run ID")
            String runId
    ) {}

    @Schema(description = "편집 시작 시점의 에디터 상태")
    public record EditorSnapshot(
            @NotNull
            @Schema(description = "에디터에 열려 있는 전체 Markdown 본문")
            String markdown,

            @Valid
            @Schema(description = "편집 대상 범위. 생략하면 문서 전체가 대상이다.")
            Target target
    ) {}

    @Schema(description = "편집 대상 범위(1-based 줄 번호)")
    public record Target(
            @NotBlank
            @Schema(description = "범위 종류", example = "selection")
            String type,

            @Min(1)
            @Schema(description = "시작 줄 번호(1부터)", minimum = "1", example = "10")
            int startLine,

            @Min(1)
            @Schema(description = "끝 줄 번호(1부터, 포함)", minimum = "1", example = "24")
            int endLine
    ) {}
}
