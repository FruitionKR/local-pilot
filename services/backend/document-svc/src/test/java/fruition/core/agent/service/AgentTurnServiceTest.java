package fruition.core.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.exception.AgentRunNotFoundException;
import fruition.core.agent.exception.InvalidAgentTurnRequestException;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.aihistory.service.AgentApplyOperationStore;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.authz.WorkspaceNotFoundException;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.dto.DocumentDetailResponse;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.agent.repository.PipelineAgentRunStatusRequester;
import fruition.core.document.service.DocumentEditLockService;
import fruition.core.document.service.DocumentService;
import fruition.shared.ai.AiModelCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTurnServiceTest {

    @Mock DocumentService documentService;
    @Mock DocumentEditLockService editLockService;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock AgentRunCommandRepository runRepository;
    @Mock PipelineAgentRunStatusRequester statusRequester;
    @Mock AiCommandOutboxWriter outboxWriter;
    @Mock AgentApplyOperationStore applyOperationStore;
    private final AiModelCatalog aiModelCatalog = new AiModelCatalog("openai,gemini,claude");

    private AgentTurnService service;

    @BeforeEach
    void setUp() {
        service = new AgentTurnService(documentService, editLockService, workspaceAccessGuard, runRepository,
                statusRequester, outboxWriter, applyOperationStore, aiModelCatalog, "ai.agent.command");
    }

    @Test
    void turn_validRequestQueuesDurableRun() throws Exception {
        AgentTurnRequest request = request("whole_document", 1, 2,
                new AgentTurnRequest.ConversationContext(
                        "선택한 대화 요약",
                        Map.of("source", "selected"),
                        new AgentTurnRequest.ConversationContext.PendingSkillProposal(
                                "personal", "meeting-notes", "회의록을 작성합니다.", "# 정확한 원문 지침")));
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(applyOperationStore.newOperationId()).thenReturn("op_apply_1");

        var response = service.turn("ws_1", "user_1", request);

        assertThat(response.status()).isEqualTo("queued");
        assertThat(response.applyOperationId()).isEqualTo("op_apply_1");
        verify(runRepository).create(anyString(), org.mockito.ArgumentMatchers.eq("ws_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("op_apply_1"));
        ArgumentCaptor<AgentTurnService.AgentCommand> command =
                ArgumentCaptor.forClass(AgentTurnService.AgentCommand.class);
        verify(outboxWriter).enqueue(anyString(), org.mockito.ArgumentMatchers.eq("ai.agent.command"),
                org.mockito.ArgumentMatchers.eq("doc_1"), command.capture());
        assertThat(command.getValue().provider()).isEqualTo("openai");
        assertThat(command.getValue().model()).isEqualTo("gpt-5-nano");
        assertThat(command.getValue().conversationContext().pendingSkillProposal().scopeType()).isEqualTo("personal");
        assertThat(command.getValue().conversationContext().pendingSkillProposal().name()).isEqualTo("meeting-notes");
        assertThat(command.getValue().conversationContext().pendingSkillProposal().description())
                .isEqualTo("회의록을 작성합니다.");
        assertThat(command.getValue().conversationContext().pendingSkillProposal().instructionsMarkdown())
                .isEqualTo("# 정확한 원문 지침");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command.getValue()))
                .contains("\"pending_skill_proposal\":{\"scope_type\":\"personal\"")
                .contains("\"instructions_markdown\":\"# 정확한 원문 지침\"");
    }

    @Test
    void turn_ignoresClientSourceDetailsAndBuildsScopedCompletedAutonomousRun() throws Exception {
        String sourceRunId = "agent_0123456789abcdef0123456789abcdef";
        AgentTurnRequest request = new ObjectMapper().readValue("""
                {
                  "documentId":"doc_1","baseVersion":7,"message":"이 작업을 Skill로 만들어줘",
                  "provider":"openai","model":"gpt-5-nano",
                  "skill_draft_sources":[{"run_id":"%s","status":"completed",
                    "request_summary":"조작된 요청","plan_summary":"조작된 계획",
                    "successful_operations":[{"tool_name":"delete_document","reason":"조작된 이유"}]}],
                  "skill_draft_user_directives":["일반화해줘"],
                  "skill_draft_excluded_literals":["secret-doc"],"skill_scope_type":"team",
                  "editorSnapshot":{"markdown":"# 제목\\n본문",
                    "target":{"type":"whole_document","startLine":1,"endLine":2}}
                }
                """.formatted(sourceRunId), AgentTurnRequest.class);
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(statusRequester.findAutonomous("ws_1", "user_1", sourceRunId)).thenReturn(Optional.of(
                autonomousRun(sourceRunId, "completed", "정식 요청", "정식 계획", List.of(
                        new PipelineAgentRunStatusRequester.Operation(
                                "move_document", "정식 이유", "succeeded"),
                        new PipelineAgentRunStatusRequester.Operation(
                                "rename_folder", "실패한 이유", "failed")))));
        when(applyOperationStore.newOperationId()).thenReturn("op_apply_1");

        service.turn("ws_1", "user_1", request);

        ArgumentCaptor<AgentTurnService.AgentCommand> command = ArgumentCaptor.forClass(AgentTurnService.AgentCommand.class);
        verify(outboxWriter).enqueue(anyString(), org.mockito.ArgumentMatchers.eq("ai.agent.command"),
                org.mockito.ArgumentMatchers.eq("doc_1"), command.capture());
        var source = command.getValue().skillDraftSources().getFirst();
        assertThat(source.status()).isEqualTo("completed");
        assertThat(source.requestSummary()).isEqualTo("정식 요청");
        assertThat(source.planSummary()).isEqualTo("정식 계획");
        assertThat(source.successfulOperations().getFirst().toolName()).isEqualTo("move_document");
        assertThat(source.successfulOperations().getFirst().reason()).isEqualTo("정식 이유");
        assertThat(command.getValue().skillScopeType()).isEqualTo("team");
    }

    @Test
    void turn_rejectsMissingOrForeignSkillDraftSource() {
        String sourceRunId = "agent_0123456789abcdef0123456789abcdef";
        AgentTurnRequest request = skillDraftRequest(sourceRunId);
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(statusRequester.findAutonomous("ws_1", "user_1", sourceRunId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", request))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void turn_rejectsNonCompletedSkillDraftSource() {
        String sourceRunId = "agent_0123456789abcdef0123456789abcdef";
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(statusRequester.findAutonomous("ws_1", "user_1", sourceRunId)).thenReturn(Optional.of(
                autonomousRun(sourceRunId, "executing", "정식 요청", "정식 계획", List.of())));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", skillDraftRequest(sourceRunId)))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void turn_rejectsCompletedNonAutonomousSkillDraftSource() {
        String sourceRunId = "agent_0123456789abcdef0123456789abcdef";
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(statusRequester.findAutonomous("ws_1", "user_1", sourceRunId)).thenReturn(Optional.of(
                new PipelineAgentRunStatusRequester.AutonomousRun(
                        sourceRunId, "markdown_turn", "completed", "정식 요청",
                        new PipelineAgentRunStatusRequester.Plan("정식 계획", List.of()))));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", skillDraftRequest(sourceRunId)))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void turn_rejectsIncompleteCompletedSkillDraftSource() {
        String sourceRunId = "agent_0123456789abcdef0123456789abcdef";
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(statusRequester.findAutonomous("ws_1", "user_1", sourceRunId)).thenReturn(Optional.of(
                autonomousRun(sourceRunId, "completed", "정식 요청", "정식 계획", List.of(
                        new PipelineAgentRunStatusRequester.Operation(
                                "move_document", "실패한 operation", "failed")))));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", skillDraftRequest(sourceRunId)))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void turn_rejectsTooManySkillDraftSourcesBeforeLookup() {
        var sources = List.of(
                new AgentTurnRequest.SkillDraftSourceSelector("agent_0123456789abcdef0123456789abcdef"),
                new AgentTurnRequest.SkillDraftSourceSelector("agent_1123456789abcdef0123456789abcdef"),
                new AgentTurnRequest.SkillDraftSourceSelector("agent_2123456789abcdef0123456789abcdef"),
                new AgentTurnRequest.SkillDraftSourceSelector("agent_3123456789abcdef0123456789abcdef"));
        var request = new AgentTurnRequest("doc_1", 7L, "이 작업을 Skill로 만들어줘", "openai", "gpt-5-nano",
                null, sources, List.of(), List.of(), "team",
                new AgentTurnRequest.EditorSnapshot("# 제목\n본문",
                        new AgentTurnRequest.Target("whole_document", 1, 2)));
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", request))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(statusRequester, never()).findAutonomous(anyString(), anyString(), anyString());
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void request_deserializesSnakeCasePendingSkillProposalFields() throws Exception {
        String json = """
                {
                  "documentId": "doc_1",
                  "baseVersion": 7,
                  "message": "문서를 점검해줘",
                  "conversationContext": {
                    "pendingSkillProposal": {
                      "scope_type": "personal",
                      "name": "meeting-notes",
                      "description": "회의록을 작성합니다.",
                      "instructions_markdown": "# 정확한 원문 지침"
                    }
                  },
                  "editorSnapshot": {"markdown": "# 제목\\n본문"}
                }
                """;

        AgentTurnRequest request = new ObjectMapper().readValue(json, AgentTurnRequest.class);

        assertThat(request.conversationContext().pendingSkillProposal().scopeType()).isEqualTo("personal");
        assertThat(request.conversationContext().pendingSkillProposal().instructionsMarkdown())
                .isEqualTo("# 정확한 원문 지침");
    }

    @Test
    void turn_rejectsNonMarkdownBeforeQueue() {
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("paper.pdf", "application/pdf", 7));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", request("whole_document", 1, 2)))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void turn_rejectsStaleBaseVersionBeforeQueue() {
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 9));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", request("whole_document", 1, 2)))
                .isInstanceOf(DocumentVersionConflictException.class);
        verify(outboxWriter, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    void get_removedWorkspaceMemberHidesMalformedRunId() {
        doThrow(new WorkspaceNotFoundException("ws_1"))
                .when(workspaceAccessGuard).requireMember("ws_1", "user_1");

        assertThatThrownBy(() -> service.get("ws_1", "user_1", "agent_bad"))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verify(statusRequester, never()).find(anyString(), anyString(), anyString());
    }

    @Test
    void get_unknownRunUsesNotFoundException() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.empty());
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("ws_1", "user_1", runId))
                .isInstanceOf(AgentRunNotFoundException.class);

        verify(workspaceAccessGuard).requireMember("ws_1", "user_1");
    }

    @Test
    void get_malformedRunIdUsesInvalidRequestException() {
        assertThatThrownBy(() -> service.get("ws_1", "user_1", "agent_bad"))
                .isInstanceOf(InvalidAgentTurnRequestException.class);

        verify(workspaceAccessGuard).requireMember("ws_1", "user_1");
        verify(statusRequester, never()).find(anyString(), anyString(), anyString());
    }

    @Test
    void get_firstPollReturnsCoreQueuedProjectionBeforeAiRunExists() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.empty());
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        runId, "doc_1", 7L, "op_1", "queued", null, null)));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("queued");
    }

    @Test
    void get_prefersScopedAiRunStatusAfterAiRunExists() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("changed", true);
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new PipelineAgentRunStatusRequester.RunStatus(
                        runId, "doc_1", 7L, "op_1", "completed", result, null)));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("completed");
    }

    @Test
    void get_hidesAiCompletedWhileLocalProjectionStillQueued() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("changed", true);
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new PipelineAgentRunStatusRequester.RunStatus(
                        runId, "doc_1", 7L, "op_1", "completed", result, null)));
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        runId, "doc_1", 7L, "op_1", "queued", null, null)));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("queued");
        assertThat(response.result()).isNull();
    }

    @Test
    void get_404FallbackReturnsReadyProjectionResult() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        var result = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("changed", true);
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.empty());
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        runId, "doc_1", 7L, "op_1", "ready", result, null)));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("completed");
        assertThat(response.result()).isSameAs(result);
    }

    @Test
    void get_404FallbackReturnsFailedProjectionError() {
        String runId = "agent_0123456789abcdef0123456789abcdef";
        when(statusRequester.find("ws_1", "user_1", runId)).thenReturn(Optional.empty());
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        runId, "doc_1", 7L, "op_1", "failed", null, "agent_turn_failed")));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.error()).isEqualTo("agent_turn_failed");
    }

    private AgentTurnRequest request(String type, int startLine, int endLine) {
        return request(type, startLine, endLine, null);
    }

    private AgentTurnRequest request(String type, int startLine, int endLine,
                                     AgentTurnRequest.ConversationContext conversationContext) {
        return new AgentTurnRequest("doc_1", 7L, "문서를 점검해줘", "openai", "gpt-5-nano", conversationContext,
                new AgentTurnRequest.EditorSnapshot("# 제목\n본문",
                        new AgentTurnRequest.Target(type, startLine, endLine)));
    }

    private AgentTurnRequest skillDraftRequest(String sourceRunId) {
        return new AgentTurnRequest(
                "doc_1", 7L, "이 작업을 Skill로 만들어줘", "openai", "gpt-5-nano", null,
                List.of(new AgentTurnRequest.SkillDraftSourceSelector(sourceRunId)),
                List.of("일반화해줘"), List.of("secret-doc"), "team",
                new AgentTurnRequest.EditorSnapshot("# 제목\n본문",
                        new AgentTurnRequest.Target("whole_document", 1, 2)));
    }

    private PipelineAgentRunStatusRequester.AutonomousRun autonomousRun(
            String runId, String status, String requestSummary, String planSummary,
            List<PipelineAgentRunStatusRequester.Operation> operations) {
        return new PipelineAgentRunStatusRequester.AutonomousRun(
                runId, "workspace_workflow", status, requestSummary,
                new PipelineAgentRunStatusRequester.Plan(planSummary, operations));
    }

    private DocumentDetailResponse document(String filename, String mimeType, long currentVersion) {
        return new DocumentDetailResponse(
                "doc_1", filename, mimeType, 10, DocumentStatus.completed,
                "s3://source", null, Instant.now(), Instant.now(), null, List.of(),
                null, null, null, filename.substring(0, filename.lastIndexOf('.')),
                filename.substring(filename.lastIndexOf('.') + 1), null, false, currentVersion,
                currentVersion, null, Instant.now(), null, null);
    }
}
