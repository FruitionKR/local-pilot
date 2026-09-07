package fruition.access.workspace.dto;

import fruition.access.workspace.domain.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WorkspaceInvitationCreateRequest(
        @NotBlank(message = "email은 필수입니다.")
        @Email(message = "email 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "email은 255자 이하여야 합니다.")
        @Schema(description = "초대할 이메일 주소", example = "member@example.com")
        String email,

        @NotNull(message = "role은 필수입니다.")
        @Schema(description = "수락 시 부여할 역할", example = "MEMBER", allowableValues = {"OWNER", "MEMBER"})
        WorkspaceRole role
) {}
