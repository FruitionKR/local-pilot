package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 초대 링크를 연 화면이 로그인 전에 보여줄 정보. 토큰을 가진 사람만 도달한다. */
public record InvitationPreviewResponse(
        @JsonProperty("workspace_id")
        @Schema(description = "초대된 워크스페이스 ID", example = "ws_9d47a0e9a6324341b47562553b75f92a")
        String workspaceId,

        @JsonProperty("workspace_name")
        @Schema(description = "초대된 워크스페이스 이름", example = "팀 워크스페이스")
        String workspaceName,

        @Schema(description = "초대받은 이메일 주소. 이 주소의 계정으로 로그인해야 수락할 수 있다.",
                example = "member@example.com")
        String email,

        @Schema(description = "수락 시 부여될 역할", example = "MEMBER",
                allowableValues = {"OWNER", "MEMBER"})
        String role,

        @JsonProperty("invited_by")
        @Schema(description = "초대한 사람의 표시 이름", example = "홍길동")
        String invitedBy,

        @JsonProperty("expires_at")
        @Schema(description = "초대 만료 시각(ISO-8601 UTC)", example = "2026-09-14T04:25:24.371948Z")
        Instant expiresAt
) {}
