package fruition.workspace.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.document.domain.IdempotencyRecord;
import fruition.document.exception.IdempotencyConflictException;
import fruition.document.exception.InvalidIdempotencyKeyException;
import fruition.document.repository.IdempotencyRecordRepository;
import fruition.document.service.DocumentService;
import fruition.user.repository.UserRepository;
import fruition.workspace.domain.Workspace;
import fruition.workspace.domain.WorkspaceMember;
import fruition.workspace.domain.WorkspaceRole;
import fruition.workspace.dto.WorkspaceCreateRequest;
import fruition.workspace.dto.WorkspaceListResponse;
import fruition.workspace.dto.WorkspaceLifecycleResponse;
import fruition.workspace.dto.WorkspaceRenameRequest;
import fruition.workspace.dto.WorkspaceResponse;
import fruition.workspace.dto.WorkspaceTrashResponse;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import fruition.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final DocumentService documentService;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository workspaceMemberRepository,
                            UserRepository userRepository,
                            DocumentService documentService,
                            IdempotencyRecordRepository idempotencyRecordRepository,
                            ObjectMapper objectMapper) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.documentService = documentService;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Workspace createDefault(String userId, String displayName) {
        return createWorkspace(userId, displayName + "의 워크스페이스");
    }

    @Transactional
    public WorkspaceResponse create(String userId, WorkspaceCreateRequest request) {
        Workspace workspace = createWorkspace(userId, request.name().trim());
        return toResponse(workspace);
    }

    public WorkspaceListResponse list(String userId) {
        return new WorkspaceListResponse(
                workspaceMemberRepository.findAllWorkspacesByUserId(userId).stream()
                        .map(this::toResponse)
                        .toList()
        );
    }

    @Transactional
    public WorkspaceResponse rename(String userId, String workspaceId, WorkspaceRenameRequest request) {
        Workspace workspace = findOwned(userId, workspaceId);
        workspace.rename(request.name().trim());
        return toResponse(workspace);
    }

    @Transactional
    public WorkspaceLifecycleResponse delete(
            String userId,
            String workspaceId,
            String idempotencyKey
    ) {
        validateIdempotencyKey(idempotencyKey);
        Workspace workspace = findOwnedIncludingDeleted(userId, workspaceId);
        String endpointScope = "DELETE:/api/workspaces";
        String requestHash = requestHash(workspaceId, "delete");
        Optional<WorkspaceLifecycleResponse> replay =
                replayIdempotentRequest(userId, endpointScope, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (workspace.getDeletedAt() != null) {
            throw new WorkspaceNotFoundException(workspaceId);
        }

        Instant deletedAt = Instant.now();
        workspace.softDelete(userId, deletedAt);
        WorkspaceLifecycleResponse response =
                new WorkspaceLifecycleResponse(workspaceId, true, deletedAt);
        saveIdempotencyRecord(userId, endpointScope, idempotencyKey, requestHash, response);
        return response;
    }

    @Transactional
    public WorkspaceLifecycleResponse restore(
            String userId,
            String workspaceId,
            String idempotencyKey
    ) {
        validateIdempotencyKey(idempotencyKey);
        Workspace workspace = findOwnedIncludingDeleted(userId, workspaceId);
        String endpointScope = "POST:/api/workspaces/restore";
        String requestHash = requestHash(workspaceId, "restore");
        Optional<WorkspaceLifecycleResponse> replay =
                replayIdempotentRequest(userId, endpointScope, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (workspace.getDeletedAt() == null) {
            throw new WorkspaceNotFoundException(workspaceId);
        }

        workspace.restore(Instant.now());
        WorkspaceLifecycleResponse response =
                new WorkspaceLifecycleResponse(workspaceId, false, null);
        saveIdempotencyRecord(userId, endpointScope, idempotencyKey, requestHash, response);
        return response;
    }

    public WorkspaceTrashResponse trash(String userId) {
        return new WorkspaceTrashResponse(
                workspaceMemberRepository.findDeletedOwnedWorkspaces(userId, WorkspaceRole.OWNER).stream()
                        .map(workspace -> new WorkspaceTrashResponse.WorkspaceTrashItem(
                                workspace.getId(),
                                workspace.getName(),
                                workspace.getDeletedAt(),
                                workspace.getDeletedBy()
                        ))
                        .toList()
        );
    }

    private Workspace createWorkspace(String userId, String name) {
        String workspaceId = "ws_" + UUID.randomUUID().toString().replace("-", "");
        Workspace workspace = new Workspace(workspaceId, name);
        workspaceRepository.save(workspace);

        WorkspaceMember owner = new WorkspaceMember(
                workspace,
                userRepository.getReferenceById(userId),
                WorkspaceRole.OWNER
        );
        workspaceMemberRepository.save(owner);
        documentService.createInitialNote(workspaceId, userId);

        return workspace;
    }

    private Workspace findOwned(String userId, String workspaceId) {
        Workspace workspace = findOwnedIncludingDeleted(userId, workspaceId);
        if (workspace.getDeletedAt() != null) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        return workspace;
    }

    private Workspace findOwnedIncludingDeleted(String userId, String workspaceId) {
        return workspaceMemberRepository.findOwnedWorkspaceIncludingDeleted(
                        workspaceId,
                        userId,
                        WorkspaceRole.OWNER
                )
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key는 1자 이상 255자 이하여야 합니다.");
        }
    }

    private Optional<WorkspaceLifecycleResponse> replayIdempotentRequest(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash
    ) {
        Optional<IdempotencyRecord> found =
                idempotencyRecordRepository.findByUserIdAndEndpointScopeAndIdempotencyKey(
                        userId, endpointScope, idempotencyKey);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord record = found.get();
        if (!record.getExpiresAt().isAfter(Instant.now())) {
            idempotencyRecordRepository.delete(record);
            idempotencyRecordRepository.flush();
            return Optional.empty();
        }
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "같은 Idempotency-Key를 다른 요청에 사용할 수 없습니다.");
        }
        try {
            return Optional.of(objectMapper.readValue(
                    record.getResponseBody(), WorkspaceLifecycleResponse.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("멱등성 응답을 복원할 수 없습니다.", exception);
        }
    }

    private void saveIdempotencyRecord(
            String userId,
            String endpointScope,
            String idempotencyKey,
            String requestHash,
            WorkspaceLifecycleResponse response
    ) {
        Instant now = Instant.now();
        try {
            idempotencyRecordRepository.save(new IdempotencyRecord(
                    UUID.randomUUID(),
                    userId,
                    endpointScope,
                    idempotencyKey,
                    requestHash,
                    200,
                    response.id(),
                    objectMapper.writeValueAsString(response),
                    now,
                    now.plusSeconds(24 * 60 * 60)
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("멱등성 응답을 저장할 수 없습니다.", exception);
        }
    }

    private String requestHash(String workspaceId, String action) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((workspaceId + "\0" + action).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    private WorkspaceResponse toResponse(Workspace workspace) {
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getCreatedAt(), workspace.getUpdatedAt());
    }
}
