package fruition.poc.backend.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.poc.backend.document.domain.DocumentStatus;

import java.time.Instant;
import java.util.List;

public record DocumentListResponse(List<DocumentItem> documents) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentItem(
            String id,
            String filename,
            @JsonProperty("mime_type") String mimeType,
            @JsonProperty("byte_size") long byteSize,
            DocumentStatus status,
            @JsonProperty("source_uri") String sourceUri,
            @JsonProperty("extracted_text_uri") String extractedTextUri,
            @JsonProperty("uploaded_at") Instant uploadedAt,
            @JsonProperty("processed_at") Instant processedAt,
            @JsonProperty("error_message") String errorMessage
    ) {}
}
