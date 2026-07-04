package fruition.query.service;

import fruition.query.domain.QueryRun;
import fruition.query.dto.QueryResponse;
import fruition.query.exception.PipelineQueryException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

@Service
public class QueryRunService {

    private final QueryRunStore queryRunStore;
    private final QueryEventBroker queryEventBroker;
    private final QueryService queryService;
    private final Executor queryRunExecutor;
    private final String callbackBaseUrl;

    public QueryRunService(
            QueryRunStore queryRunStore,
            QueryEventBroker queryEventBroker,
            QueryService queryService,
            @Qualifier("queryRunExecutor") Executor queryRunExecutor,
            @Value("${app.callback.base-url}") String callbackBaseUrl) {
        this.queryRunStore = queryRunStore;
        this.queryEventBroker = queryEventBroker;
        this.queryService = queryService;
        this.queryRunExecutor = queryRunExecutor;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    public QueryRun start(String sessionId, String question) {
        QueryRun run = queryRunStore.create(sessionId, question);
        queryRunExecutor.execute(() -> runPipeline(run.requestId(), sessionId, question));
        return run;
    }

    private void runPipeline(String requestId, String sessionId, String question) {
        queryRunStore.markRunning(requestId);
        String logCallbackUrl = callbackBaseUrl + "/api/query/runs/" + requestId + "/events/callback";
        try {
            QueryResponse result = queryService.query(sessionId, question, requestId, logCallbackUrl);
            queryRunStore.markCompleted(requestId, result);
            queryEventBroker.complete(requestId);
        } catch (PipelineQueryException e) {
            queryRunStore.markFailed(requestId, e.getMessage());
            queryEventBroker.fail(requestId, e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredRuns() {
        queryRunStore.evictExpired().forEach(queryEventBroker::dispose);
    }
}
