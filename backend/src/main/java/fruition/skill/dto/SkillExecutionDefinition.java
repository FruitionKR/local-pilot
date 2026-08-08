package fruition.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SkillExecutionDefinition(
        @JsonProperty("skill_id") String skillId,
        @JsonProperty("version_id") String versionId,
        String command,
        String name,
        String description,
        @JsonProperty("instructions_markdown") String instructionsMarkdown,
        List<String> capabilities,
        @JsonProperty("allowed_tools") List<String> allowedTools,
        @JsonProperty("reference_documents") List<ReferenceDocument> referenceDocuments
) {
    public record ReferenceDocument(String id, String name, @JsonProperty("content_hash") String contentHash) {}
}
