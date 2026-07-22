package fruition.query.repository;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PipelineQueryResponse(
        String answer,
        @JsonProperty("related_pages") List<RelatedPage> relatedPages,
        @JsonProperty("evidence_snippets") List<EvidenceSnippet> evidenceSnippets,
        @JsonProperty("graph_context") GraphContext graphContext,
        @JsonProperty("traversal_paths") List<TraversalPath> traversalPaths
) {
    public record RelatedPage(
            String id,
            @JsonProperty("page_type") String pageType,
            String title,
            String slug,
            @JsonProperty("relevance_score") double relevanceScore,
            String role,
            int depth
    ) {}

    public record EvidenceSnippet(
            int rank,
            @JsonProperty("source_document_id") String sourceDocumentId,
            @JsonProperty("source_block_ids") List<String> sourceBlockIds,
            @JsonProperty("source_refs") List<SourceRef> sourceRefs,
            String text
    ) {}

    public record SourceRef(
            @JsonProperty("source_document_id") String sourceDocumentId,
            @JsonProperty("source_block_id") String sourceBlockId
    ) {}

    public record GraphContext(
            List<RelatedPage> nodes,
            List<GraphEdge> edges
    ) {}

    public record GraphEdge(
            @JsonProperty("from_page_id") String fromPageId,
            @JsonProperty("to_page_id") String toPageId,
            @JsonProperty("link_type") String linkType,
            String role,
            double score
    ) {}

    public record TraversalPath(
            @JsonProperty("path_id") String pathId,
            String role,
            @JsonProperty("used_for_answer") boolean usedForAnswer,
            double score,
            @JsonProperty("stop_reason") String stopReason,
            List<String> nodes,
            List<GraphEdge> edges
    ) {}
}
