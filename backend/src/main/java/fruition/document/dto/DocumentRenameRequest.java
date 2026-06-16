package fruition.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentRenameRequest(
        String filename,
        @JsonProperty("sync_source_title") Boolean syncSourceTitle
) {}
