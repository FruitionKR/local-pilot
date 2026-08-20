package fruition.access.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record EmailVerificationRequest(
        @NotBlank(message = "email은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Schema(description = "인증번호를 받을 이메일", example = "user@example.com")
        String email,

        @NotBlank(message = "purpose는 필수입니다.")
        @Pattern(regexp = "signup|password_reset", message = "purpose는 signup 또는 password_reset이어야 합니다.")
        @Schema(description = "인증 목적. signup은 이미 가입된 이메일이면 409로 거절된다.",
                allowableValues = {"signup", "password_reset"}, example = "signup")
        String purpose
) {}
