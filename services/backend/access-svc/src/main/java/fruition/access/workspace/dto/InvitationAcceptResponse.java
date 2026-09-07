package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record InvitationAcceptResponse(
        @JsonProperty("workspace_id")
        @Schema(description = "합류한 워크스페이스 ID", example = "ws_9d47a0e9a6324341b47562553b75f92a")
        String workspaceId,

        @JsonProperty("workspace_name")
        @Schema(description = "합류한 워크스페이스 이름", example = "팀 워크스페이스")
        String workspaceName,

        @Schema(description = "부여된 역할", example = "MEMBER", allowableValues = {"OWNER", "MEMBER"})
        String role
) {}
