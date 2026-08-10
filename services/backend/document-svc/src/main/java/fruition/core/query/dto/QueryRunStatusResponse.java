package fruition.core.query.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.query.domain.QueryRun;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryRunStatusResponse(
        @JsonProperty("request_id") String requestId,
        String status,
        String provider,
        String model,
        @JsonProperty("web_search_enabled") boolean webSearchEnabled,
        QueryResponse result,
        String error
) {
    public static QueryRunStatusResponse from(QueryRun run) {
        return new QueryRunStatusResponse(
                run.requestId(), run.status().name().toLowerCase(), run.provider(), run.model(), run.webSearchEnabled(),
                run.result(), run.errorMessage());
    }
}
