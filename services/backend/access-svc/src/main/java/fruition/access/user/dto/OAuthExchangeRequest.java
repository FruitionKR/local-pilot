package fruition.access.user.dto;

import jakarta.validation.constraints.NotBlank;

public record OAuthExchangeRequest(
        @NotBlank(message = "code는 필수입니다.")
        String code
) {}
