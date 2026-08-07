package fruition.core.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record AgentTurnRequest(
        @NotBlank String documentId,
        @NotNull @Min(0) Long baseVersion,
        @NotBlank String message,
        ConversationContext conversationContext,
        @NotNull @Valid EditorSnapshot editorSnapshot
) {
    public record ConversationContext(
            String recentConversationSummary,
            Map<String, Object> referenceContext
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
