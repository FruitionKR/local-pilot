package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.document.domain.DocumentStatus;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record DocumentStatusUpdateRequest(
        @NotNull(message = "status는 필수입니다.")
        DocumentStatus status,
        @JsonProperty("extracted_text_uri") String extractedTextUri,
        @JsonProperty("processed_at") Instant processedAt,
        @JsonProperty("error_message") String errorMessage
) {}
