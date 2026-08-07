package fruition.core.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.chat.domain.SourceRef;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessageReference(
        long id,
        @JsonProperty("reference_type") String referenceType,
        Integer rank,
        @JsonProperty("source_document_id") String sourceDocumentId,
        @JsonProperty("source_block_ids") List<String> sourceBlockIds,
        String text,
        @JsonProperty("source_refs") List<SourceRef> sourceRefs
) {}
