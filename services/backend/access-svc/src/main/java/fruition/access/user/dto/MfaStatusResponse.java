package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record MfaStatusResponse(
        @Schema(description = "다단계 인증이 켜져 있는지 여부", example = "true")
        boolean enabled,

        @JsonProperty("activated_at")
        @Schema(description = "켠 시각(ISO-8601 UTC). 꺼져 있으면 null이다.", nullable = true)
        Instant activatedAt,

        @JsonProperty("remaining_recovery_codes")
        @Schema(description = "아직 쓰지 않은 복구 코드 수", example = "10")
        int remainingRecoveryCodes
) {}
