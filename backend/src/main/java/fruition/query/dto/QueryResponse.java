package fruition.query.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.query.repository.PipelineQueryResponse;

import java.time.Instant;
import java.util.List;

public record QueryResponse(
        @JsonProperty("user_message") MessageSummary userMessage,
        @JsonProperty("assistant_message") MessageSummary assistantMessage,
        @JsonProperty("related_pages") List<PipelineQueryResponse.RelatedPage> relatedPages,
        @JsonProperty("evidence_snippets") List<PipelineQueryResponse.EvidenceSnippet> evidenceSnippets,
        @JsonProperty("graph_context") PipelineQueryResponse.GraphContext graphContext,
        @JsonProperty("traversal_paths") List<PipelineQueryResponse.TraversalPath> traversalPaths
) {
    public record MessageSummary(
            String id,
            String role,
            String content,
            String status,
            @JsonProperty("created_at") Instant createdAt
    ) {}
}
