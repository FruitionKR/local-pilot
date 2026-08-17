package fruition.access.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record EmailAvailabilityResponse(
        @Schema(description = "이메일로 신규 가입할 수 있으면 true", example = "true")
        boolean available
) {}
