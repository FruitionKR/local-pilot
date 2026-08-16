package fruition.access.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "email은 필수입니다.")
        @Schema(description = "가입한 이메일",
                example = "user@example.com", defaultValue = "user@example.com")
        String email,

        @NotBlank(message = "password는 필수입니다.")
        @Schema(description = "비밀번호. 계정 없음·비밀번호 불일치·OAuth 전용 계정이 모두 같은 401로 응답된다.",
                example = "password1234", defaultValue = "password1234")
        String password
) {}
