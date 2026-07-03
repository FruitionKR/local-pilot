package fruition.workspace.service;

import fruition.chat.service.ChatSessionService;
import fruition.document.service.DocumentService;
import fruition.workspace.domain.Workspace;
import fruition.workspace.dto.WorkspaceCreateRequest;
import fruition.workspace.dto.WorkspaceRenameRequest;
import fruition.workspace.dto.WorkspaceResponse;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock WorkspaceRepository workspaceRepository;
    @Mock DocumentService documentService;
    @Mock ChatSessionService chatSessionService;

    WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(workspaceRepository, documentService, chatSessionService);
    }

    @Test
    void createDefault_buildsNameFromDisplayName() {
        when(workspaceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Workspace workspace = workspaceService.createDefault("user_1f9a74af", "tes");

        assertThat(workspace.getName()).isEqualTo("tes의 워크스페이스");
        assertThat(workspace.getUserId()).isEqualTo("user_1f9a74af");
        assertThat(workspace.getId()).startsWith("ws_");
    }

    @Test
    void create_validRequest_returnsResponse() {
        when(workspaceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkspaceResponse response = workspaceService.create("user_1f9a74af", new WorkspaceCreateRequest("팀 워크스페이스"));

        assertThat(response.name()).isEqualTo("팀 워크스페이스");
        assertThat(response.id()).startsWith("ws_");
    }

    @Test
    void list_returnsOnlyOwnedWorkspaces() {
        when(workspaceRepository.findAllByUserIdOrderByCreatedAtDesc("user_1f9a74af"))
                .thenReturn(List.of(new Workspace("ws_aaa11111", "user_1f9a74af", "워크스페이스 A")));

        var response = workspaceService.list("user_1f9a74af");

        assertThat(response.workspaces()).hasSize(1);
        assertThat(response.workspaces().get(0).name()).isEqualTo("워크스페이스 A");
    }

    @Test
    void rename_ownedWorkspace_updatesName() {
        Workspace workspace = new Workspace("ws_aaa11111", "user_1f9a74af", "이전 이름");
        when(workspaceRepository.findByIdAndUserId("ws_aaa11111", "user_1f9a74af")).thenReturn(Optional.of(workspace));

        WorkspaceResponse response = workspaceService.rename("user_1f9a74af", "ws_aaa11111", new WorkspaceRenameRequest("새 이름"));

        assertThat(response.name()).isEqualTo("새 이름");
    }

    @Test
    void rename_notOwnedWorkspace_throwsNotFound() {
        when(workspaceRepository.findByIdAndUserId("ws_aaa11111", "user_other")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workspaceService.rename("user_other", "ws_aaa11111", new WorkspaceRenameRequest("새 이름")))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void delete_ownedWorkspace_cascadesToDocumentsAndChatSessionsThenRemovesWorkspace() {
        Workspace workspace = new Workspace("ws_aaa11111", "user_1f9a74af", "워크스페이스 A");
        when(workspaceRepository.findByIdAndUserId("ws_aaa11111", "user_1f9a74af")).thenReturn(Optional.of(workspace));

        workspaceService.delete("user_1f9a74af", "ws_aaa11111");

        verify(documentService).deleteAllByWorkspaceId("ws_aaa11111");
        verify(chatSessionService).deleteAllByWorkspaceId("ws_aaa11111");
        verify(workspaceRepository).delete(workspace);
    }

    @Test
    void delete_notOwnedWorkspace_throwsNotFoundWithoutCascading() {
        when(workspaceRepository.findByIdAndUserId("ws_aaa11111", "user_other")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workspaceService.delete("user_other", "ws_aaa11111"))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verify(documentService, never()).deleteAllByWorkspaceId(any());
        verify(chatSessionService, never()).deleteAllByWorkspaceId(any());
    }
}
