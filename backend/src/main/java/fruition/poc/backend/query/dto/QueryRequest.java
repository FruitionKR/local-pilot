package fruition.poc.backend.query.dto;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(
        @NotBlank(message = "질문은 비어 있을 수 없습니다.")
        String question
) {}
