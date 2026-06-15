package fruition.query.repository;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PipelineQueryResponse(
        String answer,
        @JsonProperty("related_pages") List<RelatedPage> relatedPages,
        @JsonProperty("evidence_snippets") List<EvidenceSnippet> evidenceSnippets,
        @JsonProperty("graph_context") GraphContext graphContext,
        @JsonProperty("traversal_paths") List<TraversalPath> traversalPaths,
        @JsonProperty("retrieval_summary") RetrievalSummary retrievalSummary
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
            @JsonProperty("page_id") String pageId,
            @JsonProperty("page_type") String pageType,
            @JsonProperty("page_title") String pageTitle,
            @JsonProperty("page_slug") String pageSlug,
            @JsonProperty("page_url") String pageUrl,
            @JsonProperty("page_role") String pageRole,
            String text,
            double score,
            int rank,
            @JsonProperty("paragraph_index") Integer paragraphIndex,
            @JsonProperty("sentence_index") Integer sentenceIndex
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

    public record RetrievalSummary(
            @JsonProperty("source_candidate_count") int sourceCandidateCount,
            @JsonProperty("concept_candidate_count") int conceptCandidateCount,
            @JsonProperty("visited_node_count") int visitedNodeCount,
            @JsonProperty("returned_node_count") int returnedNodeCount,
            @JsonProperty("used_source_count") int usedSourceCount,
            @JsonProperty("used_concept_count") int usedConceptCount,
            @JsonProperty("max_depth") int maxDepth,
            @JsonProperty("stop_reason") String stopReason
    ) {}
}
