package fruition.document.service;

import fruition.TestcontainersConfiguration;
import fruition.document.dto.FolderChildrenResponse;
import fruition.document.dto.FolderCreateRequest;
import fruition.document.dto.FolderPositionRequest;
import fruition.document.dto.FolderRenameRequest;
import fruition.document.dto.FolderResponse;
import fruition.document.exception.HierarchyCycleException;
import fruition.document.exception.HierarchyVersionConflictException;
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
                new FolderPositionRequest(child.id(), parent.currentVersion())))
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
