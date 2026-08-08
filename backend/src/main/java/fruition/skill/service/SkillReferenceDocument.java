package fruition.skill.service;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SkillReferenceDocument(
        String id,
        String name,
        @JsonProperty("content_hash") String contentHash,
        String content
) {}
