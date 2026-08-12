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

import java.util.Objects;

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
            JsonNode request = event.get("request");
            JsonNode payload = event.get("payload");
            String errorCode = null;
            if (request == null || !request.isObject()) {
                errorCode = "agent_result_invalid_request";
            } else if (payload == null || !payload.isObject()) {
                errorCode = "agent_result_invalid_payload";
            } else if (!"markdown_create".equals(payload.path("action").asText())
                    && !"markdown_edit".equals(payload.path("action").asText())) {
                errorCode = "agent_result_unsupported_action";
            } else if (expectedMarkdown(event) == null) {
                errorCode = "agent_result_missing_canonical_markdown";
            }
            if (errorCode != null) {
                updated = markAgentFailed(runId, errorCode);
            } else {
                AgentProjection projection = loadAgentProjection(runId);
                errorCode = agentRequestIdentityError(request, projection);
                updated = errorCode == null
                        ? jdbcTemplate.update("""
                                UPDATE agent_apply_projections
                                SET status = 'ready', result = CAST(? AS jsonb), ready_markdown = ?,
                                    error_code = NULL, updated_at = now()
                                WHERE run_id = ? AND status = 'queued'
                                """, payload.toString(), expectedMarkdown(event), runId)
                        : markAgentFailed(runId, errorCode);
            }
        } else {
            String error = event.path("error").asText(null);
            updated = markAgentFailed(runId, error == null || error.isBlank() ? "agent_turn_failed" : error);
        }
        if (updated != 1) {
            throw new IllegalStateException("Agent 적용 projection을 갱신하지 못했습니다: " + runId);
        }
    }

    public static String expectedMarkdown(JsonNode event) {
        JsonNode request = event == null ? null : event.get("request");
        JsonNode payload = event == null ? null : event.get("payload");
        if (request == null || !request.isObject() || payload == null || !payload.isObject()) return null;
        if ("markdown_create".equals(payload.path("action").asText())) {
            return payload.path("generated_markdown").path("markdown").asText(null);
        }
        if (!"markdown_edit".equals(payload.path("action").asText())) return null;
        String source = request.path("editor_snapshot").path("markdown").asText(null);
        JsonNode edit = payload.path("edit");
        int start = edit.path("actual_target").path("start_line").asInt(0);
        int end = edit.path("actual_target").path("end_line").asInt(0);
        String replacement = edit.path("replacement_markdown").asText(null);
        String operation = edit.path("operation").asText(null);
        if (source == null || replacement == null || start < 1 || end < start || operation == null) return null;
        java.util.List<String> lines = new java.util.ArrayList<>(java.util.List.of(source.split("\\n", -1)));
        java.util.List<String> replacementLines = java.util.List.of(replacement.split("\\n", -1));
        if (end > lines.size()) return null;
        if ("replace".equals(operation)) {
            lines.subList(start - 1, end).clear();
            lines.addAll(start - 1, replacementLines);
        } else if ("insert_after".equals(operation)) {
            lines.addAll(end, replacementLines);
        } else {
            return null;
        }
        return String.join("\n", lines);
    }

    private int markAgentFailed(String runId, String errorCode) {
        return jdbcTemplate.update("""
                UPDATE agent_apply_projections
                SET status = 'failed', error_code = ?, updated_at = now()
                WHERE run_id = ? AND status = 'queued'
                """, errorCode, runId);
    }

    private AgentProjection loadAgentProjection(String runId) {
        return jdbcTemplate.query("""
                SELECT workspace_id, user_id, document_id, base_version, apply_operation_id
                FROM agent_apply_projections
                WHERE run_id = ?
                FOR UPDATE
                """, resultSet -> resultSet.next() ? new AgentProjection(
                resultSet.getString("workspace_id"),
                resultSet.getString("user_id"),
                resultSet.getString("document_id"),
                resultSet.getLong("base_version"),
                resultSet.getString("apply_operation_id")) : null, runId);
    }

    private String agentRequestIdentityError(JsonNode request, AgentProjection projection) {
        if (projection == null
                || !Objects.equals(textOrNull(request, "workspace_id"), projection.workspaceId())
                || !Objects.equals(textOrNull(request, "user_id"), projection.userId())
                || !Objects.equals(textOrNull(request, "document_id"), projection.documentId())
                || !request.path("base_version").isNumber()
                || request.path("base_version").asLong() != projection.baseVersion()
                || !Objects.equals(textOrNull(request, "apply_operation_id"), projection.applyOperationId())) {
            return "agent_result_request_mismatch";
        }
        return null;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static record AgentProjection(
            String workspaceId,
            String userId,
            String documentId,
            long baseVersion,
            String applyOperationId
    ) {}

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
