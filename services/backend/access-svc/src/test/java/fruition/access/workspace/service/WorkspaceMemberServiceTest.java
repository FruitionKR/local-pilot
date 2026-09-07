package fruition.access.workspace.service;

import fruition.access.user.domain.User;
import fruition.access.workspace.domain.Workspace;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceMemberServiceTest {

    private static final String WORKSPACE_ID = "ws_abc12345";
    private static final String OWNER_ID = "user_owner";
    private static final String MEMBER_ID = "user_member";

    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock AuthzProjectionStore authzProjectionStore;

    WorkspaceMemberService workspaceMemberService;

    @BeforeEach
    void setUp() {
        workspaceMemberService = new WorkspaceMemberService(workspaceMemberRepository, authzProjectionStore);
    }

    private WorkspaceMember member(String userId, WorkspaceRole role) {
        Workspace workspace = new Workspace(WORKSPACE_ID, "워크스페이스");
        User user = new User(userId, userId + "@example.com", User.PROVIDER_LOCAL, userId, null);
        return new WorkspaceMember(workspace, user, role);
    }

    private void actorRole(String userId, WorkspaceRole role) {
        when(workspaceMemberRepository.findActiveRole(WORKSPACE_ID, userId)).thenReturn(Optional.of(role));
    }

    @Test
    void list_memberSeesAllMembers() {
        actorRole(MEMBER_ID, WorkspaceRole.MEMBER);
        when(workspaceMemberRepository.findActiveMembers(WORKSPACE_ID))
                .thenReturn(List.of(member(OWNER_ID, WorkspaceRole.OWNER), member(MEMBER_ID, WorkspaceRole.MEMBER)));

        WorkspaceMemberListResponse response = workspaceMemberService.list(MEMBER_ID, WORKSPACE_ID);

        assertThat(response.members()).extracting(WorkspaceMemberResponse::userId)
                .containsExactly(OWNER_ID, MEMBER_ID);
        assertThat(response.members().getFirst().role()).isEqualTo("OWNER");
        assertThat(response.members().getFirst().provider()).isEqualTo(User.PROVIDER_LOCAL);
    }

    @Test
    void list_nonMemberGets404() {
        when(workspaceMemberRepository.findActiveRole(WORKSPACE_ID, "user_stranger")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workspaceMemberService.list("user_stranger", WORKSPACE_ID))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(workspaceMemberRepository, never()).findActiveMembers(WORKSPACE_ID);
    }

    @Test
    void changeRole_ownerPromotesMember_evictsProjection() {
        actorRole(OWNER_ID, WorkspaceRole.OWNER);
        WorkspaceMember target = member(MEMBER_ID, WorkspaceRole.MEMBER);
        when(workspaceMemberRepository.findByWorkspace_IdAndUser_Id(WORKSPACE_ID, MEMBER_ID))
                .thenReturn(Optional.of(target));

        WorkspaceMemberResponse response = workspaceMemberService.changeRole(
                OWNER_ID, WORKSPACE_ID, MEMBER_ID, new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.OWNER));

        assertThat(response.role()).isEqualTo("OWNER");
        assertThat(target.getRole()).isEqualTo(WorkspaceRole.OWNER);
        verify(authzProjectionStore).evict(WORKSPACE_ID, MEMBER_ID);
    }

    @Test
    void changeRole_nonOwnerGets403() {
        actorRole(MEMBER_ID, WorkspaceRole.MEMBER);

        assertThatThrownBy(() -> workspaceMemberService.changeRole(
                MEMBER_ID, WORKSPACE_ID, OWNER_ID, new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.MEMBER)))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        verifyNoInteractions(authzProjectionStore);
    }

    @Test
    void changeRole_targetNotMemberGets404() {
        actorRole(OWNER_ID, WorkspaceRole.OWNER);
        when(workspaceMemberRepository.findByWorkspace_IdAndUser_Id(WORKSPACE_ID, "user_stranger"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> workspaceMemberService.changeRole(
                OWNER_ID, WORKSPACE_ID, "user_stranger", new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.MEMBER)))
                .isInstanceOf(WorkspaceMemberNotFoundException.class);
    }

    @Test
    void changeRole_lastOwnerCannotBeDemoted() {
        actorRole(OWNER_ID, WorkspaceRole.OWNER);
        when(workspaceMemberRepository.findByWorkspace_IdAndUser_Id(WORKSPACE_ID, OWNER_ID))
                .thenReturn(Optional.of(member(OWNER_ID, WorkspaceRole.OWNER)));
        when(workspaceMemberRepository.countActiveByRole(WORKSPACE_ID, WorkspaceRole.OWNER)).thenReturn(1L);

        assertThatThrownBy(() -> workspaceMemberService.changeRole(
                OWNER_ID, WORKSPACE_ID, OWNER_ID, new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.MEMBER)))
                .isInstanceOf(LastOwnerException.class);
        verifyNoInteractions(authzProjectionStore);
    }

    @Test
    void changeRole_ownerDemotedWhenAnotherOwnerRemains() {
        actorRole(OWNER_ID, WorkspaceRole.OWNER);
        WorkspaceMember target = member(MEMBER_ID, WorkspaceRole.OWNER);
        when(workspaceMemberRepository.findByWorkspace_IdAndUser_Id(WORKSPACE_ID, MEMBER_ID))
                .thenReturn(Optional.of(target));
        when(workspaceMemberRepository.countActiveByRole(WORKSPACE_ID, WorkspaceRole.OWNER)).thenReturn(2L);

        workspaceMemberService.changeRole(
                OWNER_ID, WORKSPACE_ID, MEMBER_ID, new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.MEMBER));

        assertThat(target.getRole()).isEqualTo(WorkspaceRole.MEMBER);
        verify(authzProjectionStore).evict(WORKSPACE_ID, MEMBER_ID);
    }

    @Test
    void changeRole_sameRoleIsNoOp() {
        actorRole(OWNER_ID, WorkspaceRole.OWNER);
        when(workspaceMemberRepository.findByWorkspace_IdAndUser_Id(WORKSPACE_ID, OWNER_ID))
                .thenReturn(Optional.of(member(OWNER_ID, WorkspaceRole.OWNER)));

        WorkspaceMemberResponse response = workspaceMemberService.changeRole(
                OWNER_ID, WORKSPACE_ID, OWNER_ID, new WorkspaceMemberRoleUpdateRequest(WorkspaceRole.OWNER));

        assertThat(response.role()).isEqualTo("OWNER");
        verifyNoInteractions(authzProjectionStore);
    }

    @Test
    void remove_ownerRemovesMember() {
        actorRole(OWNER_ID, WorkspaceRole.OWNER);
        WorkspaceMember target = member(MEMBER_ID, WorkspaceRole.MEMBER);
        when(workspaceMemberRepository.findByWorkspace_IdAndUser_Id(WORKSPACE_ID, MEMBER_ID))
                .thenReturn(Optional.of(target));

        workspaceMemberService.remove(OWNER_ID, WORKSPACE_ID, MEMBER_ID);

        verify(workspaceMemberRepository).delete(target);
        verify(authzProjectionStore).evict(WORKSPACE_ID, MEMBER_ID);
    }

    @Test
    void remove_memberLeavesByRemovingSelf() {
        actorRole(MEMBER_ID, WorkspaceRole.MEMBER);
        WorkspaceMember target = member(MEMBER_ID, WorkspaceRole.MEMBER);
        when(workspaceMemberRepository.findByWorkspace_IdAndUser_Id(WORKSPACE_ID, MEMBER_ID))
                .thenReturn(Optional.of(target));

        workspaceMemberService.remove(MEMBER_ID, WORKSPACE_ID, MEMBER_ID);

        verify(workspaceMemberRepository).delete(target);
        verify(authzProjectionStore).evict(WORKSPACE_ID, MEMBER_ID);
    }

    @Test
    void remove_memberCannotRemoveOthers() {
        actorRole(MEMBER_ID, WorkspaceRole.MEMBER);

        assertThatThrownBy(() -> workspaceMemberService.remove(MEMBER_ID, WORKSPACE_ID, OWNER_ID))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        verify(workspaceMemberRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void remove_lastOwnerCannotLeave() {
        actorRole(OWNER_ID, WorkspaceRole.OWNER);
        when(workspaceMemberRepository.findByWorkspace_IdAndUser_Id(WORKSPACE_ID, OWNER_ID))
                .thenReturn(Optional.of(member(OWNER_ID, WorkspaceRole.OWNER)));
        when(workspaceMemberRepository.countActiveByRole(WORKSPACE_ID, WorkspaceRole.OWNER)).thenReturn(1L);

        assertThatThrownBy(() -> workspaceMemberService.remove(OWNER_ID, WORKSPACE_ID, OWNER_ID))
                .isInstanceOf(LastOwnerException.class);
        verify(workspaceMemberRepository, never()).delete(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(authzProjectionStore);
    }
}
