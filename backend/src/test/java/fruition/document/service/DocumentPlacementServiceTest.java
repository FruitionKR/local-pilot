package fruition.document.service;

import fruition.document.domain.Document;
import fruition.document.dto.DocumentPositionRequest;
import fruition.document.dto.DocumentPositionResponse;
import fruition.document.exception.HierarchyItemNotFoundException;
import fruition.document.exception.HierarchyVersionConflictException;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentPlacementServiceTest {

    private static final String WORKSPACE_ID = "ws_1";
    private static final String USER_ID = "user_1";
    private static final String DOCUMENT_ID = "doc_1";

    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock DocumentRepository documentRepository;
    @Mock FolderRepository folderRepository;
    @Mock IdempotencyService idempotencyService;

    private DocumentPlacementService service;

    @BeforeEach
    void setUp() {
        service = new DocumentPlacementService(
                workspaceMemberRepository, documentRepository, folderRepository, idempotencyService);
    }

    private Document document() {
        return new Document(DOCUMENT_ID, WORKSPACE_ID, USER_ID, "메모.md", "text/markdown", 10, null, null, "direct");
    }

    private void memberOk() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE_ID, USER_ID)).thenReturn(true);
    }

    private void noReplay() {
        when(idempotencyService.replay(any(), any(), any(), any(), eq(DocumentPositionResponse.class)))
                .thenReturn(Optional.empty());
    }

    @Test
    void move_rejectsNonMember() {
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE_ID, "intruder")).thenReturn(false);
        assertThatThrownBy(() -> service.move(WORKSPACE_ID, "intruder", DOCUMENT_ID, "k1",
                new DocumentPositionRequest(null, 1L)))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void move_rejectsMissingDocument() {
        memberOk();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.move(WORKSPACE_ID, USER_ID, DOCUMENT_ID, "k1",
                new DocumentPositionRequest(null, 1L)))
                .isInstanceOf(HierarchyItemNotFoundException.class);
    }

    @Test
    void move_rejectsMissingTargetFolder() {
        memberOk();
        UUID targetFolderId = UUID.randomUUID();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(document()));
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(targetFolderId, WORKSPACE_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.move(WORKSPACE_ID, USER_ID, DOCUMENT_ID, "k1",
                new DocumentPositionRequest(targetFolderId, 1L)))
                .isInstanceOf(HierarchyItemNotFoundException.class);
    }

    @Test
    void move_conflictsOnStaleVersion() {
        memberOk();
        noReplay();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(document()));
        when(folderRepository.findMaxSortOrder(WORKSPACE_ID, null)).thenReturn(-1L);
        when(documentRepository.findMaxSortOrderInFolder(WORKSPACE_ID, null)).thenReturn(-1L);
        when(documentRepository.moveIfVersionMatches(eq(DOCUMENT_ID), eq(WORKSPACE_ID), eq(9L), eq(null),
                anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.move(WORKSPACE_ID, USER_ID, DOCUMENT_ID, "k1",
                new DocumentPositionRequest(null, 9L)))
                .isInstanceOf(HierarchyVersionConflictException.class);
    }

    @Test
    void move_appendsAtEndOfTargetFolderMixedOrder() {
        memberOk();
        noReplay();
        UUID targetFolderId = UUID.randomUUID();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(document()));
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(targetFolderId, WORKSPACE_ID))
                .thenReturn(Optional.of(new fruition.document.domain.Folder(
                        targetFolderId, WORKSPACE_ID, null, "대상", 0)));
        when(folderRepository.findMaxSortOrder(WORKSPACE_ID, targetFolderId)).thenReturn(2L);
        when(documentRepository.findMaxSortOrderInFolder(WORKSPACE_ID, targetFolderId)).thenReturn(5L);
        when(documentRepository.moveIfVersionMatches(eq(DOCUMENT_ID), eq(WORKSPACE_ID), eq(1L),
                eq(targetFolderId), eq(6L), any())).thenReturn(1);

        service.move(WORKSPACE_ID, USER_ID, DOCUMENT_ID, "k1",
                new DocumentPositionRequest(targetFolderId, 1L));

        verify(documentRepository).moveIfVersionMatches(eq(DOCUMENT_ID), eq(WORKSPACE_ID), eq(1L),
                eq(targetFolderId), eq(6L), any());
        verify(idempotencyService).save(eq(USER_ID), any(), eq("k1"), any(), eq(200), eq(DOCUMENT_ID), any());
    }

    @Test
    void move_returnsReplayWithoutUpdating() {
        memberOk();
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(DOCUMENT_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(document()));
        DocumentPositionResponse stored = new DocumentPositionResponse(DOCUMENT_ID, null, 3, 2);
        when(idempotencyService.replay(any(), any(), any(), any(), eq(DocumentPositionResponse.class)))
                .thenReturn(Optional.of(stored));

        service.move(WORKSPACE_ID, USER_ID, DOCUMENT_ID, "k1", new DocumentPositionRequest(null, 1L));

        verify(documentRepository, never()).moveIfVersionMatches(any(), any(), anyLong(), any(), anyLong(), any());
    }
}
