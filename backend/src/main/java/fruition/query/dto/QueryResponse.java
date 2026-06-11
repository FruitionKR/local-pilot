package fruition.query.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record QueryResponse(
        @JsonProperty("user_message") MessageSummary userMessage,
        @JsonProperty("assistant_message") MessageSummary assistantMessage,
        @JsonProperty("related_pages") List<QueryRelatedPage> relatedPages,
        @JsonProperty("source_references") List<SourceReference> sourceReferences,
        @JsonProperty("highlighted_paths") List<HighlightedPath> highlightedPaths
) {
    public record MessageSummary(
            String id,
            String role,
            String content,
            String status,
            @JsonProperty("created_at") Instant createdAt
    ) {}
}
