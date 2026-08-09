package fruition.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SkillDetailResponse(
        String id,
        String command,
        String name,
        String description,
        String instructions,
        String scope,
        @JsonProperty("auto_routing_enabled") boolean autoRoutingEnabled,
        @JsonProperty("version_id") String versionId,
        int version,
        List<String> capabilities,
        @JsonProperty("allowed_tools") List<String> allowedTools,
        @JsonProperty("reference_documents") List<ReferenceDocument> referenceDocuments
) {
    public record ReferenceDocument(
            String id, String name, @JsonProperty("content_hash") String contentHash, String status) {}
}
