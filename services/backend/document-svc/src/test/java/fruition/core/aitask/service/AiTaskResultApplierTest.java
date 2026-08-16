package fruition.core.aitask.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.exception.RestorePreviewStaleException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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
    @Mock fruition.core.chat.service.ChatTurnRecorder chatTurnRecorder;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private AiTaskResultApplier applier;

    @BeforeEach
    void setUp() {
        applier = new AiTaskResultApplier(jdbcTemplate, objectMapper, queryService,
                operationIngestService, lintOperationStarter, wikiMaintenanceService,
                operationLogRepository, restoreApplier, restoreLifecycle, documentService, chatTurnRecorder);
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
    void duplicateAgentTerminalEventUpdatesProjectionOnlyOnce() throws Exception {
        JsonNode event = objectMapper.readTree("""
                {"event_id":"agent:run-1:succeeded","run_id":"run-1","kind":"agent",
                 "status":"succeeded","request":{"workspace_id":"ws-1","user_id":"user-1",
                 "document_id":"doc-1","base_version":1,"apply_operation_id":"op-1",
                 "editor_snapshot":{"markdown":"old"}},
                 "payload":{"action":"markdown_edit","edit":{"operation":"replace",
                 "actual_target":{"start_line":1,"end_line":1},"replacement_markdown":"new"}}}
                """);
        when(jdbcTemplate.update(any(String.class), eq("agent:run-1:succeeded"), eq("run-1"), any()))
                .thenReturn(1, 0);
        when(jdbcTemplate.update(
                org.mockito.ArgumentMatchers.contains("UPDATE agent_apply_projections"),
                eq(event.get("payload").toString()), eq("new"), eq("run-1"))).thenReturn(1);
        when(jdbcTemplate.query(contains("FOR UPDATE"), any(ResultSetExtractor.class), eq("run-1")))
                .thenReturn(new AiTaskResultApplier.AgentProjection("ws-1", "user-1", "doc-1", 1L, "op-1"));

        applier.applyAgent(event);
        applier.applyAgent(event);

        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("UPDATE agent_apply_projections"),
                eq(event.get("payload").toString()), eq("new"), eq("run-1"));
    }

    @Test
    void mismatchedAgentRequestFailsProjectionBeforeReady() throws Exception {
        JsonNode event = objectMapper.readTree("""
                {"event_id":"agent:run-mismatch:succeeded","run_id":"run-mismatch","kind":"agent",
                 "status":"succeeded","request":{"workspace_id":"ws-other","user_id":"user-1",
                 "document_id":"doc-1","base_version":1,"apply_operation_id":"op-1",
                 "editor_snapshot":{"markdown":"old"}},
                 "payload":{"action":"markdown_edit","edit":{"operation":"replace",
                 "actual_target":{"start_line":1,"end_line":1},"replacement_markdown":"new"}}}
                """);
        when(jdbcTemplate.update(any(String.class), eq("agent:run-mismatch:succeeded"),
                eq("run-mismatch"), any())).thenReturn(1);
        when(jdbcTemplate.query(contains("FOR UPDATE"), any(ResultSetExtractor.class), eq("run-mismatch")))
                .thenReturn(new AiTaskResultApplier.AgentProjection("ws-1", "user-1", "doc-1", 1L, "op-1"));
        when(jdbcTemplate.update(contains("SET status = 'failed'"),
                eq("agent_result_request_mismatch"), eq("run-mismatch"))).thenReturn(1);

        applier.applyAgent(event);

        verify(jdbcTemplate).update(contains("SET status = 'failed'"),
                eq("agent_result_request_mismatch"), eq("run-mismatch"));
        verify(jdbcTemplate, never()).update(contains("SET status = 'ready'"), any(), any(), any());
    }

    /** pipeline이 갱신한 누적 요약은 세션에 쌓여야 다음 턴이 맥락으로 이어 쓴다. */
    @Test
    void querySuccessStoresUpdatedConversationSummary() throws Exception {
        JsonNode event = objectMapper.readTree(eventWithSummary("이제까지 인덱싱을 다뤘다."));
        when(jdbcTemplate.update(any(String.class), any(), any(), any())).thenReturn(1);

        applier.applyQuery(event);

        verify(chatTurnRecorder).recordContextSummary("session-1", "이제까지 인덱싱을 다뤘다.");
    }

    /** 같은 결과가 다시 오면 요약도 다시 쓰지 않는다. 최초 처리에서만 세션을 건드린다. */
    @Test
    void queryDuplicateDoesNotRestoreConversationSummary() throws Exception {
        String canonical = eventWithSummary("이제까지 인덱싱을 다뤘다.");
        when(jdbcTemplate.update(any(String.class), any(), any(), any())).thenReturn(0);
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq("query-1")))
                .thenReturn(canonical);

        applier.applyQuery(objectMapper.readTree(canonical));

        verify(chatTurnRecorder, never()).recordContextSummary(anyString(), anyString());
    }

    /**
     * 편집·생성 갈래는 chat.answer도 message도 없이 온다. 빈 말풍선을 남기면 화면에 아무것도
     * 안 뜨고, 다음 턴의 대화 맥락에 실려 pipeline이 요청 전체를 거부한다.
     */
    @Test
    void markdownCreateResultGetsNonEmptyChatMessage() throws Exception {
        JsonNode event = objectMapper.readTree("""
                {"event_id":"agent:run-create:succeeded","run_id":"run-create","kind":"agent",
                 "status":"succeeded","request":{"workspace_id":"ws-1","user_id":"user-1",
                 "document_id":"doc-1","base_version":1,"apply_operation_id":"op-1",
                 "message_context":{"assistant_message_id":"chat_assistant_1"}},
                 "payload":{"action":"markdown_create","generated_markdown":{"markdown":"# 제목"}}}
                """);
        when(jdbcTemplate.update(any(String.class), eq("agent:run-create:succeeded"),
                eq("run-create"), any())).thenReturn(1);
        when(jdbcTemplate.query(contains("FOR UPDATE"), any(ResultSetExtractor.class), eq("run-create")))
                .thenReturn(new AiTaskResultApplier.AgentProjection("ws-1", "user-1", "doc-1", 1L, "op-1"));
        when(jdbcTemplate.update(contains("SET status = 'ready'"), any(), any(), eq("run-create")))
                .thenReturn(1);

        applier.applyAgent(event);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(chatTurnRecorder).completeAgentTurn(eq("chat_assistant_1"), eq("markdown_create"),
                content.capture());
        assertThat(content.getValue()).isNotBlank();
    }

    @Test
    void agentSuccessStoresUpdatedConversationSummary() throws Exception {
        JsonNode event = objectMapper.readTree("""
                {"event_id":"agent:run-summary:succeeded","run_id":"run-summary","kind":"agent",
                 "status":"succeeded","request":{"workspace_id":"ws-1","user_id":"user-1",
                 "session_id":"session-1","document_id":"doc-1","base_version":1,"apply_operation_id":"op-1"},
                 "payload":{"action":"workspace_workflow","run_id":"run-inner","run_status":"queued",
                 "updated_conversation_summary":"편집 요청까지 반영한 요약"}}
                """);
        when(jdbcTemplate.update(any(String.class), eq("agent:run-summary:succeeded"),
                eq("run-summary"), any())).thenReturn(1);
        when(jdbcTemplate.query(contains("FOR UPDATE"), any(ResultSetExtractor.class), eq("run-summary")))
                .thenReturn(new AiTaskResultApplier.AgentProjection("ws-1", "user-1", "doc-1", 1L, "op-1"));
        when(jdbcTemplate.update(contains("SET status = 'ready'"), any(), any(), eq("run-summary")))
                .thenReturn(1);

        applier.applyAgent(event);

        verify(chatTurnRecorder).recordContextSummary("session-1", "편집 요청까지 반영한 요약");
    }

    @Test
    void autonomousAgentResultBecomesReadyWithoutCanonicalMarkdown() throws Exception {
        JsonNode event = objectMapper.readTree("""
                {"event_id":"agent:run-autonomous:succeeded","run_id":"run-autonomous","kind":"agent",
                 "status":"succeeded","request":{"workspace_id":"ws-1","user_id":"user-1",
                 "document_id":"doc-1","base_version":1,"apply_operation_id":"op-1"},
                 "payload":{"action":"workspace_workflow","run_id":"run-inner","run_status":"queued"}}
                """);
        when(jdbcTemplate.update(any(String.class), eq("agent:run-autonomous:succeeded"),
                eq("run-autonomous"), any())).thenReturn(1);
        when(jdbcTemplate.query(contains("FOR UPDATE"), any(ResultSetExtractor.class), eq("run-autonomous")))
                .thenReturn(new AiTaskResultApplier.AgentProjection("ws-1", "user-1", "doc-1", 1L, "op-1"));
        when(jdbcTemplate.update(contains("SET status = 'ready'"),
                eq(event.get("payload").toString()), isNull(), eq("run-autonomous"))).thenReturn(1);

        applier.applyAgent(event);

        verify(jdbcTemplate).update(contains("SET status = 'ready'"),
                eq(event.get("payload").toString()), isNull(), eq("run-autonomous"));
        verify(jdbcTemplate, never()).update(contains("SET status = 'failed'"), any(), any());
    }

    @Test
    void unknownSuccessfulAgentActionFailsProjectionAndKeepsReceipt() throws Exception {
        JsonNode event = objectMapper.readTree("""
                {"event_id":"agent:run-2:succeeded","run_id":"run-2","kind":"agent",
                 "status":"succeeded","request":{},"payload":{"action":"unknown_action"}}
                """);
        when(jdbcTemplate.update(any(String.class), eq("agent:run-2:succeeded"), eq("run-2"), any()))
                .thenReturn(1);
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("UPDATE agent_apply_projections"),
                eq("agent_result_unsupported_action"), eq("run-2"))).thenReturn(1);

        var result = applier.applyAgent(event);

        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("UPDATE agent_apply_projections"),
                eq("agent_result_unsupported_action"), eq("run-2"));
        assertThat(result.error()).isEqualTo("agent_result_unsupported_action");
    }

    @Test
    void internallyRejectedAgentSuccessFailsTheChatMessage() throws Exception {
        JsonNode event = objectMapper.readTree("""
                {"event_id":"agent:run-invalid:succeeded","run_id":"run-invalid","kind":"agent",
                 "status":"succeeded","request":{"message_context":{"assistant_message_id":"chat_assistant_1"}},
                 "payload":{"action":"unknown_action"}}
                """);
        when(jdbcTemplate.update(any(String.class), eq("agent:run-invalid:succeeded"),
                eq("run-invalid"), any())).thenReturn(1);
        when(jdbcTemplate.update(contains("SET status = 'failed'"),
                eq("agent_result_unsupported_action"), eq("run-invalid"))).thenReturn(1);

        var result = applier.applyAgent(event);

        assertThat(result.error()).isEqualTo("agent_result_unsupported_action");
        verify(chatTurnRecorder).markFailed("chat_assistant_1", "agent_result_unsupported_action");
        verify(chatTurnRecorder, never()).completeAgentTurn(anyString(), any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"chat_answer", "clarify", "reject"})
    void nonMutatingAgentResultBecomesReadyWithoutCanonicalMarkdown(String action) throws Exception {
        JsonNode event = objectMapper.readTree("""
                {"event_id":"agent:run-non-mutating:succeeded","run_id":"run-non-mutating","kind":"agent",
                 "status":"succeeded","request":{"workspace_id":"ws-1","user_id":"user-1",
                 "document_id":"doc-1","base_version":1,"apply_operation_id":"op-1"},
                 "payload":{"action":"%s","message":"응답"}}
                """.formatted(action));
        when(jdbcTemplate.update(any(String.class), eq("agent:run-non-mutating:succeeded"),
                eq("run-non-mutating"), any())).thenReturn(1);
        when(jdbcTemplate.query(contains("FOR UPDATE"), any(ResultSetExtractor.class), eq("run-non-mutating")))
                .thenReturn(new AiTaskResultApplier.AgentProjection("ws-1", "user-1", "doc-1", 1L, "op-1"));
        when(jdbcTemplate.update(contains("SET status = 'ready'"),
                eq(event.get("payload").toString()), isNull(), eq("run-non-mutating"))).thenReturn(1);

        applier.applyAgent(event);

        verify(jdbcTemplate).update(contains("SET status = 'ready'"),
                eq(event.get("payload").toString()), isNull(), eq("run-non-mutating"));
        verify(jdbcTemplate, never()).update(contains("SET status = 'failed'"), any(), any());
    }

    @Test
    void failedAgentResultUsesDefaultErrorCodeWhenErrorIsBlank() throws Exception {
        JsonNode event = objectMapper.readTree("""
                {"event_id":"agent:run-3:failed","run_id":"run-3","kind":"agent",
                 "status":"failed","error":""}
                """);
        when(jdbcTemplate.update(any(String.class), eq("agent:run-3:failed"), eq("run-3"), any()))
                .thenReturn(1);
        when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("UPDATE agent_apply_projections"),
                eq("agent_turn_failed"), eq("run-3"))).thenReturn(1);

        applier.applyAgent(event);

        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("UPDATE agent_apply_projections"),
                eq("agent_turn_failed"), eq("run-3"));
    }

    @Test
    void extractsCanonicalMarkdownForSupportedCreateAndEditResults() throws Exception {
        JsonNode create = objectMapper.readTree("""
                {"request":{},"payload":{"action":"markdown_create",
                 "generated_markdown":{"title":"제목","markdown":"# 제목\\n본문"}}}
                """);
        JsonNode edit = objectMapper.readTree("""
                {"request":{"editor_snapshot":{"markdown":"# 제목\\n오래된 문장\\n끝"}},
                 "payload":{"action":"markdown_edit","edit":{"operation":"replace",
                 "actual_target":{"start_line":2,"end_line":2},"replacement_markdown":"새 문장"}}}
                """);

        assertThat(AiTaskResultApplier.expectedMarkdown(create)).isEqualTo("# 제목\n본문");
        assertThat(AiTaskResultApplier.expectedMarkdown(edit)).isEqualTo("# 제목\n새 문장\n끝");
    }

    @Test
    void nonMarkdownResultDoesNotProduceCanonicalMarkdown() throws Exception {
        JsonNode event = objectMapper.readTree("""
                {"request":{},"payload":{"action":"chat_answer"}}
                """);

        assertThat(AiTaskResultApplier.expectedMarkdown(event)).isNull();
    }

    @Test
    void restoreResultAndReceiptUseRequiredTransaction() throws Exception {
        Transactional apply = AiTaskResultApplier.class.getMethod("applyRestore", JsonNode.class)
                .getAnnotation(Transactional.class);
        Transactional fail = RestoreOperationLifecycle.class
                .getMethod("fail", String.class, String.class, java.time.Instant.class)
                .getAnnotation(Transactional.class);
        Transactional restoreApply = java.util.Arrays.stream(RestoreApplier.class.getMethods())
                .filter(method -> method.getName().equals("apply"))
                .findFirst().orElseThrow().getAnnotation(Transactional.class);

        assertThat(apply).isNotNull();
        assertThat(apply.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(fail.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(restoreApply.noRollbackFor()).containsExactly(RestorePreviewStaleException.class);
    }

    @Test
    void restoreStaleConflictCommitsConflictWithReceiptWithoutRetry() throws Exception {
        OperationLog operation = org.mockito.Mockito.mock(OperationLog.class);
        when(jdbcTemplate.update(any(String.class),
                eq("restore-event-1"), eq("restore-run-1"))).thenReturn(1);
        when(operationLogRepository.findById("restore-op")).thenReturn(Optional.of(operation));
        when(operation.getStatus()).thenReturn(OperationStatus.applying);
        when(operation.getRestoreManifest()).thenReturn(restoreManifest());
        doThrow(new RestorePreviewStaleException()).when(restoreApplier)
                .apply(eq(operation), any(), any(), any(), any());

        assertThatCode(() -> applier.applyRestore(restoreEvent())).doesNotThrowAnyException();

        verify(operation).complete(eq(OperationStatus.conflict),
                eq("복구 대상이 변경되었습니다. 미리보기를 다시 확인해 주세요."),
                eq(0), isNull(), any());
        verify(operationIngestService, never()).accept(any(), any());
    }

    @Test
    void restoreTransientFailureIsRetriedByKafka() throws Exception {
        OperationLog operation = org.mockito.Mockito.mock(OperationLog.class);
        when(jdbcTemplate.update(any(String.class),
                eq("restore-event-1"), eq("restore-run-1"))).thenReturn(1);
        when(operationLogRepository.findById("restore-op")).thenReturn(Optional.of(operation));
        when(operation.getStatus()).thenReturn(OperationStatus.applying);
        when(operation.getRestoreManifest()).thenReturn(restoreManifest());
        doThrow(new IllegalStateException("temporary")).when(restoreApplier)
                .apply(eq(operation), any(), any(), any(), any());

        assertThatThrownBy(() -> applier.applyRestore(restoreEvent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary");

        verify(operation, never()).complete(any(), any(), anyInt(), any(), any());
        verify(operationIngestService, never()).accept(any(), any());
    }

    private JsonNode restoreEvent() {
        var root = objectMapper.createObjectNode();
        root.put("event_id", "restore-event-1");
        root.put("run_id", "restore-run-1");
        root.put("kind", "restore_execute");
        root.put("status", "succeeded");
        root.putObject("request").put("operation_id", "restore-op");
        var payload = root.putObject("payload");
        payload.put("operation_id", "restore-op");
        payload.put("status", "succeeded");
        payload.putArray("changed_pages");
        return root;
    }

    private String restoreManifest() {
        return """
                {"plan":{"pages":[]},"excluded_operation_ids":[],"expected_contributions":{}}
                """;
    }

    private String eventWithSummary(String summary) throws Exception {
        var root = (com.fasterxml.jackson.databind.node.ObjectNode)
                objectMapper.readTree(event("event-1", "succeeded", "answer", null));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("payload"))
                .put("updated_conversation_summary", summary);
        return root.toString();
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
