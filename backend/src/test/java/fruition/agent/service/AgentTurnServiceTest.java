package fruition.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.agent.dto.AgentTurnRequest;
import fruition.agent.dto.AgentTurnResponse;
import fruition.agent.exception.InvalidAgentTurnRequestException;
import fruition.agent.repository.PipelineAgentRequester;
import fruition.document.domain.DocumentStatus;
import fruition.document.exception.DocumentVersionConflictException;
import fruition.document.dto.DocumentDetailResponse;
import fruition.document.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTurnServiceTest {

    @Mock DocumentService documentService;
    @Mock PipelineAgentRequester pipelineAgentRequester;

    private AgentTurnService service;

    @BeforeEach
    void setUp() {
        service = new AgentTurnService(documentService, pipelineAgentRequester);
    }

    @Test
    void turn_verifiesDocumentAndPreservesVersion() throws Exception {
        AgentTurnRequest request = request("whole_document", 1, 2);
        when(documentService.findById("ws_1", "user_1", "doc_1")).thenReturn(document("note.md", "text/markdown"));
        when(pipelineAgentRequester.request(request))
                .thenReturn(new ObjectMapper().readTree("{\"action\":\"markdown_edit\"}"));

        AgentTurnResponse response = service.turn("ws_1", "user_1", request);

        assertThat(response.documentId()).isEqualTo("doc_1");
        assertThat(response.baseVersion()).isEqualTo(7L);
        assertThat(response.requestId()).startsWith("agent_");
        assertThat(response.result().path("action").asText()).isEqualTo("markdown_edit");
        verify(documentService).findById("ws_1", "user_1", "doc_1");
    }

    @Test
    void turn_rejectsNonMarkdownBeforePipelineCall() {
        AgentTurnRequest request = request("whole_document", 1, 2);
        when(documentService.findById("ws_1", "user_1", "doc_1")).thenReturn(document("paper.pdf", "application/pdf"));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", request))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(pipelineAgentRequester, never()).request(request);
    }

    @Test
    void turn_rejectsOutOfBoundsTargetBeforePipelineCall() {
        AgentTurnRequest request = request("whole_document", 1, 3);
        when(documentService.findById("ws_1", "user_1", "doc_1")).thenReturn(document("note.md", "text/markdown"));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", request))
                .isInstanceOf(InvalidAgentTurnRequestException.class);
        verify(pipelineAgentRequester, never()).request(request);
    }

    @Test
    void turn_rejectsStaleBaseVersionBeforePipelineCall() {
        AgentTurnRequest request = request("whole_document", 1, 2);
        when(documentService.findById("ws_1", "user_1", "doc_1"))
                .thenReturn(document("note.md", "text/markdown", 9));

        assertThatThrownBy(() -> service.turn("ws_1", "user_1", request))
                .isInstanceOf(DocumentVersionConflictException.class);
        verify(pipelineAgentRequester, never()).request(request);
    }

    private AgentTurnRequest request(String type, int startLine, int endLine) {
        return new AgentTurnRequest(
                "doc_1",
                7L,
                "문서를 점검해줘",
                null,
                new AgentTurnRequest.EditorSnapshot(
                        "# 제목\n본문",
                        new AgentTurnRequest.Target(type, startLine, endLine))
        );
    }

    private DocumentDetailResponse document(String filename, String mimeType) {
        return document(filename, mimeType, 7);
    }

    private DocumentDetailResponse document(String filename, String mimeType, long currentVersion) {
        return new DocumentDetailResponse(
                "doc_1", filename, mimeType, 10, DocumentStatus.completed,
                "s3://source", null, Instant.now(), Instant.now(), null, List.of(),
                null, null, null, filename.substring(0, filename.lastIndexOf('.')),
                filename.substring(filename.lastIndexOf('.') + 1), null, false, currentVersion,
                null, Instant.now(), null
        );
    }
}
