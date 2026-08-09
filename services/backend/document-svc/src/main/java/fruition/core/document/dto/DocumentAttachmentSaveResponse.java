package fruition.core.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record DocumentAttachmentSaveResponse(
        @JsonProperty("attachment_id") UUID attachmentId,
        @JsonProperty("asset_id") UUID assetId,
        @JsonProperty("content_path") String contentPath
) {
}
