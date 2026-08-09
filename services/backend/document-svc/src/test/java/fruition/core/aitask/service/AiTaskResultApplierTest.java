package fruition.core.aitask.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.aihistory.service.LintOperationStarter;
import fruition.core.aihistory.service.OperationIngestService;
import fruition.core.aihistory.service.RestoreApplier;
import fruition.core.aihistory.service.RestoreOperationLifecycle;
import fruition.core.document.service.DocumentService;
import fruition.core.query.service.QueryService;
import fruition.core.wikimaintenance.service.WikiMaintenanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTaskResultApplierTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock QueryService queryService;
    @Mock OperationIngestService operationIngestService;
    @Mock LintOperationStarter lintOperationStarter;
    @Mock WikiMaintenanceService wikiMaintenanceService;
    @Mock OperationLogRepository operationLogRepository;
    @Mock RestoreApplier restoreApplier;
    @Mock RestoreOperationLifecycle restoreLifecycle;
    @Mock DocumentService documentService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private AiTaskResultApplier applier;

    @BeforeEach
    void setUp() {
        applier = new AiTaskResultApplier(jdbcTemplate, objectMapper, queryService,
                operationIngestService, lintOperationStarter, wikiMaintenanceService,
                operationLogRepository, restoreApplier, restoreLifecycle, documentService);
    }

    @Test
    void queryDuplicateUsesFirstCanonicalTerminalPayload() throws Exception {
        JsonNode incomingFailure = objectMapper.readTree(event("event-2", "failed", null, "late failure"));
        String canonicalSuccess = event("event-1", "succeeded", "first answer", null);
        when(jdbcTemplate.update(any(String.class), any(), any(), any())).thenReturn(0);
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq("query-1")))
                .thenReturn(canonicalSuccess);

        var projection = applier.applyQuery(incomingFailure);

        assertThat(projection.error()).isNull();
        assertThat(projection.response().assistantMessage().content()).isEqualTo("first answer");
        verify(queryService, never()).failAsync(any(), any(), any());
        verify(queryService, never()).completeAsync(any(), any(), any(), any(), any());
    }

    @Test
    void queryLateSuccessCannotReplaceCanonicalFailure() throws Exception {
        JsonNode incomingSuccess = objectMapper.readTree(event("event-2", "succeeded", "late answer", null));
        String canonicalFailure = event("event-1", "failed", null, "first failure");
        when(jdbcTemplate.update(any(String.class), any(), any(), any())).thenReturn(0);
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq("query-1")))
                .thenReturn(canonicalFailure);

        var projection = applier.applyQuery(incomingSuccess);

        assertThat(projection.response()).isNull();
        assertThat(projection.error()).isEqualTo("first failure");
        verify(queryService, never()).failAsync(any(), any(), any());
        verify(queryService, never()).completeAsync(any(), any(), any(), any(), any());
    }

    @Test
    void restoreResultAndReceiptUseRequiredTransaction() throws Exception {
        Transactional apply = AiTaskResultApplier.class.getMethod("applyRestore", JsonNode.class)
                .getAnnotation(Transactional.class);
        Transactional fail = RestoreOperationLifecycle.class
                .getMethod("fail", String.class, String.class, java.time.Instant.class)
                .getAnnotation(Transactional.class);

        assertThat(apply).isNotNull();
        assertThat(apply.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(fail.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    private String event(String eventId, String status, String answer, String error) {
        var root = objectMapper.createObjectNode();
        root.put("event_id", eventId);
        root.put("run_id", "query-1");
        root.put("kind", "query");
        root.put("status", status);
        if (error != null) root.put("error", error);
        var request = root.putObject("request");
        request.put("session_id", "session-1");
        request.put("question", "question");
        var context = request.putObject("message_context");
        context.put("pairId", "pair-1");
        context.put("userMessageId", "user-message-1");
        context.put("assistantMessageId", "assistant-message-1");
        context.put("createdAt", "2026-08-10T00:00:00Z");
        if (answer != null) {
            var payload = root.putObject("payload");
            payload.put("answer", answer);
            payload.putArray("related_pages");
            payload.putArray("evidence_snippets");
            var graph = payload.putObject("graph_context");
            graph.putArray("nodes");
            graph.putArray("edges");
            payload.putArray("traversal_paths");
        }
        return root.toString();
    }
}
