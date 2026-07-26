package fruition.document.repository;

import fruition.document.domain.SourceFolder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceFolderRepository extends JpaRepository<SourceFolder, UUID> {

    Optional<SourceFolder> findByIdAndWorkspaceIdAndDeletedAtIsNull(UUID id, String workspaceId);

    List<SourceFolder> findAllByWorkspaceIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(String workspaceId);

    List<SourceFolder> findByWorkspaceIdAndParentFolderIdAndDeletedAtIsNull(String workspaceId, UUID parentFolderId);

    @Query("SELECT COALESCE(MAX(f.sortOrder), -1) FROM SourceFolder f "
            + "WHERE f.workspaceId = :workspaceId AND f.deletedAt IS NULL "
            + "AND ((:parentFolderId IS NULL AND f.parentFolderId IS NULL) "
            + "OR f.parentFolderId = :parentFolderId)")
    long findMaxSortOrder(
            @Param("workspaceId") String workspaceId,
            @Param("parentFolderId") UUID parentFolderId
    );

    /** 이름·상위 폴더·정렬을 한 번에 원자적으로 변경한다(PATCH). */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE SourceFolder f SET f.currentVersion = f.currentVersion + 1, "
            + "f.name = :name, f.parentFolderId = :parentFolderId, "
            + "f.sortOrder = :sortOrder, f.updatedAt = :updatedAt "
            + "WHERE f.id = :id AND f.workspaceId = :workspaceId "
            + "AND f.deletedAt IS NULL AND f.currentVersion = :baseVersion")
    int updateIfVersionMatches(
            @Param("id") UUID id,
            @Param("workspaceId") String workspaceId,
            @Param("baseVersion") long baseVersion,
            @Param("name") String name,
            @Param("parentFolderId") UUID parentFolderId,
            @Param("sortOrder") long sortOrder,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("UPDATE SourceFolder f SET f.currentVersion = f.currentVersion + 1, "
            + "f.deletedAt = :deletedAt, f.deletedBy = :deletedBy, "
            + "f.deleteOperationId = :deleteOperationId, f.updatedAt = :deletedAt "
            + "WHERE f.id IN :ids AND f.deletedAt IS NULL")
    int softDeleteByIds(
            @Param("ids") List<UUID> ids,
            @Param("deletedBy") String deletedBy,
            @Param("deletedAt") Instant deletedAt,
            @Param("deleteOperationId") UUID deleteOperationId
    );
}
