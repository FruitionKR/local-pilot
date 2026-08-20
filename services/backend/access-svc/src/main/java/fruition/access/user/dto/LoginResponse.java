package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(
        @JsonProperty("access_token")
        @Schema(description = "API 호출에 쓰는 JWT. Authorization: Bearer <access_token>으로 보낸다. "
                + "payload의 sub(사용자 ID)·email·exp(만료 Unix seconds)를 갱신 타이밍 계산에 쓸 수 있다.")
        String accessToken,

        @JsonIgnore
        @Schema(hidden = true)
        String refreshToken,

        @JsonProperty("token_type")
        @Schema(description = "인증 스킴. 항상 Bearer다.", example = "Bearer")
        String tokenType,

        @JsonProperty("expires_in")
        @Schema(description = "access_token 유효 시간(초)", example = "900")
        long expiresIn
) {}
