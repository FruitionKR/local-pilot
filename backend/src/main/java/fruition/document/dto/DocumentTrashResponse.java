package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.document.domain.DocumentRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentTrashResponse(List<DocumentTrashItem> documents) {

    public record DocumentTrashItem(
            String id,
            String filename,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("document_role") DocumentRole documentRole,
            @JsonProperty("current_version") long currentVersion,
            @JsonProperty("deleted_at") Instant deletedAt,
            @JsonProperty("deleted_by") String deletedBy,
            @JsonProperty("delete_operation_id") UUID deleteOperationId,
            @JsonProperty("source_document_id") String sourceDocumentId
    ) {
    }
}
