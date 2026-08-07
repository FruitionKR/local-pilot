package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentBlockResponse(
        @JsonProperty("block_id") String blockId,
        String text
) {}
