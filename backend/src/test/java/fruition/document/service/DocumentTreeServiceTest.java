package fruition.document.service;

import fruition.document.domain.Document;
import fruition.document.domain.SourceFolder;
import fruition.document.dto.DocumentPlacementRequest;
import fruition.document.dto.DocumentTreeResponse;
import fruition.document.dto.FolderCreateRequest;
import fruition.document.dto.FolderResponse;
import fruition.document.dto.FolderUpdateRequest;
import fruition.document.exception.DocumentVersionConflictException;
import fruition.document.exception.FolderVersionConflictException;
import fruition.document.exception.InvalidFolderRequestException;
import fruition.document.repository.DocumentRepository;
import fruition.document.repository.SourceFolderRepository;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentTreeServiceTest {

    private static final String WORKSPACE_ID = "ws_1";
    private static final String USER_ID = "user_1";

    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock SourceFolderRepository folderRepository;
    @Mock DocumentRepository documentRepository;

    DocumentTreeService service;

    @BeforeEach
    void setUp() {
        service = new DocumentTreeService(workspaceMemberRepository, folderRepository, documentRepository);
        when(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(WORKSPACE_ID, USER_ID)).thenReturn(true);
    }

    @Test
    @DisplayName("폴더 생성 시 sort_order를 생략하면 형제 마지막(max+1)으로 배치한다")
    void createFolder_appendsToParentEnd() {
        when(folderRepository.findMaxSortOrder(WORKSPACE_ID, null)).thenReturn(4L);

        FolderResponse response = service.createFolder(
                WORKSPACE_ID, USER_ID, new FolderCreateRequest("설계", null, null));

        assertThat(response.sortOrder()).isEqualTo(5);
        assertThat(response.name()).isEqualTo("설계");
        verify(folderRepository).save(any(SourceFolder.class));
    }

    @Test
    @DisplayName("폴더를 자기 하위 폴더로 이동하면 순환으로 거절한다")
    void updateFolder_rejectsCycle() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        SourceFolder parent = new SourceFolder(parentId, WORKSPACE_ID, null, "부모", 0);
        SourceFolder child = new SourceFolder(childId, WORKSPACE_ID, parentId, "자식", 0);
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(parentId, WORKSPACE_ID))
                .thenReturn(Optional.of(parent));
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(childId, WORKSPACE_ID))
                .thenReturn(Optional.of(child));

        assertThatThrownBy(() -> service.updateFolder(WORKSPACE_ID, USER_ID, parentId,
                new FolderUpdateRequest("부모", childId, null, 1L)))
                .isInstanceOf(InvalidFolderRequestException.class);
        verify(folderRepository, never()).updateIfVersionMatches(any(), any(), anyLong(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("폴더 수정 시 base_version 불일치는 409 충돌")
    void updateFolder_conflictOnStaleVersion() {
        UUID folderId = UUID.randomUUID();
        SourceFolder folder = new SourceFolder(folderId, WORKSPACE_ID, null, "폴더", 0); // version=1
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(folderId, WORKSPACE_ID))
                .thenReturn(Optional.of(folder));

        assertThatThrownBy(() -> service.updateFolder(WORKSPACE_ID, USER_ID, folderId,
                new FolderUpdateRequest("새이름", null, null, 5L)))
                .isInstanceOf(FolderVersionConflictException.class);
    }

    @Test
    @DisplayName("폴더 삭제는 하위 폴더와 그 안 문서를 같은 operation으로 cascade 소프트 삭제한다")
    void deleteFolder_cascadesSubtree() {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        SourceFolder root = new SourceFolder(rootId, WORKSPACE_ID, null, "루트", 0);
        SourceFolder child = new SourceFolder(childId, WORKSPACE_ID, rootId, "자식", 0);
        when(folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(rootId, WORKSPACE_ID))
                .thenReturn(Optional.of(root));
        when(folderRepository.findByWorkspaceIdAndParentFolderIdAndDeletedAtIsNull(WORKSPACE_ID, rootId))
                .thenReturn(List.of(child));
        when(folderRepository.findByWorkspaceIdAndParentFolderIdAndDeletedAtIsNull(WORKSPACE_ID, childId))
                .thenReturn(List.of());

        service.deleteFolder(WORKSPACE_ID, USER_ID, rootId, 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> folderIds = ArgumentCaptor.forClass(List.class);
        verify(folderRepository).softDeleteByIds(folderIds.capture(), eq(USER_ID), any(), any());
        assertThat(folderIds.getValue()).containsExactlyInAnyOrder(rootId, childId);
        verify(documentRepository).softDeleteBySourceFolderIds(
                argThatContains(rootId, childId), eq(USER_ID), any(), any());
    }

    @Test
    @DisplayName("문서 배치 시 base_version 불일치는 409 충돌")
    void placeDocument_conflictOnStaleVersion() {
        Document document = new Document("doc_1", WORKSPACE_ID, USER_ID, "note.md", "text/markdown", 10,
                "sources/documents/doc_1/original", "hash"); // version=1
        when(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull("doc_1", WORKSPACE_ID))
                .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.placeDocument(WORKSPACE_ID, USER_ID, "doc_1",
                new DocumentPlacementRequest(null, 3L, 9L)))
                .isInstanceOf(DocumentVersionConflictException.class);
        verify(documentRepository, never()).placeIfVersionMatches(any(), any(), anyLong(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("트리 조회는 활성 폴더와 문서를 함께 반환한다")
    void getTree_returnsFoldersAndDocuments() {
        SourceFolder folder = new SourceFolder(UUID.randomUUID(), WORKSPACE_ID, null, "폴더", 0);
        Document document = new Document("doc_1", WORKSPACE_ID, USER_ID, "note.md", "text/markdown", 10,
                "sources/documents/doc_1/original", "hash");
        when(folderRepository.findAllByWorkspaceIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(WORKSPACE_ID))
                .thenReturn(List.of(folder));
        when(documentRepository.findVisibleByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(document));

        DocumentTreeResponse response = service.getTree(WORKSPACE_ID, USER_ID);

        assertThat(response.folders()).hasSize(1);
        assertThat(response.documents()).hasSize(1);
        assertThat(response.documents().get(0).fileType()).isEqualTo("md");
        assertThat(response.documents().get(0).editable()).isTrue();
    }

    private static List<UUID> argThatContains(UUID... expected) {
        return org.mockito.ArgumentMatchers.argThat(list ->
                list != null && list.containsAll(List.of(expected)));
    }
}
