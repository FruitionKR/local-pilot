package fruition.document.repository;

import fruition.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
                "parent_document_id",
                "source_folder_id",
                "sort_order",
                "updated_at",
                "deleted_at",
                "deleted_by",
                "delete_operation_id"
        );

        for (String table : List.of("document_edit_states", "source_folders", "idempotency_records")) {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL",
                    Boolean.class,
                    "public." + table
            );
            assertThat(exists).as(table).isTrue();
        }
    }

    @Test
    void documents_allowSameContentAndEnforceRoleParentRules() {
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
                INSERT INTO source_folders(
                    id, workspace_id, parent_folder_id, name, sort_order, current_version, created_at, updated_at
                ) VALUES (?, ?, NULL, ?, 0, 1, now(), now())
                """,
                folderId,
                workspaceId,
                "원본"
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE documents SET source_folder_id = ? WHERE id = ?",
                folderId,
                "doc_parent_" + suffix
        )).isInstanceOf(DataIntegrityViolationException.class);

        insertDocument("doc_original_" + suffix, workspaceId, userId, "original.pdf", "pdf-hash", "ORIGINAL");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE documents SET parent_document_id = ? WHERE id = ?",
                "doc_parent_" + suffix,
                "doc_original_" + suffix
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE documents SET source_document_id = id WHERE id = ?",
                "doc_original_" + suffix
        )).isInstanceOf(DataIntegrityViolationException.class);
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
                INSERT INTO source_folders(
                    id, workspace_id, parent_folder_id, name, sort_order, current_version, created_at, updated_at
                ) VALUES (?, ?, NULL, ?, 0, 1, now(), now())
                """,
                parentFolderId,
                workspaceId,
                "부모"
        );
        jdbcTemplate.update(
                """
                INSERT INTO source_folders(
                    id, workspace_id, parent_folder_id, name, sort_order, current_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 0, 1, now(), now())
                """,
                childFolderId,
                workspaceId,
                parentFolderId,
                "자식"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT parent_folder_id FROM source_folders WHERE id = ?",
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
                            parent_document_id, source_folder_id, sort_order,
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
                        Map.entry("parentIsNull", resultSet.getObject("parent_document_id") == null),
                        Map.entry("folderIsNull", resultSet.getObject("source_folder_id") == null)
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
                        "parentIsNull", true,
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
                        "parentIsNull", true,
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
                        "parentIsNull", true,
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
