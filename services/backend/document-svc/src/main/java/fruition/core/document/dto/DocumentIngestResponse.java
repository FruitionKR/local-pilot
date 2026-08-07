package fruition.core.document.dto;

import fruition.core.document.domain.DocumentStatus;

public record DocumentIngestResponse(
        String id,
        DocumentStatus status
) {
}
