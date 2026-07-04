package fruition.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkspaceCreateRequest(
        @NotBlank(message = "name은 필수입니다.")
        @Size(max = 255, message = "name은 255자 이하여야 합니다.")
        String name
) {}
