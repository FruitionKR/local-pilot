package fruition.core.query.dto;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(
        @NotBlank(message = "질문은 비어 있을 수 없습니다.")
        String question,
        String provider,
        String model
) {
    public QueryRequest(String question) {
        this(question, null, null);
    }
}
