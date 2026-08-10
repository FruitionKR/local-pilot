package fruition.core.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.core.agent.dto.AgentToolExecuteRequest;
import fruition.core.agent.dto.AgentToolReadRequest;
import fruition.core.agent.repository.PipelineAgentToolAuthorizationClient;
import fruition.core.authz.WorkspaceAccessGuard;
import fruition.core.document.domain.Document;
import fruition.core.document.dto.DocumentPositionRequest;
import fruition.core.document.dto.DocumentRenameRequest;
import fruition.core.document.dto.DocumentRenameResponse;
import fruition.core.document.dto.FolderCreateRequest;
import fruition.core.document.dto.FolderPositionRequest;
import fruition.core.document.dto.FolderRenameRequest;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.mongo.MongoDocumentEditState;
import fruition.core.document.mongo.MongoDocumentEditStore;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.document.service.DocumentPlacementService;
import fruition.core.document.service.DocumentService;
import fruition.core.document.service.FolderService;
import fruition.shared.idempotency.IdempotencyService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentToolService {

    private static final Map<String, Set<String>> READ_TOOL_ARGUMENTS = Map.of(
            "list_root_items", Set.of(),
            "list_folder_children", Set.of("folder_id"),
            "get_document_metadata", Set.of("document_id"),
            "get_document_content", Set.of("document_id"));
    private static final Map<String, Set<String>> EXECUTE_TOOL_ARGUMENTS = Map.of(
            "create_folder", Set.of("name", "parent_folder_id"),
            "rename_folder", Set.of("folder_id", "name", "base_version"),
            "move_folder", Set.of("folder_id", "parent_folder_id", "position", "base_version"),
            "move_document", Set.of("document_id", "folder_id", "position", "base_version"),
            "rename_document", Set.of("document_id", "display_name", "base_version"));

    private final PipelineAgentToolAuthorizationClient authorizationClient;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final FolderService folderService;
    private final DocumentService documentService;
    private final DocumentPlacementService documentPlacementService;
    private final DocumentRepository documentRepository;
    private final MongoDocumentEditStore mongoDocumentEditStore;
    private final IdempotencyService idempotencyService;

    public AgentToolService(
            PipelineAgentToolAuthorizationClient authorizationClient,
            WorkspaceAccessGuard workspaceAccessGuard,
            FolderService folderService,
            DocumentService documentService,
            DocumentPlacementService documentPlacementService,
            DocumentRepository documentRepository,
            MongoDocumentEditStore mongoDocumentEditStore,
            IdempotencyService idempotencyService) {
        this.authorizationClient = authorizationClient;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.folderService = folderService;
        this.documentService = documentService;
        this.documentPlacementService = documentPlacementService;
        this.documentRepository = documentRepository;
        this.mongoDocumentEditStore = mongoDocumentEditStore;
        this.idempotencyService = idempotencyService;
    }

    public Object read(String toolName, AgentToolReadRequest request) {
        requireToolArguments(READ_TOOL_ARGUMENTS, toolName, request.arguments());
        authorizationClient.authorizeRead(request);
        return switch (toolName) {
            case "list_root_items" -> folderService.children(request.workspaceId(), request.userId(), null);
            case "list_folder_children" -> folderService.children(
                    request.workspaceId(), request.userId(), uuid(request.arguments(), "folder_id"));
            case "get_document_metadata" -> documentMetadata(request);
            case "get_document_content" -> documentContent(request);
            default -> throw badRequest("지원하지 않는 read Tool입니다.");
        };
    }

    @Transactional
    public Object execute(String toolName, AgentToolExecuteRequest request) {
        requireToolArguments(EXECUTE_TOOL_ARGUMENTS, toolName, request.arguments());
        authorizationClient.authorizeExecute(toolName, request);
        JsonNode arguments = request.arguments();
        return switch (toolName) {
            case "create_folder" -> folderService.create(
                    request.workspaceId(), request.userId(), request.idempotencyKey(),
                    new FolderCreateRequest(text(arguments, "name"), nullableUuid(arguments, "parent_folder_id")));
            case "rename_folder" -> folderService.rename(
                    request.workspaceId(), request.userId(), uuid(arguments, "folder_id"), request.idempotencyKey(),
                    new FolderRenameRequest(text(arguments, "name"), positiveLong(arguments, "base_version")));
            case "move_folder" -> folderService.move(
                    request.workspaceId(), request.userId(), uuid(arguments, "folder_id"), request.idempotencyKey(),
                    new FolderPositionRequest(nullableUuid(arguments, "parent_folder_id"),
                            nullableInteger(arguments, "position"), positiveLong(arguments, "base_version")));
            case "move_document" -> documentPlacementService.move(
                    request.workspaceId(), request.userId(), text(arguments, "document_id"),
                    request.idempotencyKey(), new DocumentPositionRequest(
                            nullableUuid(arguments, "folder_id"), nullableInteger(arguments, "position"),
                            positiveLong(arguments, "base_version")));
            case "rename_document" -> renameDocument(request);
            default -> throw badRequest("지원하지 않는 mutation Tool입니다.");
        };
    }

    private Map<String, Object> documentMetadata(AgentToolReadRequest request) {
        Document document = requireDocument(request.workspaceId(), request.userId(),
                text(request.arguments(), "document_id"));
        return Map.of(
                "id", document.getId(),
                "display_name", document.getDisplayName(),
                "current_version", document.getCurrentVersion());
    }

    private Map<String, Object> documentContent(AgentToolReadRequest request) {
        String documentId = text(request.arguments(), "document_id");
        requireDocument(request.workspaceId(), request.userId(), documentId);
        MongoDocumentEditState state = mongoDocumentEditStore.findState(documentId)
                .filter(value -> request.workspaceId().equals(value.getWorkspaceId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "문서의 canonical Mongo 편집 상태를 찾을 수 없습니다."));
        return Map.of(
                "id", state.getDocumentId(),
                "markdown", state.getMarkdown(),
                "content_hash", state.getContentHash(),
                "edit_revision", state.getRevision());
    }

    private Document requireDocument(String workspaceId, String userId, String documentId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        return documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private DocumentRenameResponse renameDocument(AgentToolExecuteRequest request) {
        JsonNode arguments = request.arguments();
        String documentId = text(arguments, "document_id");
        String displayName = text(arguments, "display_name");
        long baseVersion = positiveLong(arguments, "base_version");
        idempotencyService.validateKey(request.idempotencyKey());
        String scope = "POST:/internal/agent/tools/execute/rename_document";
        String requestHash = idempotencyService.requestHash(
                request.workspaceId(), documentId, displayName, String.valueOf(baseVersion));
        Optional<DocumentRenameResponse> replay = idempotencyService.replay(
                request.userId(), scope, request.idempotencyKey(), requestHash, DocumentRenameResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        DocumentRenameResponse response = documentService.rename(
                request.workspaceId(), request.userId(), documentId,
                new DocumentRenameRequest(displayName, baseVersion));
        idempotencyService.save(
                request.userId(), scope, request.idempotencyKey(), requestHash, 200, documentId, response);
        return response;
    }

    private static void requireToolArguments(
            Map<String, Set<String>> contracts,
            String toolName,
            JsonNode arguments) {
        Set<String> fields = contracts.get(toolName);
        if (fields == null) {
            throw badRequest("허용되지 않은 Agent Tool입니다.");
        }
        requireArguments(arguments, fields);
    }

    private static void requireArguments(JsonNode arguments, Set<String> fields) {
        if (!arguments.isObject() || arguments.size() != fields.size()
                || !fields.stream().allMatch(arguments::has)) {
            throw badRequest("Agent Tool arguments가 계약과 일치하지 않습니다.");
        }
    }

    private static String text(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw badRequest(field + " 값이 올바르지 않습니다.");
        }
        return value.textValue();
    }

    private static UUID uuid(JsonNode arguments, String field) {
        try {
            return UUID.fromString(text(arguments, field));
        } catch (IllegalArgumentException e) {
            throw badRequest(field + " 값이 UUID가 아닙니다.");
        }
    }

    private static UUID nullableUuid(JsonNode arguments, String field) {
        return arguments.get(field).isNull() ? null : uuid(arguments, field);
    }

    private static long positiveLong(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw badRequest(field + " 값은 1 이상이어야 합니다.");
        }
        return value.longValue();
    }

    private static Integer nullableInteger(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw badRequest(field + " 값은 0 이상이어야 합니다.");
        }
        return value.intValue();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
