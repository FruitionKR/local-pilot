package fruition.query.service;

import fruition.query.domain.QueryRun;
import fruition.query.dto.QueryResponse;
import fruition.query.exception.PipelineQueryException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

@Service
public class QueryRunService {

    private static final Logger log = LoggerFactory.getLogger(QueryRunService.class);

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
        log.info("[질의 run 생성] requestId={} sessionId={} questionLength={}",
                run.requestId(), sessionId, question.length());
        queryRunExecutor.execute(() -> runPipeline(run.requestId(), sessionId, question));
        log.info("[질의 run 실행 예약] requestId={}", run.requestId());
        return run;
    }

    private void runPipeline(String requestId, String sessionId, String question) {
        queryRunStore.markRunning(requestId);
        String logCallbackUrl = callbackBaseUrl + "/api/query/runs/" + requestId + "/events/callback";
        log.info("[질의 run 시작] requestId={} sessionId={} callbackUrl={} questionLength={}",
                requestId, sessionId, logCallbackUrl, question.length());
        try {
            QueryResponse result = queryService.query(sessionId, question, requestId, logCallbackUrl);
            queryRunStore.markCompleted(requestId, result);
            log.info("[질의 run 완료] requestId={} answerLength={} relatedPageCount={} evidenceCount={}",
                    requestId,
                    answerLength(result),
                    result != null && result.relatedPages() != null ? result.relatedPages().size() : 0,
                    result != null && result.evidenceSnippets() != null ? result.evidenceSnippets().size() : 0);
            queryEventBroker.complete(requestId);
        } catch (PipelineQueryException e) {
            queryRunStore.markFailed(requestId, e.getMessage());
            log.warn("[질의 run 실패] requestId={} errorCode={} message={}",
                    requestId, e.getErrorCode(), e.getMessage());
            queryEventBroker.fail(requestId, e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredRuns() {
        queryRunStore.evictExpired().forEach(queryEventBroker::dispose);
    }

    private int answerLength(QueryResponse result) {
        if (result == null || result.assistantMessage() == null || result.assistantMessage().content() == null) {
            return 0;
        }
        return result.assistantMessage().content().length();
    }
}
