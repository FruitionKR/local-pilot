package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @NotBlank(message = "email은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Schema(description = "비밀번호를 재설정할 계정 이메일", example = "user@example.com")
        String email,

        @JsonProperty("new_password")
        @NotBlank(message = "new_password는 필수입니다.")
        @Size(min = 8, max = 72, message = "new_password는 8자 이상 72자 이하여야 합니다.")
        @Schema(description = "새 비밀번호(8~72자). 성공하면 해당 사용자의 refresh token이 전부 폐기된다.",
                example = "password1234")
        String newPassword,

        @JsonProperty("verification_token")
        @NotBlank(message = "verification_token은 필수입니다.")
        @Schema(description = "purpose=password_reset로 받은 1회용 토큰",
                example = "EXAMPLE-verification-token-not-real-0000000")
        String verificationToken
) {}
