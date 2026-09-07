package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailChangeRequest(
        @JsonProperty("new_email")
        @NotBlank(message = "new_email은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Size(max = 255, message = "new_email은 255자 이하여야 합니다.")
        @Schema(description = "바꿀 새 이메일. 이 주소로 받은 인증번호의 토큰이어야 한다.",
                example = "new@example.com")
        String newEmail,

        @JsonProperty("verification_token")
        @NotBlank(message = "verification_token은 필수입니다.")
        @Schema(description = "purpose=email_change로 받은 1회용 토큰",
                example = "EXAMPLE-verification-token-not-real-0000000")
        String verificationToken
) {}
