package fruition.core.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WikiGraphEdge(
        @JsonProperty("from_page_id") String fromPageId,
        @JsonProperty("to_page_id") String toPageId,
        @JsonProperty("link_type") String linkType,
        String label,
        double confidence
) {}
