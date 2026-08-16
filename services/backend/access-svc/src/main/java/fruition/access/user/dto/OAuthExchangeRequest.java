package fruition.access.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record OAuthExchangeRequest(
        @NotBlank(message = "code는 필수입니다.")
        @Schema(description = "OAuth 로그인 성공 후 redirect로 받은 1회용 교환 코드(TTL 60초)")
        String code
) {}
