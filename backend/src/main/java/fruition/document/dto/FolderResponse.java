package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.document.domain.Folder;

import java.time.Instant;
import java.util.UUID;

public record FolderResponse(
        UUID id,
        @JsonProperty("parent_folder_id") UUID parentFolderId,
        String name,
        @JsonProperty("sort_order") long sortOrder,
        @JsonProperty("current_version") long currentVersion,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
) {
    public static FolderResponse from(Folder folder) {
        return new FolderResponse(
                folder.getId(),
                folder.getParentFolderId(),
                folder.getName(),
                folder.getSortOrder(),
                folder.getCurrentVersion(),
                folder.getCreatedAt(),
                folder.getUpdatedAt()
        );
    }
}
