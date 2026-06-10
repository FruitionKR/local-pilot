package fruition.poc.backend.wiki.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WikiGraphNode(
        String id,
        @JsonProperty("page_type") String pageType,
        String title,
        String slug,
        String summary,
        String status,
        @JsonProperty("source_document") SourceDocRef sourceDocument
) {
    public record SourceDocRef(String id, String filename) {}
}
