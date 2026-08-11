package fruition.access.workspace.service;

import fruition.shared.idempotency.IdempotencyService;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.WorkspaceCreateRequest;
import fruition.access.workspace.dto.WorkspaceRenameRequest;
import fruition.access.workspace.dto.WorkspaceResponse;
import fruition.access.workspace.dto.WorkspaceLifecycleResponse;
import fruition.access.workspace.dto.WorkspaceTrashResponse;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock WorkspaceRepository workspaceRepository;
    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock UserRepository userRepository;
    @Mock DocumentInternalClient documentInternalClient;
    @Mock IdempotencyService idempotencyService;
    @Mock AuthzProjectionStore authzProjectionStore;

    WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(
                workspaceRepository,
                workspaceMemberRepository,
                userRepository,
                documentInternalClient,
                idempotencyService,
                authzProjectionStore
        );
        lenient().when(userRepository.getReferenceById(any()))
                .thenAnswer(invocation -> new User(invocation.getArgument(0), "test@example.com", "test", null));
        lenient().when(idempotencyService.execute(
                any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(7).get());
    }

    @Test
    void createDefault_buildsNameFromDisplayName() {
        when(workspaceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Workspace workspace = workspaceService.createDefault("user_1f9a74af", "tes");

        assertThat(workspace.getName()).isEqualTo("tes의 워크스페이스");
        assertThat(workspace.getId()).startsWith("ws_");
        verify(workspaceMemberRepository).save(any());
        verify(documentInternalClient).createInitialNote(workspace.getId(), "user_1f9a74af");
    }

    @Test
    void create_validRequest_returnsResponse() {
        when(workspaceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkspaceResponse response = workspaceService.create("user_1f9a74af", new WorkspaceCreateRequest("팀 워크스페이스"));

        assertThat(response.name()).isEqualTo("팀 워크스페이스");
        assertThat(response.id()).startsWith("ws_");
        verify(documentInternalClient).createInitialNote(response.id(), "user_1f9a74af");
    }

    @Test
    void list_returnsOnlyOwnedWorkspaces() {
        when(workspaceMemberRepository.findAllWorkspacesByUserId("user_1f9a74af"))
                .thenReturn(List.of(new Workspace("ws_aaa11111", "워크스페이스 A")));

        var response = workspaceService.list("user_1f9a74af");

        assertThat(response.workspaces()).hasSize(1);
        assertThat(response.workspaces().get(0).name()).isEqualTo("워크스페이스 A");
    }

    @Test
    void rename_ownedWorkspace_updatesName() {
        Workspace workspace = new Workspace("ws_aaa11111", "이전 이름");
        when(workspaceMemberRepository.findOwnedWorkspaceIncludingDeleted(
                "ws_aaa11111", "user_1f9a74af", WorkspaceRole.OWNER)).thenReturn(Optional.of(workspace));

        WorkspaceResponse response = workspaceService.rename("user_1f9a74af", "ws_aaa11111", new WorkspaceRenameRequest("새 이름"));

        assertThat(response.name()).isEqualTo("새 이름");
    }

    @Test
    void rename_notOwnedWorkspace_throwsNotFound() {
        assertThatThrownBy(() -> workspaceService.rename("user_other", "ws_aaa11111", new WorkspaceRenameRequest("새 이름")))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void delete_ownedWorkspace_softDeletesWithoutChangingChildren() {
        Workspace workspace = new Workspace("ws_aaa11111", "워크스페이스 A");
        when(workspaceMemberRepository.findOwnedWorkspaceIncludingDeleted(
                "ws_aaa11111", "user_1f9a74af", WorkspaceRole.OWNER)).thenReturn(Optional.of(workspace));

        WorkspaceLifecycleResponse response = workspaceService.delete(
                "user_1f9a74af", "ws_aaa11111", "delete-key");

        assertThat(response.deleted()).isTrue();
        assertThat(workspace.getDeletedAt()).isNotNull();
        assertThat(workspace.getDeletedBy()).isEqualTo("user_1f9a74af");
        // soft delete는 document 서비스 호출 없이 access 안에서만 끝나야 한다.
        verifyNoInteractions(documentInternalClient);
        verify(workspaceRepository, never()).delete(any());
        verify(idempotencyService).execute(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void delete_notOwnedWorkspace_throwsNotFoundWithoutChangingChildren() {
        assertThatThrownBy(() -> workspaceService.delete(
                "user_other", "ws_aaa11111", "delete-key"))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verifyNoInteractions(documentInternalClient);
    }

    @Test
    void restore_deletedWorkspace_preservesExistingWorkspace() {
        Workspace workspace = new Workspace("ws_aaa11111", "워크스페이스 A");
        workspace.softDelete("user_1f9a74af", java.time.Instant.now());
        when(workspaceMemberRepository.findOwnedWorkspaceIncludingDeleted(
                "ws_aaa11111", "user_1f9a74af", WorkspaceRole.OWNER)).thenReturn(Optional.of(workspace));

        WorkspaceLifecycleResponse response = workspaceService.restore(
                "user_1f9a74af", "ws_aaa11111", "restore-key");

        assertThat(response.deleted()).isFalse();
        assertThat(workspace.getDeletedAt()).isNull();
        assertThat(workspace.getDeletedBy()).isNull();
    }

    @Test
    void trash_returnsOnlyDeletedOwnedWorkspaces() {
        Workspace deleted = new Workspace("ws_deleted", "삭제 workspace");
        deleted.softDelete("user_1f9a74af", java.time.Instant.now());
        when(workspaceMemberRepository.findDeletedOwnedWorkspaces(
                "user_1f9a74af", WorkspaceRole.OWNER))
                .thenReturn(List.of(deleted));

        WorkspaceTrashResponse response = workspaceService.trash("user_1f9a74af");

        assertThat(response.workspaces()).hasSize(1);
        assertThat(response.workspaces().get(0).id()).isEqualTo("ws_deleted");
        assertThat(response.workspaces().get(0).deletedBy()).isEqualTo("user_1f9a74af");
    }
}
