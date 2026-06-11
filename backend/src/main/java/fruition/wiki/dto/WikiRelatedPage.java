package fruition.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WikiRelatedPage(
        String id,
        @JsonProperty("page_type") String pageType,
        String title,
        String slug,
        @JsonProperty("link_type") String linkType,
        String label,
        double confidence
) {}
