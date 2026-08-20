package fruition.access.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record VerificationConfirmRequest(
        @NotBlank(message = "code는 필수입니다.")
        @Schema(description = "메일로 받은 인증번호. 오입력 5회에서 잠기며 잔여 횟수는 응답에 담기지 않는다.",
                example = "042173")
        String code
) {}
