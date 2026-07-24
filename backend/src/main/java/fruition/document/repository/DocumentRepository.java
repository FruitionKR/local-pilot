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
}
