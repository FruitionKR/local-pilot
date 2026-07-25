package fruition.document.repository;

import fruition.document.domain.Folder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FolderRepository extends JpaRepository<Folder, UUID> {

    Optional<Folder> findByIdAndWorkspaceIdAndDeletedAtIsNull(UUID id, String workspaceId);

    boolean existsByWorkspaceIdAndParentFolderIdAndDeletedAtIsNull(String workspaceId, UUID parentFolderId);

    @Query("SELECT COALESCE(MAX(f.sortOrder), -1) FROM Folder f "
            + "WHERE f.workspaceId = :workspaceId "
            + "AND ((:parentFolderId IS NULL AND f.parentFolderId IS NULL) "
            + "OR f.parentFolderId = :parentFolderId) "
            + "AND f.deletedAt IS NULL")
    long findMaxSortOrder(
            @Param("workspaceId") String workspaceId,
            @Param("parentFolderId") UUID parentFolderId
    );

    @Query("SELECT f FROM Folder f WHERE f.workspaceId = :workspaceId "
            + "AND ((:parentFolderId IS NULL AND f.parentFolderId IS NULL) "
            + "OR f.parentFolderId = :parentFolderId) "
            + "AND f.deletedAt IS NULL "
            + "ORDER BY f.sortOrder ASC, f.id ASC")
    List<Folder> findChildren(
            @Param("workspaceId") String workspaceId,
            @Param("parentFolderId") UUID parentFolderId
    );

    /** 대상 부모 폴더의 조상 경로(자기 자신 포함)에 이동 폴더가 있으면 순환이다. */
    @Query(value = "WITH RECURSIVE ancestors AS ("
            + "SELECT id, parent_folder_id FROM folders WHERE id = :targetParentId "
            + "UNION ALL "
            + "SELECT f.id, f.parent_folder_id FROM folders f "
            + "JOIN ancestors a ON f.id = a.parent_folder_id) "
            + "SELECT count(*) FROM ancestors WHERE id = :movingFolderId",
            nativeQuery = true)
    long countAncestorMatches(
            @Param("targetParentId") UUID targetParentId,
            @Param("movingFolderId") UUID movingFolderId
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Folder f SET f.currentVersion = f.currentVersion + 1, "
            + "f.name = :name, f.updatedAt = :updatedAt "
            + "WHERE f.id = :id AND f.workspaceId = :workspaceId "
            + "AND f.deletedAt IS NULL AND f.currentVersion = :baseVersion")
    int renameIfVersionMatches(
            @Param("id") UUID id,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("name") String name,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Folder f SET f.currentVersion = f.currentVersion + 1, "
            + "f.parentFolderId = :parentFolderId, f.sortOrder = :sortOrder, f.updatedAt = :updatedAt "
            + "WHERE f.id = :id AND f.workspaceId = :workspaceId "
            + "AND f.deletedAt IS NULL AND f.currentVersion = :baseVersion")
    int moveIfVersionMatches(
            @Param("id") UUID id,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("parentFolderId") UUID parentFolderId,
            @Param("sortOrder") long sortOrder,
            @Param("updatedAt") Instant updatedAt
    );
}
