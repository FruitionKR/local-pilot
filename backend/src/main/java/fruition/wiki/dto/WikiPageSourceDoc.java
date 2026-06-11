package fruition.wiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WikiPageSourceDoc(
        String id,
        String filename,
        @JsonProperty("source_uri") String sourceUri,
        @JsonProperty("relation_type") String relationType,
        double confidence
) {}
