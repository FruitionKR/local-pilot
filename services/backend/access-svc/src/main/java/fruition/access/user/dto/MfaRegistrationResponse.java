package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record MfaRegistrationResponse(
        @Schema(description = "인증 앱에 수동 입력할 base32 secret", example = "JBSWY3DPEHPK3PXP")
        String secret,

        @JsonProperty("otpauth_uri")
        @Schema(description = "QR 코드로 만들 otpauth URI",
                example = "otpauth://totp/Fruition:user@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Fruition")
        String otpauthUri,

        @JsonProperty("recovery_codes")
        @Schema(description = "1회용 복구 코드. 이 응답에서만 볼 수 있고 서버에는 해시만 남는다.")
        List<String> recoveryCodes
) {}
