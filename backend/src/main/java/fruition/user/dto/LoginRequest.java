package fruition.user.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "email은 필수입니다.")
        String email,

        @NotBlank(message = "password는 필수입니다.")
        String password
) {}
