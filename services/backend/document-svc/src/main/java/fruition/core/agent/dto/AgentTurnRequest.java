package fruition.core.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record AgentTurnRequest(
        @NotBlank String documentId,
        @NotNull @Min(0) Long baseVersion,
        @NotBlank String message,
        String provider,
        String model,
        ConversationContext conversationContext,
        @JsonProperty("skill_draft_sources") @Size(max = 3) List<@Valid SkillDraftSourceSelector> skillDraftSources,
        @JsonProperty("skill_draft_user_directives") List<String> skillDraftUserDirectives,
        @JsonProperty("skill_draft_excluded_literals") List<String> skillDraftExcludedLiterals,
        @JsonProperty("skill_scope_type") String skillScopeType,
        @NotNull @Valid EditorSnapshot editorSnapshot
) {
    public AgentTurnRequest(String documentId, Long baseVersion, String message, String provider, String model,
                            ConversationContext conversationContext, EditorSnapshot editorSnapshot) {
        this(documentId, baseVersion, message, provider, model, conversationContext,
                List.of(), List.of(), List.of(), null, editorSnapshot);
    }

    public AgentTurnRequest {
        skillDraftSources = skillDraftSources == null ? List.of() : List.copyOf(skillDraftSources);
        skillDraftUserDirectives = skillDraftUserDirectives == null ? List.of() : List.copyOf(skillDraftUserDirectives);
        skillDraftExcludedLiterals = skillDraftExcludedLiterals == null ? List.of() : List.copyOf(skillDraftExcludedLiterals);
    }
    public record ConversationContext(
            String recentConversationSummary,
            Map<String, Object> referenceContext,
            PendingSkillProposal pendingSkillProposal
    ) {
        public record PendingSkillProposal(
                @JsonProperty("scope_type") String scopeType,
                String name,
                String description,
                @JsonProperty("instructions_markdown") String instructionsMarkdown
        ) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillDraftSourceSelector(
            @JsonProperty("run_id") @NotBlank String runId
    ) {}

    public record EditorSnapshot(
            @NotNull String markdown,
            @Valid Target target
    ) {}

    public record Target(
            @NotBlank String type,
            @Min(1) int startLine,
            @Min(1) int endLine
    ) {}
}
