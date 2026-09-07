package fruition.access.workspace.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.access.workspace.domain.WorkspaceMember;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record WorkspaceMemberResponse(
        @JsonProperty("user_id")
        @Schema(description = "멤버 사용자 ID", example = "user_1f9a74af")
        String userId,

        @Schema(description = "멤버 이메일", example = "member@example.com")
        String email,

        @JsonProperty("display_name")
        @Schema(description = "멤버 표시 이름", example = "홍길동")
        String displayName,

        @Schema(description = "계정을 만든 수단. 일반 가입은 local, OAuth는 provider 등록 ID."
                + " 같은 이메일이라도 provider가 다르면 다른 계정이다.", example = "local")
        String provider,

        @Schema(description = "워크스페이스 역할", example = "MEMBER",
                allowableValues = {"OWNER", "MEMBER"})
        String role,

        @JsonProperty("joined_at")
        @Schema(description = "합류 시각(ISO-8601 UTC)", example = "2026-08-13T04:25:24.371948Z")
        Instant joinedAt
) {
    public static WorkspaceMemberResponse from(WorkspaceMember member) {
        return new WorkspaceMemberResponse(
                member.getUserId(),
                member.getUser().getEmail(),
                member.getUser().getDisplayName(),
                member.getUser().getProvider(),
                member.getRole().name(),
                member.getJoinedAt()
        );
    }
}
