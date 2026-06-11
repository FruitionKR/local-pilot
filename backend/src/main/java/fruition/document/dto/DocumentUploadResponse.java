package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.document.domain.DocumentStatus;

import java.time.Instant;

public record DocumentUploadResponse(
        String id,
        String filename,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("byte_size") long byteSize,
        DocumentStatus status,
        @JsonProperty("source_uri") String sourceUri,
        @JsonProperty("uploaded_at") Instant uploadedAt
) {}
