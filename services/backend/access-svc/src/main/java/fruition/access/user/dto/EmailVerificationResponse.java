package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record EmailVerificationResponse(
        @JsonProperty("verification_id")
        @Schema(description = "발급된 인증 요청 ID. confirm 호출 시 경로에 넣는다.",
                example = "ev_3f1c8a6b52d7411e9c04ab5d2e7f6081")
        String verificationId,

        @JsonProperty("expires_in")
        @Schema(description = "인증번호 유효 시간(초)", example = "300")
        long expiresIn,

        @JsonProperty("retry_after")
        @Schema(description = "재발송이 가능해지기까지 남은 시간(초). 429의 Retry-After 헤더와는 다른 값이다.",
                example = "60")
        long retryAfter
) {}
