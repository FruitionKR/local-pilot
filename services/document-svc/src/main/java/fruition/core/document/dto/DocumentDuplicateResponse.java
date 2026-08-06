package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record DocumentDuplicateResponse(
        String id,
        String filename,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("byte_size") long byteSize,
        @JsonProperty("current_version") long currentVersion,
        @JsonProperty("folder_id") UUID folderId,
        @JsonProperty("source_document_id") String sourceDocumentId,
        @JsonProperty("sort_order") long sortOrder
) {
}
