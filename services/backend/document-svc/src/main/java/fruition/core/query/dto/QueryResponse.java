package fruition.core.query.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.query.repository.PipelineQueryResponse;

import java.time.Instant;
import java.util.List;

public record QueryResponse(
        @JsonProperty("user_message") MessageSummary userMessage,
        @JsonProperty("assistant_message") MessageSummary assistantMessage,
        @JsonProperty("related_pages") List<PipelineQueryResponse.RelatedPage> relatedPages,
        @JsonProperty("evidence_snippets") List<PipelineQueryResponse.EvidenceSnippet> evidenceSnippets,
        @JsonProperty("graph_context") PipelineQueryResponse.GraphContext graphContext,
        @JsonProperty("traversal_paths") List<PipelineQueryResponse.TraversalPath> traversalPaths,
        @JsonProperty("web_search_requested") boolean webSearchRequested,
        @JsonProperty("web_search_executed") boolean webSearchExecuted,
        @JsonProperty("result_count") int resultCount,
        @JsonProperty("error_code") String errorCode
) {
    public record MessageSummary(
            String id,
            String role,
            String content,
            String status,
            @JsonProperty("created_at") Instant createdAt
    ) {}
}
