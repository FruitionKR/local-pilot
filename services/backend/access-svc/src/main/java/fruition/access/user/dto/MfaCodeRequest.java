package fruition.access.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaCodeRequest(
        @NotBlank(message = "code는 필수입니다.")
        @Size(max = 64, message = "code는 64자 이하여야 합니다.")
        @Schema(description = "인증 앱의 6자리 코드 또는 복구 코드", example = "482917")
        String code
) {}
