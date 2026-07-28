package fruition.document.dto;

import fruition.document.domain.DocumentStatus;

public record DocumentIngestResponse(
        String id,
        DocumentStatus status
) {
}
