package fruition.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "email은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,

        @NotBlank(message = "password는 필수입니다.")
        @Size(min = 8, max = 72, message = "password는 8자 이상 72자 이하여야 합니다.")
        String password,

        @JsonProperty("display_name")
        @Size(max = 50, message = "display_name은 50자 이하여야 합니다.")
        String displayName,

        @JsonProperty("verification_token")
        @NotBlank(message = "verification_token은 필수입니다.")
        String verificationToken
) {
        // 서비스 단위 테스트용 편의 생성자(인증 토큰 검증은 EmailVerificationService에서 별도 처리).
        public SignupRequest(String email, String password) {
                this(email, password, null, null);
        }
}
