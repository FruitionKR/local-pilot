package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record VerificationConfirmResponse(
        @JsonProperty("verification_token")
        @Schema(description = "1회용 토큰. signup 또는 password-reset 요청에 그대로 실어 보낸다.",
                example = "EXAMPLE-verification-token-not-real-0000000")
        String verificationToken,

        @JsonProperty("expires_in")
        @Schema(description = "verification_token 유효 시간(초)", example = "600")
        long expiresIn
) {}
