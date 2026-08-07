package fruition.core.wikimaintenance.dto;

public record WikiLintRequest(
        Boolean materializePromotions,
        Boolean dryRun
) {}
