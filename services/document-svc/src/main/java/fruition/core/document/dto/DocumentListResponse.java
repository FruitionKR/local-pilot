package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.domain.DocumentProcessingState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.domain.DocumentStatus;

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
            @JsonProperty("error_message") String errorMessage,
            @JsonProperty("pipeline_run_id") String pipelineRunId,
            @JsonProperty("processing_state") DocumentProcessingState processingState,
            @JsonProperty("processing_stage") String processingStage,
            String area,
            @JsonProperty("item_kind") String itemKind,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("file_type") String fileType,
            @JsonProperty("document_role") DocumentRole documentRole,
            boolean editable,
            @JsonProperty("current_version") long currentVersion,
            @JsonProperty("source_document_id") String sourceDocumentId,
            @JsonProperty("updated_at") Instant updatedAt
    ) {}
}
