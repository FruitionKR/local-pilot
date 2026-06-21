package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record DocumentBlocksResponse(
        @JsonProperty("document_id") String documentId,
        List<DocumentBlockResponse> blocks
) {}
