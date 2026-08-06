package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;

import java.time.Instant;

public record DocumentUploadResponse(
        String id,
        String filename,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("byte_size") long byteSize,
        DocumentStatus status,
        @JsonProperty("source_uri") String sourceUri,
        @JsonProperty("uploaded_at") Instant uploadedAt,
        boolean editable,
        @JsonProperty("current_version") long currentVersion,
        @JsonProperty("document_role") DocumentRole documentRole
) {}
