package fruition.core.wikimaintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** 위키 유지보수 상태: 마지막 lint 이후 위키가 변경됐으면 needs_lint = true. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WikiMaintenanceStatusResponse(
        @JsonProperty("needs_lint") boolean needsLint,
        @JsonProperty("last_lint_at") Instant lastLintAt,
        @JsonProperty("last_wiki_change_at") Instant lastWikiChangeAt
) {}
