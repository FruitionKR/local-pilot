package fruition.document.repository;

import fruition.TestcontainersConfiguration;
import fruition.document.domain.DocumentRole;
import fruition.document.dto.DocumentDuplicateResponse;
import fruition.document.dto.DocumentExportResult;
import fruition.document.dto.DocumentLifecycleRequest;
import fruition.document.service.DocumentExportService;
import fruition.document.service.DocumentService;
import fruition.workspace.repository.WorkspaceMemberRepository;
import fruition.workspace.service.WorkspaceService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DocumentEditingSchemaIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PostgreSQLContainer<?> postgresContainer;

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    DocumentService documentService;

    @Autowired
    DocumentExportService documentExportService;

    @Autowired
    WorkspaceService workspaceService;

    @Autowired
    WorkspaceMemberRepository workspaceMemberRepository;

    @Test
    void migration_createsDocumentEditingFoundation() {
        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'documents'
                """,
                String.class
        );

        assertThat(columns).contains(
                "display_name",
                "normalized_filename",
                "source_document_id",
                "current_content_hash",
                "current_version",
                "document_role",
                "folder_id",
                "sort_order",
                "updated_at",
                "deleted_at",
                "deleted_by",
                "delete_operation_id"
        );
        assertThat(columns).doesNotContain("parent_document_id", "source_folder_id");

        for (String table : List.of("document_edit_states", "folders", "idempotency_records")) {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL",
                    Boolean.class,
                    "public." + table
            );
            assertThat(exists).as(table).isTrue();
        }
    }

    @Test
    void migration_createsWorkspaceSoftDeleteColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'workspaces'
                """,
                String.class
        );

        assertThat(columns).contains("deleted_at", "deleted_by");
        assertThat(columns).doesNotContain("current_version");
    }

    @Test
    void documents_allowSameContentAndFolderPlacement() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        insertUserAndWorkspace(userId, workspaceId);

        insertDocument("doc_parent_" + suffix, workspaceId, userId, "parent.md", "same-hash", "EDITABLE");
        insertDocument("doc_same_" + suffix, workspaceId, userId, "parent.md", "same-hash", "EDITABLE");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE workspace_id = ? AND content_hash = ?",
                Integer.class,
                workspaceId,
                "same-hash"
        );
        assertThat(count).isEqualTo(2);

        UUID folderId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO folders(
                    id, workspace_id, parent_folder_id, name, sort_order, current_version, created_at, updated_at
                ) VALUES (?, ?, NULL, ?, 0, 1, now(), now())
                """,
                folderId,
                workspaceId,
                "폴더"
        );
        // 통일 모델: 역할과 무관하게 folder_id로 폴더에 배치할 수 있다.
        jdbcTemplate.update(
                "UPDATE documents SET folder_id = ? WHERE id = ?",
                folderId,
                "doc_parent_" + suffix
        );
        insertDocument("doc_original_" + suffix, workspaceId, userId, "original.pdf", "pdf-hash", "ORIGINAL");
        jdbcTemplate.update(
                "UPDATE documents SET folder_id = ? WHERE id = ?",
                folderId,
                "doc_original_" + suffix
        );
        Integer placed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE folder_id = ?",
                Integer.class,
                folderId
        );
        assertThat(placed).isEqualTo(2);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE documents SET source_document_id = id WHERE id = ?",
                "doc_original_" + suffix
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicate_sameIdempotencyKeyConcurrently_createsOneDocument() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String sourceId = "doc_source_" + suffix;
        insertUserAndWorkspace(userId, workspaceId);
        jdbcTemplate.update(
                """
                INSERT INTO workspace_members(joined_at, role, user_id, workspace_id)
                VALUES (now(), 'OWNER', ?, ?)
                """,
                userId,
                workspaceId
        );
        insertDocument(sourceId, workspaceId, userId, "보고서.md", "source-hash", "EDITABLE");
        jdbcTemplate.update(
                """
                INSERT INTO document_edit_states(document_id, markdown, content_hash, created_at, updated_at)
                VALUES (?, '# 최신 본문', ?, now(), now())
                """,
                sourceId,
                "a".repeat(64)
        );

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<DocumentDuplicateResponse> first = executor.submit(() -> {
                start.await();
                return documentService.duplicate(
                        workspaceId, userId, sourceId, "concurrent-key");
            });
            Future<DocumentDuplicateResponse> second = executor.submit(() -> {
                start.await();
                return documentService.duplicate(
                        workspaceId, userId, sourceId, "concurrent-key");
            });
            start.countDown();

            assertThat(first.get().id()).isEqualTo(second.get().id());
        }

        Integer duplicateCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE workspace_id = ? AND origin = 'duplicate'",
                Integer.class,
                workspaceId
        );
        Integer idempotencyCount = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM idempotency_records
                WHERE user_id = ? AND idempotency_key = 'concurrent-key'
                """,
                Integer.class,
                userId
        );
        assertThat(duplicateCount).isEqualTo(1);
        assertThat(idempotencyCount).isEqualTo(1);
    }

    @Test
    void documentSoftDeleteAndRestore_preservesOriginalAndEditingState() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String documentId = "doc_" + suffix;
        insertUserAndWorkspace(userId, workspaceId);
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "문서.md", "original-hash", "EDITABLE");
        jdbcTemplate.update(
                """
                INSERT INTO document_edit_states(document_id, markdown, content_hash, created_at, updated_at)
                VALUES (?, '# 보존 본문', ?, now(), now())
                """,
                documentId,
                "b".repeat(64)
        );

        documentService.delete(
                workspaceId,
                userId,
                documentId,
                "delete-" + suffix,
                new DocumentLifecycleRequest(1L)
        );

        Map<String, Object> deleted = jdbcTemplate.queryForMap(
                """
                SELECT current_version, content_hash, deleted_at, deleted_by
                FROM documents WHERE id = ?
                """,
                documentId
        );
        assertThat(deleted.get("current_version")).isEqualTo(2L);
        assertThat(deleted.get("content_hash")).isEqualTo("original-hash");
        assertThat(deleted.get("deleted_at")).isNotNull();
        assertThat(deleted.get("deleted_by")).isEqualTo(userId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT markdown FROM document_edit_states WHERE document_id = ?",
                String.class,
                documentId
        )).isEqualTo("# 보존 본문");
        assertThat(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                documentId, workspaceId)).isEmpty();

        documentService.restore(
                workspaceId,
                userId,
                documentId,
                "restore-" + suffix,
                new DocumentLifecycleRequest(2L)
        );

        Map<String, Object> restored = jdbcTemplate.queryForMap(
                """
                SELECT current_version, deleted_at, deleted_by, folder_id
                FROM documents WHERE id = ?
                """,
                documentId
        );
        assertThat(restored.get("current_version")).isEqualTo(3L);
        assertThat(restored.get("deleted_at")).isNull();
        assertThat(restored.get("deleted_by")).isNull();
        assertThat(restored.get("folder_id")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT markdown FROM document_edit_states WHERE document_id = ?",
                String.class,
                documentId
        )).isEqualTo("# 보존 본문");
    }

    @Test
    void markdownExport_readsLatestEditStateWithoutChangingDocument() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String documentId = "doc_" + suffix;
        insertUserAndWorkspace(userId, workspaceId);
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "회의 결과.md", "original-hash", "EDITABLE");
        jdbcTemplate.update(
                """
                INSERT INTO document_edit_states(document_id, markdown, content_hash, created_at, updated_at)
                VALUES (?, '# 최신 회의 결과\n한글 본문', ?, now(), now())
                """,
                documentId,
                "b".repeat(64)
        );
        Map<String, Object> before = jdbcTemplate.queryForMap(
                """
                SELECT current_version, updated_at
                FROM documents WHERE id = ?
                """,
                documentId
        );

        DocumentExportResult result =
                documentExportService.exportMarkdown(workspaceId, userId, documentId);

        assertThat(result.filename()).isEqualTo("회의 결과.md");
        assertThat(new String(result.bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("# 최신 회의 결과\n한글 본문");
        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT current_version, updated_at
                FROM documents WHERE id = ?
                """,
                documentId
        )).isEqualTo(before);
    }

    @Test
    void documentSoftDelete_sameIdempotencyKeyConcurrently_returnsOneResult() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String documentId = "doc_" + suffix;
        insertUserAndWorkspace(userId, workspaceId);
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "문서.md", "hash", "EDITABLE");

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                start.await();
                return documentService.delete(
                        workspaceId,
                        userId,
                        documentId,
                        "same-delete-key",
                        new DocumentLifecycleRequest(1L)
                );
            });
            Future<?> second = executor.submit(() -> {
                start.await();
                return documentService.delete(
                        workspaceId,
                        userId,
                        documentId,
                        "same-delete-key",
                        new DocumentLifecycleRequest(1L)
                );
            });
            start.countDown();

            assertThat(first.get()).isEqualTo(second.get());
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_version FROM documents WHERE id = ?",
                Long.class,
                documentId
        )).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM idempotency_records
                WHERE user_id = ? AND idempotency_key = 'same-delete-key'
                """,
                Integer.class,
                userId
        )).isEqualTo(1);
    }

    @Test
    void workspaceSoftDeleteAndRestore_preservesChildrenAndControlsMembershipAccess() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String documentId = "doc_" + suffix;
        insertUserAndWorkspace(userId, workspaceId);
        insertWorkspaceMember(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "문서.md", "hash", "EDITABLE");

        workspaceService.delete(userId, workspaceId, "workspace-delete-" + suffix);

        assertThat(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(
                workspaceId, userId)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM documents WHERE workspace_id = ?",
                Integer.class,
                workspaceId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM workspace_members WHERE workspace_id = ?",
                Integer.class,
                workspaceId
        )).isEqualTo(1);
        assertThat(documentRepository.findByIdInActiveWorkspace(documentId)).isEmpty();

        workspaceService.restore(userId, workspaceId, "workspace-restore-" + suffix);

        assertThat(workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(
                workspaceId, userId)).isTrue();
        assertThat(documentRepository.findByIdInActiveWorkspace(documentId)).isPresent();
    }

    @Test
    @Transactional
    void conditionalUpdates_allowOnlyCurrentBaseVersion() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String documentId = "doc_" + suffix;
        insertUserAndWorkspace(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "기존.md", "hash-before", "EDITABLE");

        Instant renamedAt = Instant.now();
        int renamed = documentRepository.renameIfVersionMatches(
                documentId,
                workspaceId,
                1,
                "새 제목.md",
                "새 제목",
                "새 제목.md",
                renamedAt
        );
        int staleRename = documentRepository.renameIfVersionMatches(
                documentId,
                workspaceId,
                1,
                "오래된 요청.md",
                "오래된 요청",
                "오래된 요청.md",
                Instant.now()
        );
        int contentUpdated = documentRepository.updateContentIfVersionMatches(
                documentId,
                workspaceId,
                2,
                "hash-after",
                42,
                Instant.now()
        );

        assertThat(renamed).isEqualTo(1);
        assertThat(staleRename).isZero();
        assertThat(contentUpdated).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                """
                SELECT filename, display_name, current_version, current_content_hash, byte_size
                FROM documents
                WHERE id = ?
                """,
                documentId
        )).containsAllEntriesOf(Map.of(
                "filename", "새 제목.md",
                "display_name", "새 제목",
                "current_version", 3L,
                "current_content_hash", "hash-after",
                "byte_size", 42L
        ));
    }

    @Test
    void visibleListAndSearchExcludeDeletedChatExportAndOtherWorkspaceDocuments() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String otherWorkspaceId = "ws_other_" + suffix;
        insertUserAndWorkspace(userId, workspaceId);
        insertWorkspace(otherWorkspaceId, userId);

        insertDocument("doc_visible_" + suffix, workspaceId, userId, "보고서.md", "visible-hash", "EDITABLE");
        insertDocument("doc_deleted_" + suffix, workspaceId, userId, "보고서 삭제.md", "deleted-hash", "EDITABLE");
        insertDocument("doc_chat_" + suffix, workspaceId, userId, "보고서 채팅.md", "chat-hash", "EDITABLE");
        insertDocument("doc_other_" + suffix, otherWorkspaceId, userId, "보고서 외부.md", "other-hash", "EDITABLE");
        jdbcTemplate.update(
                "UPDATE documents SET deleted_at = now() WHERE id = ?",
                "doc_deleted_" + suffix
        );
        jdbcTemplate.update(
                "UPDATE documents SET origin = 'chat_export' WHERE id = ?",
                "doc_chat_" + suffix
        );

        assertThat(documentRepository.findVisibleByWorkspaceId(workspaceId))
                .extracting(fruition.document.domain.Document::getId)
                .containsExactly("doc_visible_" + suffix);
        assertThat(documentRepository.searchVisibleByWorkspaceId(workspaceId, "보고서"))
                .extracting(fruition.document.domain.Document::getId)
                .containsExactly("doc_visible_" + suffix);
        assertThat(documentRepository.searchVisibleByWorkspaceId(workspaceId, "본문에만 있는 검색어"))
                .isEmpty();
        assertThat(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                "doc_deleted_" + suffix, workspaceId)).isEmpty();
        assertThat(documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                "doc_visible_" + suffix, otherWorkspaceId)).isEmpty();
        assertThat(documentRepository.findMaxRootSortOrder(workspaceId, DocumentRole.EDITABLE))
                .isZero();
    }

    @Test
    void editStateFolderAndIdempotencyConstraintsAreEnforced() {
        String suffix = UUID.randomUUID().toString();
        String userId = "user_" + suffix;
        String workspaceId = "ws_" + suffix;
        String documentId = "doc_" + suffix;
        UUID parentFolderId = UUID.randomUUID();
        UUID childFolderId = UUID.randomUUID();
        insertUserAndWorkspace(userId, workspaceId);
        insertDocument(documentId, workspaceId, userId, "note.md", "note-hash", "EDITABLE");

        jdbcTemplate.update(
                """
                INSERT INTO document_edit_states(document_id, markdown, content_hash, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                """,
                documentId,
                "# 제목",
                "edit-hash"
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO document_edit_states(document_id, markdown, content_hash, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                """,
                documentId,
                "# 중복",
                "other-hash"
        )).isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update(
                """
                INSERT INTO folders(
                    id, workspace_id, parent_folder_id, name, sort_order, current_version, created_at, updated_at
                ) VALUES (?, ?, NULL, ?, 0, 1, now(), now())
                """,
                parentFolderId,
                workspaceId,
                "부모"
        );
        jdbcTemplate.update(
                """
                INSERT INTO folders(
                    id, workspace_id, parent_folder_id, name, sort_order, current_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 0, 1, now(), now())
                """,
                childFolderId,
                workspaceId,
                parentFolderId,
                "자식"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT parent_folder_id FROM folders WHERE id = ?",
                UUID.class,
                childFolderId
        )).isEqualTo(parentFolderId);

        UUID firstRequestId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO idempotency_records(
                    id, user_id, endpoint_scope, idempotency_key, request_hash,
                    response_status, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, 201, ?, ?)
                """,
                firstRequestId,
                userId,
                "POST:/documents/markdown",
                "same-key",
                "request-hash",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plusSeconds(86_400))
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO idempotency_records(
                    id, user_id, endpoint_scope, idempotency_key, request_hash,
                    response_status, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, 201, ?, ?)
                """,
                UUID.randomUUID(),
                userId,
                "POST:/documents/markdown",
                "same-key",
                "different-request-hash",
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plusSeconds(86_400))
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migration_backfillsExistingV8Documents() throws Exception {
        String databaseName = "backfill_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword()
        ); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }

        String databaseUrl = "jdbc:postgresql://"
                + postgresContainer.getHost()
                + ":"
                + postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/"
                + databaseName;

        Flyway.configure()
                .dataSource(databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword())
                .target(MigrationVersion.fromVersion("8"))
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                databaseUrl,
                postgresContainer.getUsername(),
                postgresContainer.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    INSERT INTO users(id, email, display_name, password_hash, created_at, updated_at)
                    VALUES ('user_backfill', 'backfill@example.com', 'tester', NULL, now(), now())
                    """
            );
            statement.executeUpdate(
                    """
                    INSERT INTO workspaces(id, name, created_at, updated_at)
                    VALUES ('ws_backfill', 'workspace', now(), now())
                    """
            );
            statement.executeUpdate(legacyDocumentInsert(
                    "doc_a", "첫 문서.md", "text/markdown", "hash-a", "sources/doc_a"
            ));
            statement.executeUpdate(legacyDocumentInsert(
                    "doc_b", "둘째.MD", "application/octet-stream", "hash-b", "sources/doc_b"
            ));
            statement.executeUpdate(legacyDocumentInsert(
                    "doc_c", "원본.pdf", "application/pdf", "hash-c", "sources/doc_c"
            ));
        }

        Flyway.configure()
                .dataSource(databaseUrl, postgresContainer.getUsername(), postgresContainer.getPassword())
                .load()
                .migrate();

        List<Map<String, Object>> documents = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                databaseUrl,
                postgresContainer.getUsername(),
                postgresContainer.getPassword()
        ); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     """
                     SELECT id, display_name, normalized_filename, document_role,
                            folder_id, sort_order,
                            current_version, current_content_hash
                     FROM documents
                     ORDER BY id
                     """
             )) {
            while (resultSet.next()) {
                documents.add(Map.ofEntries(
                        Map.entry("id", resultSet.getString("id")),
                        Map.entry("displayName", resultSet.getString("display_name")),
                        Map.entry("normalizedFilename", resultSet.getString("normalized_filename")),
                        Map.entry("documentRole", resultSet.getString("document_role")),
                        Map.entry("sortOrder", resultSet.getLong("sort_order")),
                        Map.entry("currentVersion", resultSet.getLong("current_version")),
                        Map.entry("currentContentHash", resultSet.getString("current_content_hash")),
                        Map.entry("folderIsNull", resultSet.getObject("folder_id") == null)
                ));
            }

            try (ResultSet editStateCount = statement.executeQuery("SELECT count(*) FROM document_edit_states")) {
                editStateCount.next();
                assertThat(editStateCount.getInt(1)).isZero();
            }
        }

        assertThat(documents).containsExactly(
                Map.of(
                        "id", "doc_a",
                        "displayName", "첫 문서",
                        "normalizedFilename", "첫 문서.md",
                        "documentRole", "EDITABLE",
                        "sortOrder", 0L,
                        "currentVersion", 1L,
                        "currentContentHash", "hash-a",
                        "folderIsNull", true
                ),
                Map.of(
                        "id", "doc_b",
                        "displayName", "둘째",
                        "normalizedFilename", "둘째.md",
                        "documentRole", "EDITABLE",
                        "sortOrder", 1L,
                        "currentVersion", 1L,
                        "currentContentHash", "hash-b",
                        "folderIsNull", true
                ),
                Map.of(
                        "id", "doc_c",
                        "displayName", "원본",
                        "normalizedFilename", "원본.pdf",
                        "documentRole", "ORIGINAL",
                        "sortOrder", 0L,
                        "currentVersion", 1L,
                        "currentContentHash", "hash-c",
                        "folderIsNull", true
                )
        );
    }

    private void insertUserAndWorkspace(String userId, String workspaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO users(id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, NULL, now(), now())
                """,
                userId,
                userId + "@example.com",
                "tester"
        );
        jdbcTemplate.update(
                "INSERT INTO workspaces(id, name, created_at, updated_at) VALUES (?, ?, now(), now())",
                workspaceId,
                "workspace"
        );
    }

    private void insertWorkspaceMember(String userId, String workspaceId) {
        jdbcTemplate.update(
                """
                INSERT INTO workspace_members(joined_at, role, user_id, workspace_id)
                VALUES (now(), 'OWNER', ?, ?)
                """,
                userId,
                workspaceId
        );
    }

    private void insertWorkspace(String workspaceId, String userId) {
        jdbcTemplate.update(
                "INSERT INTO workspaces(id, name, created_at, updated_at) VALUES (?, ?, now(), now())",
                workspaceId,
                "workspace-" + userId
        );
    }

    private void insertDocument(
            String documentId,
            String workspaceId,
            String userId,
            String filename,
            String contentHash,
            String documentRole
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, display_name, normalized_filename,
                    mime_type, source_uri, status, uploaded_at, updated_at, user_id, workspace_id,
                    current_content_hash, current_version, document_role, sort_order
                ) VALUES (?, 1, ?, ?, ?, ?, ?, ?, 'completed', now(), now(), ?, ?, ?, 1, ?, 0)
                """,
                documentId,
                contentHash,
                filename,
                filename.substring(0, filename.lastIndexOf('.')),
                filename.toLowerCase(),
                documentRole.equals("EDITABLE") ? "text/markdown" : "application/pdf",
                documentRole.equals("EDITABLE") ? null : "sources/" + documentId,
                userId,
                workspaceId,
                contentHash,
                documentRole
        );
    }

    private String legacyDocumentInsert(
            String documentId,
            String filename,
            String mimeType,
            String contentHash,
            String sourceUri
    ) {
        return """
                INSERT INTO documents(
                    id, byte_size, content_hash, filename, mime_type, source_uri,
                    status, uploaded_at, user_id, workspace_id
                ) VALUES (
                    '%s', 1, '%s', '%s', '%s', '%s',
                    'completed', '2026-07-24 00:00:00+00', 'user_backfill', 'ws_backfill'
                )
                """.formatted(documentId, contentHash, filename, mimeType, sourceUri);
    }
}
