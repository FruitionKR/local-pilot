package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 로그인 응답. 두 가지 형태를 가진다.
 *
 * <ul>
 *   <li>MFA를 켜지 않은 사용자: {@code access_token}·{@code expires_in}이 채워지고 MFA 필드는 빠진다.</li>
 *   <li>MFA를 켠 사용자: {@code mfa_required}·{@code mfa_token}만 채워진다. 아직 로그인이 끝나지 않았고,
 *       {@code POST /api/auth/login/mfa}로 코드를 보내야 토큰을 받는다.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
        @Schema(description = "access_token 유효 시간(초). MFA 단계가 남았으면 빠진다.", example = "900")
        Long expiresIn,

        @JsonProperty("mfa_required")
        @Schema(description = "true면 다단계 인증 코드가 더 필요하다. 아니면 이 필드가 빠진다.",
                example = "true", nullable = true)
        Boolean mfaRequired,

        @JsonProperty("mfa_token")
        @Schema(description = "코드 검증에 쓸 1회용 토큰. mfa_required일 때만 나온다.", nullable = true)
        String mfaToken
) {
    public static LoginResponse tokens(String accessToken, String refreshToken, long expiresIn) {
        return new LoginResponse(accessToken, refreshToken, "Bearer", expiresIn, null, null);
    }

    public static LoginResponse mfaRequired(String mfaToken) {
        return new LoginResponse(null, null, null, null, true, mfaToken);
    }
}
