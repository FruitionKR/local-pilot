package fruition.core.agent.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
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

    private AgentTurnService service;

    @BeforeEach
    void setUp() {
        service = new AgentTurnService(documentService, editLockService, workspaceAccessGuard, runRepository,
                statusRequester, outboxWriter, applyOperationStore, "ai.agent.command");
    }

    @Test
    void turn_validRequestQueuesDurableRun() {
        AgentTurnRequest request = request("whole_document", 1, 2);
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 7));
        when(applyOperationStore.newOperationId()).thenReturn("op_apply_1");

        var response = service.turn("ws_1", "user_1", request);

        assertThat(response.status()).isEqualTo("queued");
        assertThat(response.applyOperationId()).isEqualTo("op_apply_1");
        verify(runRepository).create(anyString(), org.mockito.ArgumentMatchers.eq("ws_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("doc_1"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("op_apply_1"));
        verify(outboxWriter).enqueue(anyString(), org.mockito.ArgumentMatchers.eq("ai.agent.command"),
                org.mockito.ArgumentMatchers.eq("doc_1"), any());
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
        when(runRepository.find("ws_1", "user_1", runId)).thenReturn(Optional.of(
                new AgentRunCommandRepository.RunView(
                        runId, "doc_1", 7L, "op_1", "ready", result, null)));

        var response = service.get("ws_1", "user_1", runId);

        assertThat(response.status()).isEqualTo("completed");
    }

    @Test
    void get_doesNotExposeCompletedStatusWhileLocalProjectionStillQueued() {
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

    private AgentTurnRequest request(String type, int startLine, int endLine) {
        return new AgentTurnRequest("doc_1", 7L, "문서를 점검해줘", null,
                new AgentTurnRequest.EditorSnapshot("# 제목\n본문",
                        new AgentTurnRequest.Target(type, startLine, endLine)));
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
