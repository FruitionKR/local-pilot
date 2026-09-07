package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.access.workspace.domain.WorkspaceInvitation;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record WorkspaceInvitationResponse(
        @JsonProperty("invitation_id")
        @Schema(description = "초대 ID", example = "inv_3c1d8e2f4a5b4c6d8e9f0a1b2c3d4e5f")
        String invitationId,

        @Schema(description = "초대한 이메일 주소", example = "member@example.com")
        String email,

        @Schema(description = "수락 시 부여할 역할", example = "MEMBER",
                allowableValues = {"OWNER", "MEMBER"})
        String role,

        @JsonProperty("expires_at")
        @Schema(description = "초대 만료 시각(ISO-8601 UTC)", example = "2026-09-14T04:25:24.371948Z")
        Instant expiresAt,

        @JsonProperty("invited_at")
        @Schema(description = "초대 생성 시각(ISO-8601 UTC)", example = "2026-09-07T04:25:24.371948Z")
        Instant invitedAt
) {
    public static WorkspaceInvitationResponse from(WorkspaceInvitation invitation) {
        return new WorkspaceInvitationResponse(
                invitation.getId(),
                invitation.getEmail(),
                invitation.getRole().name(),
                invitation.getExpiresAt(),
                invitation.getCreatedAt()
        );
    }
}
