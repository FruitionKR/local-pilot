package fruition.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import fruition.agent.dto.AgentToolExecuteRequest;
import fruition.agent.dto.AgentToolReadRequest;
import fruition.document.dto.DocumentDetailResponse;
import fruition.document.dto.DocumentPositionRequest;
import fruition.document.dto.DocumentRenameRequest;
import fruition.document.dto.FolderCreateRequest;
import fruition.document.dto.FolderPositionRequest;
import fruition.document.dto.FolderRenameRequest;
import fruition.document.repository.DocumentEditStateRepository;
import fruition.document.service.DocumentPlacementService;
import fruition.document.service.DocumentService;
import fruition.document.service.FolderService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentToolService {
    private static final Set<String> READ_TOOLS = Set.of(
            "list_root_items", "list_folder_children", "get_document_metadata", "get_document_content");
    private static final Set<String> EXECUTE_TOOLS = Set.of(
            "create_folder", "rename_folder", "move_folder", "move_document", "rename_document");

    private final JdbcTemplate jdbcTemplate;
    private final FolderService folderService;
    private final DocumentService documentService;
    private final DocumentPlacementService documentPlacementService;
    private final DocumentEditStateRepository editStateRepository;

    public AgentToolService(
            JdbcTemplate jdbcTemplate,
            FolderService folderService,
            DocumentService documentService,
            DocumentPlacementService documentPlacementService,
            DocumentEditStateRepository editStateRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.folderService = folderService;
        this.documentService = documentService;
        this.documentPlacementService = documentPlacementService;
        this.editStateRepository = editStateRepository;
    }

    public Object read(String toolName, AgentToolReadRequest request) {
        requireTool(READ_TOOLS, toolName);
        requireRunScope(request.runId(), request.workspaceId(), request.userId());
        return switch (toolName) {
            case "list_root_items" -> {
                requireArguments(request.arguments(), Set.of());
                yield folderService.children(request.workspaceId(), request.userId(), null);
            }
            case "list_folder_children" -> {
                requireArguments(request.arguments(), Set.of("folder_id"));
                yield folderService.children(
                        request.workspaceId(), request.userId(), uuid(request.arguments(), "folder_id"));
            }
            case "get_document_metadata" -> {
                requireArguments(request.arguments(), Set.of("document_id"));
                DocumentDetailResponse detail = documentService.findById(
                        request.workspaceId(), request.userId(), text(request.arguments(), "document_id"));
                yield Map.of(
                        "id", detail.id(),
                        "display_name", detail.displayName(),
                        "current_version", detail.currentVersion());
            }
            case "get_document_content" -> {
                requireArguments(request.arguments(), Set.of("document_id"));
                String documentId = text(request.arguments(), "document_id");
                DocumentDetailResponse detail = documentService.findById(
                        request.workspaceId(), request.userId(), documentId);
                var state = editStateRepository.findById(documentId)
                        .orElseThrow(() -> badRequest("문서 편집 상태를 찾을 수 없습니다."));
                yield Map.of(
                        "id", detail.id(),
                        "markdown", state.getMarkdown(),
                        "content_hash", state.getContentHash(),
                        "current_version", detail.currentVersion());
            }
            default -> throw badRequest("지원하지 않는 read Tool입니다.");
        };
    }

    public Object execute(String toolName, AgentToolExecuteRequest request) {
        requireTool(EXECUTE_TOOLS, toolName);
        requireApprovedOperation(toolName, request);
        JsonNode arguments = request.arguments();
        return switch (toolName) {
            case "create_folder" -> {
                requireArguments(arguments, Set.of("name", "parent_folder_id"));
                yield folderService.create(request.workspaceId(), request.userId(), request.idempotencyKey(),
                        new FolderCreateRequest(text(arguments, "name"), nullableUuid(arguments, "parent_folder_id")));
            }
            case "rename_folder" -> {
                requireArguments(arguments, Set.of("folder_id", "name", "base_version"));
                yield folderService.rename(request.workspaceId(), request.userId(), uuid(arguments, "folder_id"),
                        request.idempotencyKey(),
                        new FolderRenameRequest(text(arguments, "name"), positiveLong(arguments, "base_version")));
            }
            case "move_folder" -> {
                requireArguments(arguments, Set.of("folder_id", "parent_folder_id", "position", "base_version"));
                yield folderService.move(request.workspaceId(), request.userId(), uuid(arguments, "folder_id"),
                        request.idempotencyKey(), new FolderPositionRequest(
                                nullableUuid(arguments, "parent_folder_id"), nullableInteger(arguments, "position"),
                                positiveLong(arguments, "base_version")));
            }
            case "move_document" -> {
                requireArguments(arguments, Set.of("document_id", "folder_id", "position", "base_version"));
                yield documentPlacementService.move(request.workspaceId(), request.userId(),
                        text(arguments, "document_id"), request.idempotencyKey(), new DocumentPositionRequest(
                                nullableUuid(arguments, "folder_id"), nullableInteger(arguments, "position"),
                                positiveLong(arguments, "base_version")));
            }
            case "rename_document" -> {
                requireArguments(arguments, Set.of("document_id", "display_name", "base_version"));
                yield documentService.rename(request.workspaceId(), request.userId(),
                        text(arguments, "document_id"), new DocumentRenameRequest(
                                text(arguments, "display_name"), positiveLong(arguments, "base_version")));
            }
            default -> throw badRequest("지원하지 않는 mutation Tool입니다.");
        };
    }

    private void requireRunScope(String runId, String workspaceId, String userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM agent_runs
                 WHERE id = ? AND workspace_id = ? AND user_id = ?
                """, Integer.class, runId, workspaceId, userId);
        if (count == null || count != 1) {
            throw badRequest("AgentRun 범위가 요청 사용자 또는 Workspace와 일치하지 않습니다.");
        }
    }

    private void requireApprovedOperation(String toolName, AgentToolExecuteRequest request) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM agent_runs run
                  JOIN agent_plans plan ON plan.id = run.current_plan_id
                  JOIN agent_plan_operations operation ON operation.plan_id = plan.id
                  JOIN agent_approvals approval ON approval.plan_id = plan.id
                 WHERE run.id = ? AND run.workspace_id = ? AND run.user_id = ?
                   AND run.status IN ('executing', 'verifying')
                   AND plan.id = ? AND plan.version = ? AND plan.operation_hash = ?
                   AND plan.status = 'approved'
                   AND operation.id = ? AND operation.tool_name = ? AND operation.status = 'pending'
                   AND approval.user_id = run.user_id AND approval.decision = 'approved'
                   AND approval.plan_version = plan.version AND approval.operation_hash = plan.operation_hash
                """, Integer.class,
                request.runId(), request.workspaceId(), request.userId(), request.planId(), request.planVersion(),
                request.operationHash(), request.operationId(), toolName);
        if (count == null || count != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "승인된 현재 Agent operation과 요청이 일치하지 않습니다.");
        }
    }

    private static void requireTool(Set<String> allowed, String toolName) {
        if (!allowed.contains(toolName)) {
            throw badRequest("허용되지 않은 Agent Tool입니다.");
        }
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

    private static Long positiveLong(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 1) {
            throw badRequest(field + " 값은 1 이상이어야 합니다.");
        }
        return value.longValue();
    }

    private static Integer nullableInteger(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt() || value.intValue() < 0) {
            throw badRequest(field + " 값은 0 이상이어야 합니다.");
        }
        return value.intValue();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
