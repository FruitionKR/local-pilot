package fruition.core.document.repository;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentStatus;
import fruition.core.document.domain.DocumentRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {

    /** 중복 판별: 같은 workspace 안에서만 동일 content_hash를 중복으로 본다. */
    Optional<Document> findByWorkspaceIdAndContentHash(String workspaceId, String contentHash);

    /** 완료 후처리(reconcile) 대상: 아직 후처리 안 된(reconciled_at IS NULL) origin·status 문서. */
    List<Document> findAllByOriginAndStatusAndReconciledAtIsNull(String origin, DocumentStatus status);

    List<Document> findAllByWorkspaceId(String workspaceId);

    /** 호환 문서 목록: 채팅 편입 문서를 포함한 활성 문서를 공용 순서로 조회한다. */
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findVisibleByWorkspaceId(@Param("workspaceId") String workspaceId);

    /** 파일명 검색은 본문을 조회하지 않는다. */
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL "
            + "AND (LOWER(d.displayName) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "OR LOWER(d.filename) LIKE LOWER(CONCAT('%', :query, '%'))) "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> searchVisibleByWorkspaceId(
            @Param("workspaceId") String workspaceId,
            @Param("query") String query
    );

    Optional<Document> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.id = :documentId "
            + "AND d.workspaceId = :workspaceId")
    Optional<Document> findByIdAndWorkspaceIdForUpdate(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId
    );

    // Workspace entity는 access-svc 소유라 JPQL로 참조할 수 없다 — 테이블 기준 native query로 조회한다.
    @Query(value = "SELECT d.* FROM documents d WHERE d.id = :documentId "
            + "AND d.deleted_at IS NULL "
            + "AND EXISTS (SELECT w.id FROM workspaces w "
            + "WHERE w.id = d.workspace_id AND w.deleted_at IS NULL)", nativeQuery = true)
    Optional<Document> findByIdInActiveWorkspace(
            @Param("documentId") String documentId
    );

    Optional<Document> findByIdAndWorkspaceIdAndDeletedAtIsNotNull(String id, String workspaceId);

    List<Document> findAllByWorkspaceIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
            String workspaceId
    );

    @Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d "
            + "WHERE d.workspaceId = :workspaceId "
            + "AND d.documentRole = :documentRole "
            + "AND d.folderId IS NULL "
            + "AND d.deletedAt IS NULL")
    long findMaxRootSortOrder(
            @Param("workspaceId") String workspaceId,
            @Param("documentRole") DocumentRole documentRole
    );

    @Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d "
            + "WHERE d.workspaceId = :workspaceId "
            + "AND ((:folderId IS NULL AND d.folderId IS NULL) OR d.folderId = :folderId) "
            + "AND d.deletedAt IS NULL")
    long findMaxSortOrderInFolder(
            @Param("workspaceId") String workspaceId,
            @Param("folderId") java.util.UUID folderId
    );

    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND ((:folderId IS NULL AND d.folderId IS NULL) OR d.folderId = :folderId) "
            + "AND d.deletedAt IS NULL "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findChildDocuments(
            @Param("workspaceId") String workspaceId,
            @Param("folderId") java.util.UUID folderId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND ((:folderId IS NULL AND d.folderId IS NULL) OR d.folderId = :folderId) "
            + "AND d.deletedAt IS NULL "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findChildDocumentsForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("folderId") java.util.UUID folderId
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Document d SET d.sortOrder = :sortOrder, d.updatedAt = :updatedAt "
            + "WHERE d.id = :id AND d.workspaceId = :workspaceId AND d.deletedAt IS NULL")
    void updateSortOrder(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("sortOrder") long sortOrder,
            @Param("updatedAt") Instant updatedAt
    );

    boolean existsByWorkspaceIdAndFolderIdAndDeletedAtIsNull(String workspaceId, java.util.UUID folderId);

    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId AND d.deletedAt IS NULL "
            + "AND (LOWER(d.displayName) LIKE :pattern OR d.normalizedFilename LIKE :pattern) "
            + "ORDER BY d.displayName ASC, d.id ASC")
    List<Document> searchByName(
            @Param("workspaceId") String workspaceId,
            @Param("pattern") String pattern
    );

    /** 루트 폴더와 그 하위 폴더에 속한 문서 전체를 같은 삭제 작업 ID로 소프트 삭제한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "WITH RECURSIVE subtree AS ("
            + "SELECT id FROM folders WHERE id = :rootId "
            + "UNION ALL "
            + "SELECT f.id FROM folders f JOIN subtree s ON f.parent_folder_id = s.id) "
            + "UPDATE documents SET deleted_at = :deletedAt, deleted_by = :deletedBy, "
            + "delete_operation_id = :operationId, current_version = current_version + 1, updated_at = :deletedAt "
            + "WHERE folder_id IN (SELECT id FROM subtree) AND deleted_at IS NULL",
            nativeQuery = true)
    void softDeleteDocumentsInSubtree(
            @Param("rootId") java.util.UUID rootId,
            @Param("deletedBy") String deletedBy,
            @Param("deletedAt") Instant deletedAt,
            @Param("operationId") java.util.UUID operationId
    );

    /** 복구 대상 폴더의 하위 트리에 속하고 같은 삭제 작업으로 삭제된 문서만 되살린다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "WITH RECURSIVE subtree AS ("
            + "SELECT id FROM folders WHERE id = :rootId "
            + "UNION ALL "
            + "SELECT f.id FROM folders f JOIN subtree s ON f.parent_folder_id = s.id) "
            + "UPDATE documents SET deleted_at = NULL, deleted_by = NULL, delete_operation_id = NULL, "
            + "current_version = current_version + 1, updated_at = :restoredAt "
            + "WHERE folder_id IN (SELECT id FROM subtree) "
            + "AND deleted_at IS NOT NULL AND delete_operation_id = :operationId",
            nativeQuery = true)
    void restoreDocumentsInSubtree(
            @Param("rootId") java.util.UUID rootId,
            @Param("operationId") java.util.UUID operationId,
            @Param("restoredAt") Instant restoredAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.folderId = :folderId, d.sortOrder = :sortOrder, d.updatedAt = :updatedAt "
            + "WHERE d.id = :documentId AND d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL AND d.currentVersion = :baseVersion")
    int moveIfVersionMatches(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("folderId") java.util.UUID folderId,
            @Param("sortOrder") long sortOrder,
            @Param("updatedAt") Instant updatedAt
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.documentRole = fruition.core.document.domain.DocumentRole.EDITABLE "
            + "AND ((:folderId IS NULL AND d.folderId IS NULL) "
            + "OR d.folderId = :folderId) "
            + "AND d.deletedAt IS NULL "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findSiblingPagesForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("folderId") java.util.UUID folderId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.documentRole = :documentRole "
            + "AND d.folderId IS NULL "
            + "AND d.deletedAt IS NULL "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findRootItemsForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("documentRole") DocumentRole documentRole
    );

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.currentContentHash = :contentHash, d.byteSize = :byteSize, d.updatedAt = :updatedAt "
            + "WHERE d.id = :documentId AND d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL AND d.currentVersion = :baseVersion")
    int updateContentIfVersionMatches(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("contentHash") String contentHash,
            @Param("byteSize") long byteSize,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.filename = :filename, d.displayName = :displayName, "
            + "d.normalizedFilename = :normalizedFilename, d.updatedAt = :updatedAt "
            + "WHERE d.id = :documentId AND d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL AND d.currentVersion = :baseVersion")
    int renameIfVersionMatches(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("filename") String filename,
            @Param("displayName") String displayName,
            @Param("normalizedFilename") String normalizedFilename,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.deletedAt = :deletedAt, d.deletedBy = :deletedBy, "
            + "d.deleteOperationId = :deleteOperationId, d.updatedAt = :deletedAt "
            + "WHERE d.id = :documentId AND d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL AND d.currentVersion = :baseVersion")
    int softDeleteIfVersionMatches(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("deletedBy") String deletedBy,
            @Param("deletedAt") Instant deletedAt,
            @Param("deleteOperationId") java.util.UUID deleteOperationId
    );

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.deletedAt = NULL, d.deletedBy = NULL, d.deleteOperationId = NULL, "
            + "d.folderId = NULL, "
            + "d.sortOrder = :sortOrder, d.updatedAt = :restoredAt "
            + "WHERE d.id = :documentId AND d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NOT NULL AND d.currentVersion = :baseVersion")
    int restoreIfVersionMatches(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("sortOrder") long sortOrder,
            @Param("restoredAt") Instant restoredAt
    );
}
