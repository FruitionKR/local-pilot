package fruition.access.workspace.service;

import fruition.shared.idempotency.IdempotencyService;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.WorkspaceCreateRequest;
import fruition.access.workspace.dto.WorkspaceListResponse;
import fruition.access.workspace.dto.WorkspaceLifecycleResponse;
import fruition.access.workspace.dto.WorkspaceRenameRequest;
import fruition.access.workspace.dto.WorkspaceResponse;
import fruition.access.workspace.dto.WorkspaceTrashResponse;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final DocumentInternalClient documentInternalClient;
    private final IdempotencyService idempotencyService;
    private final AuthzProjectionStore authzProjectionStore;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository workspaceMemberRepository,
                            UserRepository userRepository,
                            DocumentInternalClient documentInternalClient,
                            IdempotencyService idempotencyService,
                            AuthzProjectionStore authzProjectionStore) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.documentInternalClient = documentInternalClient;
        this.idempotencyService = idempotencyService;
        this.authzProjectionStore = authzProjectionStore;
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
        String endpointScope = "DELETE:/api/workspaces";
        String requestHash = idempotencyService.requestHash(workspaceId, "delete");
        return idempotencyService.execute(
                userId, endpointScope, idempotencyKey, requestHash,
                WorkspaceLifecycleResponse.class, 200, WorkspaceLifecycleResponse::id,
                () -> {
                    Workspace workspace = findOwnedIncludingDeleted(userId, workspaceId);
                    if (workspace.getDeletedAt() != null) {
                        throw new WorkspaceNotFoundException(workspaceId);
                    }
                    Instant deletedAt = Instant.now();
                    workspace.softDelete(userId, deletedAt);
                    // 삭제된 워크스페이스는 멤버 전원이 NONE 판정이 되도록 projection을 무효화한다.
                    authzProjectionStore.evictWorkspace(workspaceId);
                    return new WorkspaceLifecycleResponse(workspaceId, true, deletedAt);
                });
    }

    @Transactional
    public WorkspaceLifecycleResponse restore(
            String userId,
            String workspaceId,
            String idempotencyKey
    ) {
        String endpointScope = "POST:/api/workspaces/restore";
        String requestHash = idempotencyService.requestHash(workspaceId, "restore");
        return idempotencyService.execute(
                userId, endpointScope, idempotencyKey, requestHash,
                WorkspaceLifecycleResponse.class, 200, WorkspaceLifecycleResponse::id,
                () -> {
                    Workspace workspace = findOwnedIncludingDeleted(userId, workspaceId);
                    if (workspace.getDeletedAt() == null) {
                        throw new WorkspaceNotFoundException(workspaceId);
                    }
                    workspace.restore(Instant.now());
                    // 복구 즉시 캐시된 NONE 판정이 남지 않도록 projection을 무효화한다.
                    authzProjectionStore.evictWorkspace(workspaceId);
                    return new WorkspaceLifecycleResponse(workspaceId, false, null);
                });
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
        authzProjectionStore.put(workspaceId, userId, WorkspaceRole.OWNER);
        // 초기 노트는 편의 기능이라 best-effort. document-svc가 방금 만든 워크스페이스를
        // 볼 수 있도록 이 트랜잭션이 커밋된 뒤에 호출한다(커밋 전엔 FK 위반).
        runAfterCommit(() -> documentInternalClient.createInitialNote(workspaceId, userId));

        return workspace;
    }

    /** 트랜잭션이 활성이면 커밋 후에, 아니면 즉시 실행한다. */
    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
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

    private WorkspaceResponse toResponse(Workspace workspace) {
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getCreatedAt(), workspace.getUpdatedAt());
    }
}
