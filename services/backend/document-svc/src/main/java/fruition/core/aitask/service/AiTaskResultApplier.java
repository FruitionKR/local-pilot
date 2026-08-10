package fruition.core.aitask.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.query.dto.QueryResponse;
import fruition.core.query.repository.PipelineQueryResponse;
import fruition.core.query.service.QueryService;
import fruition.core.aihistory.dto.OperationResultRequest;
import fruition.core.aihistory.exception.RestorePreviewStaleException;
import fruition.core.aihistory.service.LintOperationStarter;
import fruition.core.aihistory.service.OperationIngestService;
import fruition.core.wikimaintenance.repository.PipelineWikiLintResponse;
import fruition.core.wikimaintenance.service.WikiMaintenanceService;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.repository.OperationLogRepository;
import fruition.core.aihistory.service.RestoreApplier;
import fruition.core.aihistory.service.RestoreExecuteService;
import fruition.core.aihistory.service.RestoreOperationLifecycle;
import fruition.core.document.service.DocumentService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Kafka result event를 core DB에 멱등 반영한다. */
@Service
public class AiTaskResultApplier {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final QueryService queryService;
    private final OperationIngestService operationIngestService;
    private final LintOperationStarter lintOperationStarter;
    private final WikiMaintenanceService wikiMaintenanceService;
    private final OperationLogRepository operationLogRepository;
    private final RestoreApplier restoreApplier;
    private final RestoreOperationLifecycle restoreLifecycle;
    private final DocumentService documentService;

    public AiTaskResultApplier(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                               QueryService queryService,
                               OperationIngestService operationIngestService,
                               LintOperationStarter lintOperationStarter,
                               WikiMaintenanceService wikiMaintenanceService,
                               OperationLogRepository operationLogRepository,
                               RestoreApplier restoreApplier,
                               RestoreOperationLifecycle restoreLifecycle,
                               DocumentService documentService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.queryService = queryService;
        this.operationIngestService = operationIngestService;
        this.lintOperationStarter = lintOperationStarter;
        this.wikiMaintenanceService = wikiMaintenanceService;
        this.operationLogRepository = operationLogRepository;
        this.restoreApplier = restoreApplier;
        this.restoreLifecycle = restoreLifecycle;
        this.documentService = documentService;
    }

    @Transactional
    public void applyLint(JsonNode event) {
        String eventId = text(event, "event_id");
        String runId = text(event, "run_id");
        JsonNode request = required(event, "request");
        String operationId = request.path("operation_id").isNull()
                ? null : request.path("operation_id").asText(null);
        boolean first = jdbcTemplate.update("""
                INSERT INTO ai_task_result_receipts (event_id, run_id, task_kind)
                VALUES (?, ?, 'lint') ON CONFLICT (event_id) DO NOTHING
                """, eventId, runId) == 1;
        if (!first || operationId == null) return;

        if (!"succeeded".equals(text(event, "status"))) {
            lintOperationStarter.markFailed(operationId,
                    event.path("error").asText("Wiki lint 처리에 실패했습니다."));
            return;
        }
        String workspaceId = text(request, "workspace_id");
        String userId = text(request, "user_id");
        JsonNode payload = required(event, "payload");
        var response = PipelineWikiLintResponse.from(payload);
        operationIngestService.accept(operationId, new OperationResultRequest(
                response.operationId(), "lint", "succeeded", workspaceId, userId, null,
                "Wiki lint로 페이지 " + response.changedPages().size() + "개를 변경했습니다.",
                response.changedPages().stream()
                        .map(page -> new OperationResultRequest.ChangedPage(
                                page.pageId(), page.pageType(), page.markdownKey(),
                                page.contributionKey(), page.contentHash(), false))
                        .toList(), java.util.List.of()));
        wikiMaintenanceService.markLintSucceeded(workspaceId);
    }

    @Transactional
    public void applyRestore(JsonNode event) {
        String eventId = text(event, "event_id");
        String runId = text(event, "run_id");
        if (jdbcTemplate.update("""
                INSERT INTO ai_task_result_receipts (event_id, run_id, task_kind)
                VALUES (?, ?, 'restore') ON CONFLICT (event_id) DO NOTHING
                """, eventId, runId) == 0) return;
        JsonNode request = required(event, "request");
        String operationId = text(request, "operation_id");
        OperationLog operation = operationLogRepository.findById(operationId)
                .orElseThrow(() -> new IllegalArgumentException("Restore operation을 찾을 수 없습니다."));
        if (!"succeeded".equals(text(event, "status"))) {
            restoreLifecycle.fail(operationId,
                    event.path("error").asText("Wiki restore 처리에 실패했습니다."),
                    java.time.Instant.now());
            return;
        }

        OperationResultRequest result = objectMapper.convertValue(
                required(event, "payload"), OperationResultRequest.class);
        if (!operation.getStatus().isTerminal()) {
            RestoreExecuteService.RestoreManifest manifest = readRestoreManifest(operation);
            try {
                restoreApplier.apply(operation, manifest.plan(), manifest.excludedOperationIds(),
                        manifest.expectedContributions(), java.time.Instant.now());
            } catch (RestorePreviewStaleException e) {
                operation.complete(OperationStatus.conflict, e.getMessage(),
                        operation.getChangedResourceCount(), null, java.time.Instant.now());
                return;
            }
        }
        operationIngestService.accept(operationId, result);
    }

    @Transactional
    public void applyIngest(JsonNode event) {
        String eventId = text(event, "event_id");
        String runId = text(event, "run_id");
        if (jdbcTemplate.update("""
                INSERT INTO ai_task_result_receipts (event_id, run_id, task_kind)
                VALUES (?, ?, 'ingest') ON CONFLICT (event_id) DO NOTHING
                """, eventId, runId) == 0) return;
        JsonNode request = required(event, "request");
        String documentId = text(request, "document_id");
        String operationId = text(request, "operation_id");
        OperationResultRequest result = objectMapper.convertValue(
                required(event, "payload"), OperationResultRequest.class);
        operationIngestService.accept(operationId, result);
        documentService.applyPipelineResult(documentId, runId,
                "succeeded".equals(text(event, "status")) ? "succeeded" : "failed",
                event.path("error").asText(null));
    }

    @Transactional
    public void applyAgent(JsonNode event) {
        String eventId = text(event, "event_id");
        String runId = text(event, "run_id");
        if (jdbcTemplate.update("""
                INSERT INTO ai_task_result_receipts (event_id, run_id, task_kind, event_payload)
                VALUES (?, ?, 'agent', CAST(? AS jsonb))
                ON CONFLICT (run_id, task_kind) WHERE task_kind = 'agent' DO NOTHING
                """, eventId, runId, event.toString()) == 0) return;

        int updated;
        if ("succeeded".equals(text(event, "status"))) {
            updated = jdbcTemplate.update("""
                    UPDATE agent_apply_projections
                    SET status = 'ready', result = CAST(? AS jsonb), error_code = NULL, updated_at = now()
                    WHERE run_id = ? AND status = 'queued'
                    """, required(event, "payload").toString(), runId);
        } else {
            updated = jdbcTemplate.update("""
                    UPDATE agent_apply_projections
                    SET status = 'failed', error_code = ?, updated_at = now()
                    WHERE run_id = ? AND status = 'queued'
                    """, event.path("error").asText("agent_turn_failed"), runId);
        }
        if (updated != 1) {
            throw new IllegalStateException("Agent 적용 projection을 갱신하지 못했습니다: " + runId);
        }
    }

    private RestoreExecuteService.RestoreManifest readRestoreManifest(OperationLog operation) {
        try {
            return objectMapper.readValue(operation.getRestoreManifest(),
                    RestoreExecuteService.RestoreManifest.class);
        } catch (Exception e) {
            throw new IllegalStateException("Restore manifest를 읽지 못했습니다.", e);
        }
    }

    @Transactional
    public QueryProjection applyQuery(JsonNode event) {
        String runId = text(event, "run_id");
        boolean first = jdbcTemplate.update("""
                INSERT INTO ai_task_result_receipts (event_id, run_id, task_kind, event_payload)
                VALUES (?, ?, 'query', CAST(? AS jsonb))
                ON CONFLICT (run_id, task_kind) WHERE task_kind = 'query' DO NOTHING
                """, text(event, "event_id"), runId, event.toString()) == 1;
        JsonNode canonical = first ? event : canonicalQueryEvent(runId);
        JsonNode request = required(canonical, "request");
        String status = text(canonical, "status");
        String sessionId = text(request, "session_id");
        String question = text(request, "question");
        QueryService.QueryMessageContext context = objectMapper.convertValue(
                required(request, "message_context"), QueryService.QueryMessageContext.class);

        if ("succeeded".equals(status)) {
            PipelineQueryResponse result = objectMapper.convertValue(
                    required(canonical, "payload"), PipelineQueryResponse.class);
            QueryResponse response = first
                    ? queryService.completeAsync(sessionId, question, runId, context, result)
                    : responseFrom(question, context, result);
            return new QueryProjection(runId, response, null);
        }

        String error = canonical.path("error").asText("질의 처리 중 오류가 발생했습니다.");
        if (first) {
            queryService.failAsync(runId, context, error);
        }
        return new QueryProjection(runId, null, error);
    }

    private JsonNode canonicalQueryEvent(String runId) {
        String payload = jdbcTemplate.queryForObject("""
                SELECT event_payload::text
                FROM ai_task_result_receipts
                WHERE run_id = ? AND task_kind = 'query'
                """, String.class, runId);
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Query terminal event를 읽지 못했습니다: " + runId, e);
        }
    }

    private QueryResponse responseFrom(String question,
                                       QueryService.QueryMessageContext context,
                                       PipelineQueryResponse result) {
        return new QueryResponse(
                new QueryResponse.MessageSummary(context.userMessageId(), "user", question,
                        "completed", context.createdAt()),
                new QueryResponse.MessageSummary(context.assistantMessageId(), "assistant",
                        result.answer(), "completed", context.createdAt()),
                result.relatedPages(), result.evidenceSnippets(), result.graphContext(),
                result.traversalPaths());
    }

    private JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("AI task event 필드가 없습니다: " + field);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        String value = required(node, field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("AI task event 필드가 비었습니다: " + field);
        }
        return value;
    }

    public record QueryProjection(String runId, QueryResponse response, String error) {}
}
