package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record WorkspaceTrashResponse(List<WorkspaceTrashItem> workspaces) {

    public record WorkspaceTrashItem(
            String id,
            String name,
            @JsonProperty("deleted_at") Instant deletedAt,
            @JsonProperty("deleted_by") String deletedBy
    ) {
    }
}
