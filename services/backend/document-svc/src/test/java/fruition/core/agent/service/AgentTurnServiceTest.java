package fruition.core.agent.service;

import fruition.core.agent.dto.AgentTurnRequest;
import fruition.core.agent.exception.InvalidAgentTurnRequestException;
import fruition.core.agent.repository.AgentRunCommandRepository;
import fruition.core.aihistory.service.AgentApplyOperationStore;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.dto.DocumentDetailResponse;
import fruition.core.document.exception.DocumentVersionConflictException;
import fruition.core.document.repository.AiCommandOutboxWriter;
import fruition.core.document.service.DocumentEditLockService;
import fruition.core.document.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTurnServiceTest {

    @Mock DocumentService documentService;
    @Mock DocumentEditLockService editLockService;
    @Mock AgentRunCommandRepository runRepository;
    @Mock AiCommandOutboxWriter outboxWriter;
    @Mock AgentApplyOperationStore applyOperationStore;

    private AgentTurnService service;

    @BeforeEach
    void setUp() {
        service = new AgentTurnService(documentService, editLockService, runRepository,
                outboxWriter, applyOperationStore, "ai.agent.command");
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
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq("op_apply_1"),
                org.mockito.ArgumentMatchers.eq("문서를 점검해줘"));
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
