package fruition.document.service;

import fruition.document.domain.Folder;
import fruition.document.dto.FolderCreateRequest;
import fruition.document.dto.FolderPositionRequest;
import fruition.document.dto.FolderRenameRequest;
import fruition.document.dto.FolderResponse;
import fruition.document.exception.HierarchyCycleException;
import fruition.document.exception.HierarchyVersionConflictException;
import fruition.document.exception.InvalidHierarchyRequestException;
import fruition.document.repository.DocumentRepository;
import fruition.document.repository.FolderRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FolderServiceTest {

    private static final String WORKSPACE_ID = "ws_1";
    private static final String USER_ID = "user_1";

    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock FolderRepository folderRepository;
    @Mock DocumentRepository documentRepository;
    @Mock IdempotencyService idempotencyService;
    @Mock SiblingReorderer siblingReorderer;

    private FolderService service;

    @BeforeEach
    void setUp() {
        service = new FolderService(workspaceMemberRepository, folderRepository, documentRepository,
                idempotencyService, siblingReorderer);
    }

    private void memberOk() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE_ID, USER_ID)).thenReturn(true);
    }

    private void noReplay() {
        when(idempotencyService.replay(any(), any(), any(), any(), eq(FolderResponse.class)))
                .thenReturn(Optional.empty());
    }

    @Test
    void create_appendsAtEndOfParentMixedOrder() {
        memberOk();
        noReplay();
        when(folderRepository.findMaxSortOrder(WORKSPACE_ID, null)).thenReturn(1L);
        when(documentRepository.findMaxSortOrderInFolder(WORKSPACE_ID, null)).thenReturn(3L);

        FolderResponse response = service.create(WORKSPACE_ID, USER_ID, "key-1",
                new FolderCreateRequest("자료", null));

        assertThat(response.name()).isEqualTo("자료");
        assertThat(response.sortOrder()).isEqualTo(4);
        assertThat(response.currentVersion()).isEqualTo(1);
        verify(folderRepository).save(any(Folder.class));
        verify(idempotencyService).save(eq(USER_ID), any(), eq("key-1"), any(), eq(201), any(), any());
    }

    @Test
    void create_trimsAndRejectsBlankName() {
        memberOk();
        assertThatThrownBy(() -> service.create(WORKSPACE_ID, USER_ID, "key-1",
                new FolderCreateRequest("   ", null)))
                .isInstanceOf(InvalidHierarchyRequestException.class);
        verify(folderRepository, never()).save(any());
    }

    @Test
    void create_rejectsNonMember() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE_ID, "intruder")).thenReturn(false);
        assertThatThrownBy(() -> service.create(WORKSPACE_ID, "intruder", "key-1",
                new FolderCreateRequest("자료", null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void create_returnsReplayWithoutSaving() {
        memberOk();
        UUID id = UUID.randomUUID();
        FolderResponse stored = new FolderResponse(id, null, "자료", 0, 1, null, null);
        when(idempotencyService.replay(any(), any(), any(), any(), eq(FolderResponse.class)))
                .thenReturn(Optional.of(stored));

        FolderResponse response = service.create(WORKSPACE_ID, USER_ID, "key-1",
                new FolderCreateRequest("자료", null));

        assertThat(response).isEqualTo(stored);
        verify(folderRepository, never()).save(any());
    }

    @Test
    void rename_conflictsOnStaleVersion() {
        memberOk();
        noReplay();
        UUID id = UUID.randomUUID();
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(id, WORKSPACE_ID))
                .thenReturn(Optional.of(new Folder(id, WORKSPACE_ID, null, "옛이름", 0)));
        when(folderRepository.renameIfVersionMatches(eq(id), eq(WORKSPACE_ID), eq(1L), eq("새이름"), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.rename(WORKSPACE_ID, USER_ID, id, "key-1",
                new FolderRenameRequest("새이름", 1L)))
                .isInstanceOf(HierarchyVersionConflictException.class);
    }

    @Test
    void move_rejectsCycleBeforeUpdate() {
        memberOk();
        UUID movingId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(movingId, WORKSPACE_ID))
                .thenReturn(Optional.of(new Folder(movingId, WORKSPACE_ID, null, "이동", 0)));
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(targetId, WORKSPACE_ID))
                .thenReturn(Optional.of(new Folder(targetId, WORKSPACE_ID, movingId, "대상", 0)));
        when(folderRepository.countAncestorMatches(targetId, movingId)).thenReturn(1L);

        assertThatThrownBy(() -> service.move(WORKSPACE_ID, USER_ID, movingId, "key-1",
                new FolderPositionRequest(targetId, null, 1L)))
                .isInstanceOf(HierarchyCycleException.class);
        verify(folderRepository, never()).moveIfVersionMatches(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void move_conflictsOnStaleVersion() {
        memberOk();
        noReplay();
        UUID movingId = UUID.randomUUID();
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(movingId, WORKSPACE_ID))
                .thenReturn(Optional.of(new Folder(movingId, WORKSPACE_ID, null, "이동", 0)));
        when(siblingReorderer.placeFolder(WORKSPACE_ID, null, movingId, null)).thenReturn(0L);
        when(folderRepository.moveIfVersionMatches(eq(movingId), eq(WORKSPACE_ID), eq(2L), eq(null),
                org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.move(WORKSPACE_ID, USER_ID, movingId, "key-1",
                new FolderPositionRequest(null, null, 2L)))
                .isInstanceOf(HierarchyVersionConflictException.class);
    }
}
