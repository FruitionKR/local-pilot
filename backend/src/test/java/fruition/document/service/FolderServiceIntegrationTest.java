package fruition.document.service;

import fruition.TestcontainersConfiguration;
import fruition.document.dto.DocumentPositionRequest;
import fruition.document.dto.DocumentPositionResponse;
import fruition.document.dto.BreadcrumbResponse;
import fruition.document.dto.DocumentUploadResponse;
import fruition.document.dto.FolderChildrenResponse;
import fruition.document.dto.HierarchySearchResponse;
import fruition.document.dto.FolderCreateRequest;
import fruition.document.dto.FolderLifecycleResponse;
import fruition.document.dto.MarkdownDocumentCreateRequest;
import fruition.document.dto.FolderPositionRequest;
import fruition.document.dto.FolderRenameRequest;
import fruition.document.dto.FolderResponse;
import fruition.document.exception.HierarchyCycleException;
import fruition.document.exception.HierarchyItemNotFoundException;
import fruition.document.exception.HierarchyVersionConflictException;
import fruition.document.exception.HierarchyWriteForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FolderServiceIntegrationTest {

    @Autowired FolderService folderService;
    @Autowired DocumentPlacementService documentPlacementService;
    @Autowired DocumentService documentService;
    @Autowired JdbcTemplate jdbcTemplate;

    private String userId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        userId = "user_" + suffix;
        workspaceId = "ws_" + suffix;
        jdbcTemplate.update(
                "INSERT INTO users(id, email, display_name, password_hash, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, now(), now())",
                userId, userId + "@example.com", "tester");
        jdbcTemplate.update(
                "INSERT INTO workspaces(id, name, created_at, updated_at) VALUES (?, ?, now(), now())",
                workspaceId, "workspace");
        jdbcTemplate.update(
                "INSERT INTO workspace_members(joined_at, role, user_id, workspace_id) "
                        + "VALUES (now(), 'OWNER', ?, ?)",
                userId, workspaceId);
    }

    @Test
    void create_allowsSameNameSiblingsAndAppendsOrder() {
        FolderResponse first = folderService.create(workspaceId, userId, "k1",
                new FolderCreateRequest("자료", null));
        FolderResponse second = folderService.create(workspaceId, userId, "k2",
                new FolderCreateRequest("자료", null));

        assertThat(first.sortOrder()).isEqualTo(0);
        assertThat(second.sortOrder()).isEqualTo(1);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM folders WHERE workspace_id = ? AND name = ?",
                Integer.class, workspaceId, "자료");
        assertThat(count).isEqualTo(2);
    }

    @Test
    void create_isIdempotentForSameKey() {
        FolderResponse first = folderService.create(workspaceId, userId, "same-key",
                new FolderCreateRequest("자료", null));
        FolderResponse replay = folderService.create(workspaceId, userId, "same-key",
                new FolderCreateRequest("자료", null));

        assertThat(replay.id()).isEqualTo(first.id());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM folders WHERE workspace_id = ?", Integer.class, workspaceId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void rename_conflictsOnStaleVersion() {
        FolderResponse folder = folderService.create(workspaceId, userId, "k1",
                new FolderCreateRequest("옛이름", null));

        assertThatThrownBy(() -> folderService.rename(workspaceId, userId, folder.id(), "k2",
                new FolderRenameRequest("새이름", 999L)))
                .isInstanceOf(HierarchyVersionConflictException.class);
    }

    @Test
    void move_rejectsCycleIntoOwnDescendant() {
        FolderResponse parent = folderService.create(workspaceId, userId, "k1",
                new FolderCreateRequest("부모", null));
        FolderResponse child = folderService.create(workspaceId, userId, "k2",
                new FolderCreateRequest("자식", parent.id()));

        assertThatThrownBy(() -> folderService.move(workspaceId, userId, parent.id(), "k3",
                new FolderPositionRequest(child.id(), null, parent.currentVersion())))
                .isInstanceOf(HierarchyCycleException.class);
    }

    @Test
    void children_returnFoldersAndDocumentsInMixedOrder() {
        FolderResponse parent = folderService.create(workspaceId, userId, "k1",
                new FolderCreateRequest("부모", null));
        FolderResponse childFolder = folderService.create(workspaceId, userId, "k2",
                new FolderCreateRequest("하위폴더", parent.id()));
        insertDocumentInFolder("doc_mid", "메모.md", "EDITABLE", parent.id(), 1);

        FolderChildrenResponse children = folderService.children(workspaceId, userId, parent.id());

        List<FolderChildrenResponse.Item> items = children.items();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).type()).isEqualTo("folder");
        assertThat(items.get(0).id()).isEqualTo(childFolder.id().toString());
        assertThat(items.get(1).type()).isEqualTo("document");
        assertThat(items.get(1).id()).isEqualTo("doc_mid");
    }

    @Test
    void document_movesFromRootIntoFolder() {
        FolderResponse folder = folderService.create(workspaceId, userId, "k1",
                new FolderCreateRequest("자료", null));
        insertDocumentInFolder("doc_move", "메모.md", "EDITABLE", null, 0);

        DocumentPositionResponse moved = documentPlacementService.move(workspaceId, userId, "doc_move", "mk1",
                new DocumentPositionRequest(folder.id(), null, 1L));

        assertThat(moved.folderId()).isEqualTo(folder.id());
        assertThat(moved.currentVersion()).isEqualTo(2);
        UUID stored = jdbcTemplate.queryForObject(
                "SELECT folder_id FROM documents WHERE id = ?", UUID.class, "doc_move");
        assertThat(stored).isEqualTo(folder.id());
    }

    @Test
    void document_moveConflictsOnStaleVersion() {
        insertDocumentInFolder("doc_stale", "메모.md", "EDITABLE", null, 0);

        assertThatThrownBy(() -> documentPlacementService.move(workspaceId, userId, "doc_stale", "mk1",
                new DocumentPositionRequest(null, null, 999L)))
                .isInstanceOf(HierarchyVersionConflictException.class);
    }

    @Test
    void document_moveToMissingFolderIsNotFound() {
        insertDocumentInFolder("doc_orphan", "메모.md", "EDITABLE", null, 0);

        assertThatThrownBy(() -> documentPlacementService.move(workspaceId, userId, "doc_orphan", "mk1",
                new DocumentPositionRequest(UUID.randomUUID(), null, 1L)))
                .isInstanceOf(HierarchyItemNotFoundException.class);
    }

    @Test
    void folder_movesToExplicitPosition() {
        FolderResponse a = folderService.create(workspaceId, userId, "ka", new FolderCreateRequest("A", null));
        FolderResponse b = folderService.create(workspaceId, userId, "kb", new FolderCreateRequest("B", null));
        FolderResponse c = folderService.create(workspaceId, userId, "kc", new FolderCreateRequest("C", null));

        folderService.move(workspaceId, userId, c.id(), "mk",
                new FolderPositionRequest(null, 0, c.currentVersion()));

        List<FolderChildrenResponse.Item> items = folderService.children(workspaceId, userId, null).items();
        assertThat(items).extracting(FolderChildrenResponse.Item::id)
                .containsExactly(c.id().toString(), a.id().toString(), b.id().toString());
        assertThat(items).extracting(FolderChildrenResponse.Item::sortOrder)
                .containsExactly(0L, 1L, 2L);
    }

    @Test
    void document_reordersBetweenFolders() {
        FolderResponse a = folderService.create(workspaceId, userId, "ka", new FolderCreateRequest("A", null));
        FolderResponse b = folderService.create(workspaceId, userId, "kb", new FolderCreateRequest("B", null));
        insertDocumentInFolder("doc_reorder", "메모.md", "EDITABLE", null, 2);

        documentPlacementService.move(workspaceId, userId, "doc_reorder", "mk",
                new DocumentPositionRequest(null, 1, 1L));

        List<FolderChildrenResponse.Item> items = folderService.children(workspaceId, userId, null).items();
        assertThat(items).extracting(FolderChildrenResponse.Item::id)
                .containsExactly(a.id().toString(), "doc_reorder", b.id().toString());
        assertThat(items).extracting(FolderChildrenResponse.Item::sortOrder)
                .containsExactly(0L, 1L, 2L);
    }

    @Test
    void folder_deleteSoftDeletesSubtreeWithSharedOperation() {
        FolderResponse parent = folderService.create(workspaceId, userId, "kp", new FolderCreateRequest("부모", null));
        FolderResponse child = folderService.create(workspaceId, userId, "kc", new FolderCreateRequest("자식", parent.id()));
        insertDocumentInFolder("doc_p", "p.md", "EDITABLE", parent.id(), 5);
        insertDocumentInFolder("doc_c", "c.md", "EDITABLE", child.id(), 6);

        FolderLifecycleResponse res = folderService.delete(workspaceId, userId, parent.id(), "dk",
                parent.currentVersion());

        assertThat(res.deleted()).isTrue();
        assertThat(folderService.children(workspaceId, userId, null).items()).isEmpty();
        UUID op = res.deleteOperationId();
        Integer deletedFolders = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM folders WHERE delete_operation_id = ? AND deleted_at IS NOT NULL",
                Integer.class, op);
        Integer deletedDocuments = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE delete_operation_id = ? AND deleted_at IS NOT NULL",
                Integer.class, op);
        assertThat(deletedFolders).isEqualTo(2);
        assertThat(deletedDocuments).isEqualTo(2);
    }

    @Test
    void folder_restoreRestoresSubtreeUnderOriginalParent() {
        FolderResponse parent = folderService.create(workspaceId, userId, "kp", new FolderCreateRequest("부모", null));
        folderService.create(workspaceId, userId, "kc", new FolderCreateRequest("자식", parent.id()));
        insertDocumentInFolder("doc_p2", "p.md", "EDITABLE", parent.id(), 5);
        FolderLifecycleResponse deleted = folderService.delete(workspaceId, userId, parent.id(), "dk",
                parent.currentVersion());

        FolderLifecycleResponse restored = folderService.restore(workspaceId, userId, parent.id(), "rk",
                deleted.currentVersion());

        assertThat(restored.deleted()).isFalse();
        assertThat(folderService.children(workspaceId, userId, null).items())
                .extracting(FolderChildrenResponse.Item::id).contains(parent.id().toString());
        assertThat(folderService.children(workspaceId, userId, parent.id()).items()).hasSize(2);
    }

    @Test
    void folder_deleteNonEmptyByNonOwnerForbidden() {
        String memberId = insertMember("MEMBER");
        FolderResponse a = folderService.create(workspaceId, memberId, "ka", new FolderCreateRequest("A", null));
        folderService.create(workspaceId, memberId, "kb", new FolderCreateRequest("하위", a.id()));

        assertThatThrownBy(() -> folderService.delete(workspaceId, memberId, a.id(), "dk", a.currentVersion()))
                .isInstanceOf(HierarchyWriteForbiddenException.class);
    }

    @Test
    void folder_deleteEmptyByMemberAllowed() {
        String memberId = insertMember("MEMBER");
        FolderResponse a = folderService.create(workspaceId, memberId, "ka", new FolderCreateRequest("A", null));

        FolderLifecycleResponse res = folderService.delete(workspaceId, memberId, a.id(), "dk", a.currentVersion());

        assertThat(res.deleted()).isTrue();
    }

    @Test
    void createMarkdown_placesDocumentInSelectedFolder() {
        FolderResponse folder = folderService.create(workspaceId, userId, "kf", new FolderCreateRequest("자료", null));

        DocumentUploadResponse created = documentService.createMarkdown(workspaceId, userId, "cmk",
                new MarkdownDocumentCreateRequest("메모", "# 본문", folder.id()));

        UUID stored = jdbcTemplate.queryForObject(
                "SELECT folder_id FROM documents WHERE id = ?", UUID.class, created.id());
        assertThat(stored).isEqualTo(folder.id());
        assertThat(folderService.children(workspaceId, userId, folder.id()).items())
                .extracting(FolderChildrenResponse.Item::id).contains(created.id());
    }

    @Test
    void createMarkdown_rejectsMissingFolder() {
        assertThatThrownBy(() -> documentService.createMarkdown(workspaceId, userId, "cmk",
                new MarkdownDocumentCreateRequest("메모", "# 본문", UUID.randomUUID())))
                .isInstanceOf(HierarchyItemNotFoundException.class);
    }

    @Test
    void breadcrumb_returnsPathFromRootToDocument() {
        FolderResponse a = folderService.create(workspaceId, userId, "ka", new FolderCreateRequest("A", null));
        FolderResponse b = folderService.create(workspaceId, userId, "kb", new FolderCreateRequest("B", a.id()));
        DocumentUploadResponse doc = documentService.createMarkdown(workspaceId, userId, "cmk",
                new MarkdownDocumentCreateRequest("메모", "# 본문", b.id()));

        BreadcrumbResponse crumb = folderService.breadcrumb(workspaceId, userId, null, doc.id());

        assertThat(crumb.path()).extracting(BreadcrumbResponse.Node::name)
                .containsExactly("A", "B", "메모");
        assertThat(crumb.path()).extracting(BreadcrumbResponse.Node::type)
                .containsExactly("folder", "folder", "document");
    }

    @Test
    void breadcrumb_returnsPathToFolder() {
        FolderResponse a = folderService.create(workspaceId, userId, "ka", new FolderCreateRequest("A", null));
        FolderResponse b = folderService.create(workspaceId, userId, "kb", new FolderCreateRequest("B", a.id()));

        BreadcrumbResponse crumb = folderService.breadcrumb(workspaceId, userId, b.id(), null);

        assertThat(crumb.path()).extracting(BreadcrumbResponse.Node::name).containsExactly("A", "B");
    }

    @Test
    void search_findsFoldersAndDocumentsWithBreadcrumb() {
        FolderResponse a = folderService.create(workspaceId, userId, "ka", new FolderCreateRequest("보고서 폴더", null));
        documentService.createMarkdown(workspaceId, userId, "cmk",
                new MarkdownDocumentCreateRequest("보고서 메모", "# 본문", a.id()));

        HierarchySearchResponse result = folderService.search(workspaceId, userId, "보고서");

        assertThat(result.results()).extracting(HierarchySearchResponse.Match::name)
                .contains("보고서 폴더", "보고서 메모");
        HierarchySearchResponse.Match docMatch = result.results().stream()
                .filter(m -> m.type().equals("document")).findFirst().orElseThrow();
        assertThat(docMatch.breadcrumb()).extracting(BreadcrumbResponse.Node::name).containsExactly("보고서 폴더");
    }

    private String insertMember(String role) {
        String memberId = "member_" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users(id, email, display_name, password_hash, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, now(), now())",
                memberId, memberId + "@example.com", "member");
        jdbcTemplate.update(
                "INSERT INTO workspace_members(joined_at, role, user_id, workspace_id) VALUES (now(), ?, ?, ?)",
                role, memberId, workspaceId);
        return memberId;
    }

    private void insertDocumentInFolder(String documentId, String filename, String role, UUID folderId, long sortOrder) {
        jdbcTemplate.update(
                """
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order, folder_id
                ) VALUES (?, 1, ?, ?, ?, ?, ?, ?, 'completed', now(), now(), ?, ?, ?, 1, ?, ?, ?)
                """,
                documentId,
                documentId + "-hash",
                filename,
                filename.substring(0, filename.lastIndexOf('.')),
                filename.toLowerCase(),
                role.equals("EDITABLE") ? "text/markdown" : "application/pdf",
                role.equals("EDITABLE") ? null : "sources/" + documentId,
                userId,
                workspaceId,
                documentId + "-hash",
                role,
                sortOrder,
                folderId);
    }
}
