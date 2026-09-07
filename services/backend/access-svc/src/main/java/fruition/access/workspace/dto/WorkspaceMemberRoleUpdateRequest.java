package fruition.access.workspace.dto;

import fruition.access.workspace.domain.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record WorkspaceMemberRoleUpdateRequest(
        @NotNull(message = "role은 필수입니다.")
        @Schema(description = "새 역할", example = "OWNER", allowableValues = {"OWNER", "MEMBER"})
        WorkspaceRole role
) {}
