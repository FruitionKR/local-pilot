package fruition.core.wikimaintenance.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WikiLintRequest(
        @JsonProperty("materialize_promotions") Boolean materializePromotions,
        @JsonProperty("dry_run") Boolean dryRun
) {}
