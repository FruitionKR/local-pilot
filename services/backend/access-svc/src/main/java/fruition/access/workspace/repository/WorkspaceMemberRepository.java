package fruition.access.workspace.repository;

import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceMember;
import fruition.access.workspace.domain.WorkspaceMemberId;
import fruition.access.workspace.domain.WorkspaceRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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

    @Query("""
            SELECT m.role
            FROM WorkspaceMember m
            WHERE m.workspace.id = :workspaceId
              AND m.user.id = :userId
              AND m.workspace.deletedAt IS NULL
            """)
    Optional<WorkspaceRole> findActiveRole(
            @Param("workspaceId") String workspaceId,
            @Param("userId") String userId
    );

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
              AND m.role = :role
            """)
    Optional<Workspace> findOwnedWorkspaceIncludingDeleted(
            @Param("workspaceId") String workspaceId,
            @Param("userId") String userId,
            @Param("role") WorkspaceRole role
    );

    @Query("""
            SELECT m.workspace
            FROM WorkspaceMember m
            WHERE m.user.id = :userId
              AND m.role = :role
              AND m.workspace.deletedAt IS NOT NULL
            ORDER BY m.workspace.deletedAt DESC
            """)
    List<Workspace> findDeletedOwnedWorkspaces(
            @Param("userId") String userId,
            @Param("role") WorkspaceRole role
    );
}
