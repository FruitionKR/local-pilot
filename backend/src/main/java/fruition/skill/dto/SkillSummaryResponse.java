package fruition.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SkillSummaryResponse(
        String id,
        String command,
        String name,
        String description,
        String scope,
        @JsonProperty("auto_routing_enabled") boolean autoRoutingEnabled,
        @JsonProperty("can_manage") boolean canManage,
        @JsonProperty("can_delete") boolean canDelete
) {}
