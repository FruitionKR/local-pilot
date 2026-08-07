package fruition.access.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "email은 필수입니다.")
        @Schema(example = "user@example.com", defaultValue = "user@example.com")
        String email,

        @NotBlank(message = "password는 필수입니다.")
        @Schema(example = "stringst", defaultValue = "stringst")
        String password
) {}
