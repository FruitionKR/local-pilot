package fruition.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @JsonProperty("refresh_token")
        @NotBlank(message = "refresh_token은 필수입니다.")
        String refreshToken
) {}
