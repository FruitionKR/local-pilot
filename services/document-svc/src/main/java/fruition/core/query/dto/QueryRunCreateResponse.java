package fruition.core.query.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.query.domain.QueryRun;

public record QueryRunCreateResponse(
        @JsonProperty("request_id") String requestId,
        String status
) {
    public static QueryRunCreateResponse from(QueryRun run) {
        return new QueryRunCreateResponse(run.requestId(), run.status().name().toLowerCase());
    }
}
