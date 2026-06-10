package fruition.poc.backend.query.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QueryRelatedPage(
        String id,
        @JsonProperty("page_type") String pageType,
        String title,
        String slug,
        @JsonProperty("relevance_score") double relevanceScore
) {}
