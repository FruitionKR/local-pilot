package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(
        @JsonProperty("access_token")
        @Schema(description = "API 호출에 쓰는 JWT. Authorization: Bearer <access_token>으로 보낸다. "
                + "payload의 sub(사용자 ID)·email·exp(만료 Unix seconds)를 갱신 타이밍 계산에 쓸 수 있다.")
        String accessToken,

        @JsonProperty("refresh_token")
        @Schema(description = "재발급용 opaque 토큰. refresh 호출마다 rotation되어 이전 토큰은 폐기된다.",
                example = "EXAMPLE-refresh-token-not-a-real-value-0000")
        String refreshToken,

        @JsonProperty("token_type")
        @Schema(description = "인증 스킴. 항상 Bearer다.", example = "Bearer")
        String tokenType,

        @JsonProperty("expires_in")
        @Schema(description = "access_token 유효 시간(초)", example = "900")
        long expiresIn
) {}
