package fruition.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @NotBlank(message = "email은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,

        @JsonProperty("new_password")
        @NotBlank(message = "new_password는 필수입니다.")
        @Size(min = 8, max = 72, message = "new_password는 8자 이상 72자 이하여야 합니다.")
        String newPassword,

        @JsonProperty("verification_token")
        @NotBlank(message = "verification_token은 필수입니다.")
        String verificationToken
) {}
