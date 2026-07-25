package fruition.workspace.repository;

import fruition.workspace.domain.Workspace;
import fruition.workspace.domain.WorkspaceMember;
import fruition.workspace.domain.WorkspaceMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, WorkspaceMemberId> {

    @Query("""
            SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END
            FROM WorkspaceMember m
            WHERE m.workspace.id = :workspaceId
              AND m.user.id = :userId
              AND m.workspace.deletedAt IS NULL
            """)
    boolean existsByWorkspace_IdAndUser_Id(
            @Param("workspaceId") String workspaceId,
            @Param("userId") String userId
    );

    Optional<WorkspaceMember> findByWorkspace_IdAndUser_Id(String workspaceId, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT m.workspace
            FROM WorkspaceMember m
            WHERE m.user.id = :userId
              AND m.workspace.deletedAt IS NULL
            ORDER BY m.workspace.createdAt DESC
            """)
    List<Workspace> findAllWorkspacesByUserId(@Param("userId") String userId);

    @Query("""
            SELECT m.workspace
            FROM WorkspaceMember m
            WHERE m.workspace.id = :workspaceId
              AND m.user.id = :userId
              AND m.role = 'OWNER'
            """)
    Optional<Workspace> findOwnedWorkspaceIncludingDeleted(
            @Param("workspaceId") String workspaceId,
            @Param("userId") String userId
    );

    @Query("""
            SELECT m.workspace
            FROM WorkspaceMember m
            WHERE m.user.id = :userId
              AND m.role = 'OWNER'
              AND m.workspace.deletedAt IS NOT NULL
            ORDER BY m.workspace.deletedAt DESC
            """)
    List<Workspace> findDeletedOwnedWorkspaces(@Param("userId") String userId);
}
