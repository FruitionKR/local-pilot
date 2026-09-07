package fruition.access.workspace.service;

import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.WorkspaceMemberListResponse;
import fruition.access.workspace.dto.WorkspaceMemberResponse;
import fruition.access.workspace.dto.WorkspaceMemberRoleUpdateRequest;
import fruition.access.workspace.exception.LastOwnerException;
import fruition.access.workspace.exception.WorkspaceAccessDeniedException;
import fruition.access.workspace.exception.WorkspaceMemberNotFoundException;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceMemberService {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final AuthzProjectionStore authzProjectionStore;

    public WorkspaceMemberService(WorkspaceMemberRepository workspaceMemberRepository,
                                  AuthzProjectionStore authzProjectionStore) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.authzProjectionStore = authzProjectionStore;
    }

    public WorkspaceMemberListResponse list(String userId, String workspaceId) {
        requireMember(workspaceId, userId);
        return new WorkspaceMemberListResponse(
                workspaceMemberRepository.findActiveMembers(workspaceId).stream()
                        .map(WorkspaceMemberResponse::from)
                        .toList()
        );
    }

    @Transactional
    public WorkspaceMemberResponse changeRole(
            String actorId,
            String workspaceId,
            String targetUserId,
            WorkspaceMemberRoleUpdateRequest request
    ) {
        requireOwner(workspaceId, actorId);

        WorkspaceMember target = findMember(workspaceId, targetUserId);
        if (target.getRole() == request.role()) {
            return WorkspaceMemberResponse.from(target);
        }
        if (target.getRole() == WorkspaceRole.OWNER && isLastOwner(workspaceId)) {
            throw new LastOwnerException(workspaceId);
        }

        target.changeRole(request.role());
        authzProjectionStore.evict(workspaceId, targetUserId);
        return WorkspaceMemberResponse.from(target);
    }

    /** OWNER의 멤버 제거와 본인 탈퇴를 겸한다. */
    @Transactional
    public void remove(String actorId, String workspaceId, String targetUserId) {
        WorkspaceRole actorRole = requireMember(workspaceId, actorId);
        if (!actorId.equals(targetUserId) && actorRole != WorkspaceRole.OWNER) {
            throw new WorkspaceAccessDeniedException("다른 멤버를 제거하려면 OWNER 권한이 필요합니다.");
        }

        WorkspaceMember target = findMember(workspaceId, targetUserId);
        if (target.getRole() == WorkspaceRole.OWNER && isLastOwner(workspaceId)) {
            throw new LastOwnerException(workspaceId);
        }

        workspaceMemberRepository.delete(target);
        authzProjectionStore.evict(workspaceId, targetUserId);
    }

    /**
     * 호출자의 활성 역할을 확인한다. 비멤버이거나 삭제된 워크스페이스면
     * 존재 자체를 숨기려 404로 돌려준다.
     */
    private WorkspaceRole requireMember(String workspaceId, String userId) {
        return workspaceMemberRepository.findActiveRole(workspaceId, userId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
    }

    private void requireOwner(String workspaceId, String userId) {
        if (requireMember(workspaceId, userId) != WorkspaceRole.OWNER) {
            throw new WorkspaceAccessDeniedException("멤버 역할을 변경하려면 OWNER 권한이 필요합니다.");
        }
    }

    private WorkspaceMember findMember(String workspaceId, String userId) {
        return workspaceMemberRepository.findByWorkspace_IdAndUser_Id(workspaceId, userId)
                .orElseThrow(() -> new WorkspaceMemberNotFoundException(workspaceId, userId));
    }

    private boolean isLastOwner(String workspaceId) {
        return workspaceMemberRepository.countActiveByRole(workspaceId, WorkspaceRole.OWNER) <= 1;
    }
}
