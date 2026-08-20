package fruition.access.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkspaceCreateRequest(
        @NotBlank(message = "name은 필수입니다.")
        @Size(max = 255, message = "name은 255자 이하여야 합니다.")
        @Schema(description = "워크스페이스 이름(255자 이하)", example = "내 워크스페이스")
        String name
) {}
