package fruition.poc.backend.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentWikiPageRef(
        String id,
        @JsonProperty("page_type") String pageType,
        String title,
        String slug,
        @JsonProperty("relation_type") String relationType,
        Double confidence
) {}
