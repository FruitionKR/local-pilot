package fruition.document.repository;

import fruition.document.domain.Document;
import fruition.document.domain.DocumentStatus;
import fruition.document.domain.DocumentRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, String> {

    /** 중복 판별: 같은 workspace 안에서만 동일 content_hash를 중복으로 본다. */
    Optional<Document> findByWorkspaceIdAndContentHash(String workspaceId, String contentHash);

    /** 완료 후처리(reconcile) 대상: 아직 후처리 안 된(reconciled_at IS NULL) origin·status 문서. */
    List<Document> findAllByOriginAndStatusAndReconciledAtIsNull(String origin, DocumentStatus status);

    List<Document> findAllByWorkspaceId(String workspaceId);

    /** 호환 문서 목록: 활성 문서만 공용 순서로 조회하고 chat_export는 제외한다. */
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL "
            + "AND (d.origin IS NULL OR d.origin <> 'chat_export') "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findVisibleByWorkspaceId(@Param("workspaceId") String workspaceId);

    /** 파일명 검색은 본문을 조회하지 않는다. */
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL "
            + "AND (d.origin IS NULL OR d.origin <> 'chat_export') "
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

    @Query("SELECT d FROM Document d WHERE d.id = :documentId "
            + "AND d.deletedAt IS NULL "
            + "AND EXISTS (SELECT w.id FROM Workspace w "
            + "WHERE w.id = d.workspaceId AND w.deletedAt IS NULL)")
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
            + "AND d.parentDocumentId IS NULL "
            + "AND d.sourceFolderId IS NULL "
            + "AND d.deletedAt IS NULL")
    long findMaxRootSortOrder(
            @Param("workspaceId") String workspaceId,
            @Param("documentRole") DocumentRole documentRole
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.documentRole = fruition.document.domain.DocumentRole.EDITABLE "
            + "AND ((:parentDocumentId IS NULL AND d.parentDocumentId IS NULL) "
            + "OR d.parentDocumentId = :parentDocumentId) "
            + "AND d.deletedAt IS NULL "
            + "ORDER BY d.sortOrder ASC, d.id ASC")
    List<Document> findSiblingPagesForUpdate(
            @Param("workspaceId") String workspaceId,
            @Param("parentDocumentId") String parentDocumentId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.workspaceId = :workspaceId "
            + "AND d.documentRole = :documentRole "
            + "AND d.parentDocumentId IS NULL "
            + "AND d.sourceFolderId IS NULL "
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
            + "d.parentDocumentId = NULL, d.sourceFolderId = NULL, "
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

    /** 대상 폴더(또는 root, folderId=null) 안 활성 문서의 최대 sort_order. 배치 기본 위치(끝) 계산용. */
    @Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d "
            + "WHERE d.workspaceId = :workspaceId AND d.deletedAt IS NULL "
            + "AND ((:folderId IS NULL AND d.sourceFolderId IS NULL) OR d.sourceFolderId = :folderId)")
    long findMaxSortOrderInFolder(
            @Param("workspaceId") String workspaceId,
            @Param("folderId") UUID folderId
    );

    /** 문서를 폴더(또는 root, folderId=null)로 배치한다. 폴더 배치는 페이지 중첩(parent_document_id)을 해제한다. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.sourceFolderId = :sourceFolderId, d.parentDocumentId = NULL, "
            + "d.sortOrder = :sortOrder, d.updatedAt = :updatedAt "
            + "WHERE d.id = :documentId AND d.workspaceId = :workspaceId "
            + "AND d.deletedAt IS NULL AND d.currentVersion = :baseVersion")
    int placeIfVersionMatches(
            @Param("documentId") String documentId,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("sourceFolderId") UUID sourceFolderId,
            @Param("sortOrder") long sortOrder,
            @Param("updatedAt") Instant updatedAt
    );

    /** 폴더 cascade 삭제: 지정 폴더들에 직접 배치된 활성 문서를 함께 소프트 삭제한다. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Document d SET d.currentVersion = d.currentVersion + 1, "
            + "d.deletedAt = :deletedAt, d.deletedBy = :deletedBy, "
            + "d.deleteOperationId = :deleteOperationId, d.updatedAt = :deletedAt "
            + "WHERE d.sourceFolderId IN :folderIds AND d.deletedAt IS NULL")
    int softDeleteBySourceFolderIds(
            @Param("folderIds") List<UUID> folderIds,
            @Param("deletedBy") String deletedBy,
            @Param("deletedAt") Instant deletedAt,
            @Param("deleteOperationId") UUID deleteOperationId
    );
}
