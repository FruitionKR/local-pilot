package fruition.core.query.service;

import fruition.core.query.domain.QueryRun;
import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.core.document.repository.AiCommandOutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueryRunService {

    private static final Logger log = LoggerFactory.getLogger(QueryRunService.class);
    private final QueryRunStore queryRunStore;
    private final QueryService queryService;
    private final AiCommandOutboxWriter outboxWriter;
    private final String commandTopic;

    public QueryRunService(
            QueryRunStore queryRunStore,
            QueryService queryService,
            AiCommandOutboxWriter outboxWriter,
            @Value("${app.query.command-topic}") String commandTopic) {
        this.queryRunStore = queryRunStore;
        this.queryService = queryService;
        this.outboxWriter = outboxWriter;
        this.commandTopic = commandTopic;
    }

    @Transactional
    public QueryRun start(String workspaceId, String userId, String sessionId, String question) {
        QueryRun run = queryRunStore.create(workspaceId, sessionId, question);
        log.info("[질의 run 생성] requestId={} sessionId={} questionLength={}",
                run.requestId(), sessionId, question.length());
        QueryService.QueryMessageContext messageContext =
                queryService.prepareMessages(sessionId, question, run.requestId());
        outboxWriter.enqueue(run.requestId(), commandTopic, sessionId,
                new QueryCommand(run.requestId(), "query", workspaceId, userId, sessionId,
                        question, messageContext));
        log.info("[질의 command 등록 완료] requestId={}", run.requestId());
        return run;
    }

    record QueryCommand(
            @JsonProperty("run_id") String runId,
            String kind,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("session_id") String sessionId,
            String question,
            @JsonProperty("message_context") QueryService.QueryMessageContext messageContext
    ) {}
}
