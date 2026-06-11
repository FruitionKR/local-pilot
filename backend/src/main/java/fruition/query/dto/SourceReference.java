package fruition.query.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceReference(
        @JsonProperty("document_id") String documentId,
        String filename,
        @JsonProperty("page_number") Integer pageNumber,
        @JsonProperty("paragraph_index") Integer paragraphIndex,
        String quote
) {}
