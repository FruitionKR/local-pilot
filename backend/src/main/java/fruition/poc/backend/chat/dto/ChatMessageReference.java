package fruition.poc.backend.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessageReference(
        long id,
        @JsonProperty("reference_type") String referenceType,
        @JsonProperty("wiki_page_id") String wikiPageId,
        @JsonProperty("document_id") String documentId,
        @JsonProperty("relevance_score") double relevanceScore,
        @JsonProperty("page_number") Integer pageNumber,
        @JsonProperty("paragraph_index") Integer paragraphIndex,
        String quote
) {}
