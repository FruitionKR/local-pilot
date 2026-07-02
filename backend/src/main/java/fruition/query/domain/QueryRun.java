package fruition.query.domain;

import fruition.query.dto.QueryResponse;

import java.time.Instant;

public record QueryRun(
        String requestId,
        String sessionId,
        QueryRunStatus status,
        String question,
        QueryResponse result,
        String errorMessage,
        Instant createdAt,
        Instant completedAt) {

    public static QueryRun pending(String requestId, String sessionId, String question, Instant createdAt) {
        return new QueryRun(requestId, sessionId, QueryRunStatus.PENDING, question, null, null, createdAt, null);
    }

    public QueryRun running() {
        return new QueryRun(requestId, sessionId, QueryRunStatus.RUNNING, question, result, errorMessage, createdAt, completedAt);
    }

    public QueryRun completed(QueryResponse result, Instant completedAt) {
        return new QueryRun(requestId, sessionId, QueryRunStatus.COMPLETED, question, result, null, createdAt, completedAt);
    }

    public QueryRun failed(String errorMessage, Instant completedAt) {
        return new QueryRun(requestId, sessionId, QueryRunStatus.FAILED, question, null, errorMessage, createdAt, completedAt);
    }

    public boolean isFinished() {
        return status == QueryRunStatus.COMPLETED || status == QueryRunStatus.FAILED;
    }
}
