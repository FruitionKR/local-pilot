package fruition.core.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.core.agent.dto.AgentToolExecuteRequest;
import fruition.core.agent.dto.AgentToolReadRequest;
import fruition.core.agent.repository.PipelineAgentToolAuthorizationClient;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.Document;
import fruition.core.document.dto.DocumentRenameResponse;
import fruition.core.document.dto.FolderPositionRequest;
import fruition.core.document.dto.FolderResponse;
import fruition.core.document.mongo.MongoDocumentEditState;
import fruition.core.document.mongo.MongoDocumentEditStore;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.DocumentPlacementService;
import fruition.core.document.service.DocumentService;
import fruition.core.document.service.FolderService;
import fruition.shared.idempotency.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentToolServiceTest {

    @Mock PipelineAgentToolAuthorizationClient authorizationClient;
    @Mock WorkspaceAccessGuard workspaceAccessGuard;
    @Mock FolderService folderService;
    @Mock DocumentService documentService;
    @Mock DocumentPlacementService documentPlacementService;
    @Mock DocumentRepository documentRepository;
    @Mock MongoDocumentEditStore mongoDocumentEditStore;
    @Mock IdempotencyService idempotencyService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentToolService service;

    @BeforeEach
    void setUp() {
        service = new AgentToolService(
                authorizationClient,
                workspaceAccessGuard,
                folderService,
                documentService,
                documentPlacementService,
                documentRepository,
                mongoDocumentEditStore,
                idempotencyService);
    }

    @Test
    void read_returnsCanonicalMongoMarkdownAndEditRevisionAfterAuthorization() throws Exception {
        Document document = org.mockito.Mockito.mock(Document.class);
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("document-1", "workspace-1"))
                .thenReturn(Optional.of(document));
        when(mongoDocumentEditStore.findState("document-1")).thenReturn(Optional.of(
                new MongoDocumentEditState(
                        "document-1", "workspace-1", "# canonical", 7, "sha256:abc", "user-1", Instant.now())));
        AgentToolReadRequest request = new AgentToolReadRequest(
                "run-1", "workspace-1", "user-1",
                objectMapper.readTree("{\"document_id\":\"document-1\"}"));

        Object result = service.read("get_document_content", request);

        Map<?, ?> content = (Map<?, ?>) result;
        assertThat(content.get("markdown")).isEqualTo("# canonical");
        assertThat(content.get("content_hash")).isEqualTo("sha256:abc");
        assertThat(content.get("edit_revision")).isEqualTo(7L);
        assertThat(content.containsKey("current_version")).isFalse();
        InOrder order = inOrder(authorizationClient, workspaceAccessGuard, mongoDocumentEditStore);
        order.verify(authorizationClient).authorizeRead(request);
        order.verify(workspaceAccessGuard).requireMember("workspace-1", "user-1");
        order.verify(mongoDocumentEditStore).findState("document-1");
    }

    @Test
    void execute_dispatchesOnlyStrictP0ContractAfterAiAuthorization() throws Exception {
        var arguments = objectMapper.readTree("{\"name\":\"새 폴더\",\"parent_folder_id\":null}");
        AgentToolExecuteRequest request = executeRequest(arguments);
        FolderResponse expected = new FolderResponse(
                UUID.randomUUID(), null, "새 폴더", 1, 1, null, null);
        when(folderService.create(eq("workspace-1"), eq("user-1"), eq("idem-1"), any()))
                .thenReturn(expected);

        Object result = service.execute("create_folder", request);

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(authorizationClient, folderService);
        order.verify(authorizationClient).authorizeExecute("create_folder", request);
        order.verify(folderService).create(eq("workspace-1"), eq("user-1"), eq("idem-1"), any());
    }

    @Test
    void execute_acceptsExactlyIntegralDecimalsAfterAiAuthorization() throws Exception {
        UUID folderId = UUID.randomUUID();
        var arguments = objectMapper.readTree("""
                {"folder_id":"%s","parent_folder_id":null,"position":1.0,"base_version":3.0}
                """.formatted(folderId));
        AgentToolExecuteRequest request = executeRequest(arguments);
        FolderResponse expected = new FolderResponse(folderId, null, "폴더", 1, 4, null, null);
        FolderPositionRequest expectedPosition = new FolderPositionRequest(null, 1, 3L);
        when(folderService.move("workspace-1", "user-1", folderId, "idem-1", expectedPosition))
                .thenReturn(expected);

        Object result = service.execute("move_folder", request);

        assertThat(result).isSameAs(expected);
        InOrder order = inOrder(authorizationClient, folderService);
        order.verify(authorizationClient).authorizeExecute("move_folder", request);
        order.verify(folderService).move("workspace-1", "user-1", folderId, "idem-1", expectedPosition);
    }

    @Test
    void execute_rejectsFractionalAndOutOfRangeNumbersAfterAiAuthorization() throws Exception {
        UUID folderId = UUID.randomUUID();
        for (String argumentsJson : List.of(
                "{\"folder_id\":\"%s\",\"parent_folder_id\":null,\"position\":1,\"base_version\":3.5}",
                "{\"folder_id\":\"%s\",\"parent_folder_id\":null,\"position\":2147483648,\"base_version\":3}",
                "{\"folder_id\":\"%s\",\"parent_folder_id\":null,\"position\":1,\"base_version\":9223372036854775808}"
        )) {
            AgentToolExecuteRequest request = executeRequest(
                    objectMapper.readTree(argumentsJson.formatted(folderId)));

            assertThatThrownBy(() -> service.execute("move_folder", request))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
            verify(authorizationClient).authorizeExecute("move_folder", request);
        }

        verifyNoInteractions(folderService);
    }

    @Test
    void execute_rejectsUnsupportedArtifactAndContentToolsWithoutAuthorization() {
        for (String toolName : new String[]{
                "list_agent_run_artifacts", "create_document", "apply_document_edit"
        }) {
            assertThatThrownBy(() -> service.execute(
                    toolName, executeRequest(objectMapper.createObjectNode())))
                    .isInstanceOf(ResponseStatusException.class);
        }

        verifyNoInteractions(authorizationClient, folderService, documentService, documentPlacementService);
    }

    @Test
    void renameDocumentConsumesIdempotencyKeyAndReplaysStoredResponse() throws Exception {
        AgentToolExecuteRequest request = executeRequest(objectMapper.readTree(
                "{\"document_id\":\"document-1\",\"display_name\":\"새 이름\",\"base_version\":3}"));
        DocumentRenameResponse replay = new DocumentRenameResponse(
                "document-1", "새 이름.md", "새 이름", 4, Instant.now(), true);
        when(idempotencyService.requestHash("workspace-1", "document-1", "새 이름", "3"))
                .thenReturn("request-hash");
        when(idempotencyService.replay(
                eq("user-1"),
                eq("POST:/internal/agent/tools/execute/rename_document"),
                eq("idem-1"),
                eq("request-hash"),
                eq(DocumentRenameResponse.class)))
                .thenReturn(Optional.of(replay));

        Object result = service.execute("rename_document", request);

        assertThat(result).isSameAs(replay);
        verify(idempotencyService).validateKey("idem-1");
        verify(documentService, never()).rename(any(), any(), any(), any());
        verify(idempotencyService, never()).save(any(), any(), any(), any(), anyInt(), any(), any());
    }

    private AgentToolExecuteRequest executeRequest(com.fasterxml.jackson.databind.JsonNode arguments) {
        return new AgentToolExecuteRequest(
                "run-1", "workspace-1", "user-1", "plan-1", 1, "a".repeat(64),
                "operation-1", "idem-1", arguments);
    }
}
