package fruition.core.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatMessageRelatedPageResponse(
        @JsonProperty("wiki_page_id") String wikiPageId,
        @JsonProperty("page_type") String pageType,
        String title,
        String slug,
        @JsonProperty("relevance_score") double relevanceScore,
        String role,
        int depth,
        int rank
) {}
