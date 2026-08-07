package fruition.access.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record EmailVerificationRequest(
        @NotBlank(message = "email은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,

        @NotBlank(message = "purpose는 필수입니다.")
        @Pattern(regexp = "signup|password_reset", message = "purpose는 signup 또는 password_reset이어야 합니다.")
        String purpose
) {}
