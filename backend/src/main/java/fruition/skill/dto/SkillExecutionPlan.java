package fruition.skill.dto;

import java.util.List;

public record SkillExecutionPlan(
        String message,
        String mode,
        SkillExecutionDefinition selectedSkill,
        List<SkillExecutionDefinition> skillCandidates
) {
    public static SkillExecutionPlan explicit(String message, SkillExecutionDefinition definition) {
        return new SkillExecutionPlan(message, "explicit", definition, List.of());
    }

    public static SkillExecutionPlan auto(String message, List<SkillExecutionDefinition> candidates) {
        return new SkillExecutionPlan(message, "auto", null, candidates);
    }
}
