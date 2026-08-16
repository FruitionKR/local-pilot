package fruition.access.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "email은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Schema(description = "가입할 이메일. 이미 가입된 이메일이면 토큰 유효성과 무관하게 409 DUPLICATE_EMAIL이 먼저 나온다.",
                example = "user@example.com")
        String email,

        @NotBlank(message = "password는 필수입니다.")
        @Size(min = 8, max = 72, message = "password는 8자 이상 72자 이하여야 합니다.")
        @Schema(description = "비밀번호(8~72자)", example = "password1234")
        String password,

        @JsonProperty("display_name")
        @Size(max = 50, message = "display_name은 50자 이하여야 합니다.")
        @Schema(description = "표시명(50자 이하). 생략하면 이메일의 @ 앞부분이 표시명이 된다.",
                example = "표시 이름")
        String displayName,

        @JsonProperty("verification_token")
        @NotBlank(message = "verification_token은 필수입니다.")
        @Schema(description = "인증번호 검증으로 받은 1회용 토큰",
                example = "EXAMPLE-verification-token-not-real-0000000")
        String verificationToken
) {
        // 서비스 단위 테스트용 편의 생성자(인증 토큰 검증은 EmailVerificationService에서 별도 처리).
        public SignupRequest(String email, String password) {
                this(email, password, null, null);
        }
}
