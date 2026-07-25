package fruition.wikimaintenance.dto;

public record WikiLintRequest(
        Boolean materializePromotions,
        Boolean dryRun
) {}
